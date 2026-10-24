package com.zhangwenkang.cinefin.repository

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

sealed interface MetadataSnapshotReadResult {
    data class Hit(val value: Any, val savedAtEpochMs: Long) : MetadataSnapshotReadResult

    data class Miss(val reason: MetadataSnapshotMissReason) : MetadataSnapshotReadResult
}

enum class MetadataSnapshotMissReason {
    NOT_FOUND,
    EXPIRED,
    INVALID_IDENTITY,
    INVALID_KEY,
    UNREADABLE,
    MALFORMED,
    SCHEMA_MISMATCH,
    NAMESPACE_MISMATCH,
    KEY_MISMATCH,
    CHECKSUM_MISMATCH,
    UNSUPPORTED_PAYLOAD,
}

sealed interface MetadataSnapshotWriteResult {
    data object Success : MetadataSnapshotWriteResult

    data class Degraded(
        val reason: MetadataSnapshotWriteFailure,
        val snapshotPersisted: Boolean,
        val detail: String? = null,
    ) : MetadataSnapshotWriteResult
}

enum class MetadataSnapshotWriteFailure {
    INVALID_IDENTITY,
    UNSUPPORTED_PAYLOAD,
    TOO_LARGE,
    WRITE_FAILED,
    PRUNE_FAILED,
}

internal fun interface MetadataSnapshotAtomicWriter {
    fun writeAndReplace(partFile: File, targetFile: File, bytes: ByteArray)
}

internal object AtomicSnapshotFileWriter : MetadataSnapshotAtomicWriter {
    override fun writeAndReplace(partFile: File, targetFile: File, bytes: ByteArray) {
        FileOutputStream(partFile).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                partFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            throw IOException("atomic snapshot replace is not supported", error)
        }
    }
}

/**
 * Persistent metadata snapshots with fail-closed validation and bounded on-disk LRU.
 *
 * Snapshot files are addressed exclusively by SHA-256 hashes, so raw namespace and logical keys
 * cannot traverse outside [directory]. Reads enforce envelope schema, identity, checksum, and TTL.
 * Invalid files are removed and returned as misses. Writes are serialized per namespace and use an
 * fsynced `.part` file followed by an atomic rename, preserving the previous snapshot on error.
 */
class MetadataSnapshotStore
internal constructor(
    directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val atomicWriter: MetadataSnapshotAtomicWriter = AtomicSnapshotFileWriter,
    private val codec: MetadataSnapshotCodec = MetadataSnapshotCodec(),
) {
    constructor(
        directory: File
    ) : this(
        directory = directory,
        clock = System::currentTimeMillis,
        maxFiles = DEFAULT_MAX_FILES,
        maxBytes = DEFAULT_MAX_BYTES,
        atomicWriter = AtomicSnapshotFileWriter,
        codec = MetadataSnapshotCodec(),
    )

    private val storeRoot = File(directory, "metadata-snapshots/v1")
    private val canonicalStoreRoot = storeRoot.canonicalFile
    private val namespaceLocks = ConcurrentHashMap<String, Any>()
    private val envelopeJson = Json

    init {
        require(maxFiles > 0) { "maxFiles must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    fun write(
        namespace: MetadataNamespace,
        logicalKey: String,
        value: Any,
        savedAtEpochMs: Long = clock(),
    ): MetadataSnapshotWriteResult {
        if (!isValidNamespace(namespace)) {
            return MetadataSnapshotWriteResult.Degraded(
                reason = MetadataSnapshotWriteFailure.INVALID_IDENTITY,
                snapshotPersisted = false,
            )
        }
        if (!isValidKey(logicalKey)) {
            return MetadataSnapshotWriteResult.Degraded(
                reason = MetadataSnapshotWriteFailure.INVALID_IDENTITY,
                snapshotPersisted = false,
            )
        }

        val encoded =
            codec.encode(value)
                ?: return MetadataSnapshotWriteResult.Degraded(
                    reason = MetadataSnapshotWriteFailure.UNSUPPORTED_PAYLOAD,
                    snapshotPersisted = false,
                )
        val payloadText = encoded.payload.toString()
        val envelope = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("serverId", namespace.serverId)
            put("userId", namespace.userId)
            put("logicalKey", logicalKey)
            put("savedAt", savedAtEpochMs)
            put("payloadKind", encoded.kind.wireName)
            put("payloadSha256", sha256(payloadText))
            put("payload", encoded.payload)
        }
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > maxBytes) {
            return MetadataSnapshotWriteResult.Degraded(
                reason = MetadataSnapshotWriteFailure.TOO_LARGE,
                snapshotPersisted = false,
            )
        }

        val target = requireNotNull(fileFor(namespace, logicalKey))
        val lock = namespaceLock(namespace)
        return synchronized(lock) {
            val targetDirectory = requireNotNull(target.parentFile)
            val part = File(targetDirectory, "${target.name}.part")
            try {
                Files.createDirectories(targetDirectory.toPath())
                atomicWriter.writeAndReplace(part, target, bytes)
            } catch (error: Exception) {
                return@synchronized MetadataSnapshotWriteResult.Degraded(
                    reason = MetadataSnapshotWriteFailure.WRITE_FAILED,
                    snapshotPersisted = false,
                    detail = error.message,
                )
            } finally {
                runCatching { Files.deleteIfExists(part.toPath()) }
            }

            try {
                prune()
                MetadataSnapshotWriteResult.Success
            } catch (error: Exception) {
                MetadataSnapshotWriteResult.Degraded(
                    reason = MetadataSnapshotWriteFailure.PRUNE_FAILED,
                    snapshotPersisted = true,
                    detail = error.message,
                )
            }
        }
    }

    fun read(
        namespace: MetadataNamespace,
        logicalKey: String,
        maxAgeMs: Long,
    ): MetadataSnapshotReadResult {
        require(maxAgeMs >= 0L) { "maxAgeMs must not be negative" }
        if (!isValidNamespace(namespace)) {
            return MetadataSnapshotReadResult.Miss(MetadataSnapshotMissReason.INVALID_IDENTITY)
        }
        if (!isValidKey(logicalKey)) {
            return MetadataSnapshotReadResult.Miss(MetadataSnapshotMissReason.INVALID_KEY)
        }

        val file = requireNotNull(fileFor(namespace, logicalKey))
        if (!file.isFile) {
            return MetadataSnapshotReadResult.Miss(MetadataSnapshotMissReason.NOT_FOUND)
        }
        if (file.length() > maxBytes) {
            return cleanupMiss(file, MetadataSnapshotMissReason.UNREADABLE)
        }

        val envelope =
            try {
                envelopeJson.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            } catch (_: Exception) {
                return cleanupMiss(file, MetadataSnapshotMissReason.MALFORMED)
            }

        val schemaVersion = envelope.primitive("schemaVersion")?.intOrNull
        if (schemaVersion != SCHEMA_VERSION) {
            return cleanupMiss(file, MetadataSnapshotMissReason.SCHEMA_MISMATCH)
        }
        if (
            envelope.string("serverId") != namespace.serverId ||
                envelope.string("userId") != namespace.userId
        ) {
            return cleanupMiss(file, MetadataSnapshotMissReason.NAMESPACE_MISMATCH)
        }
        if (envelope.string("logicalKey") != logicalKey) {
            return cleanupMiss(file, MetadataSnapshotMissReason.KEY_MISMATCH)
        }

        val payload = envelope["payload"]
        if (payload == null) {
            return cleanupMiss(file, MetadataSnapshotMissReason.MALFORMED)
        }
        val expectedChecksum = envelope.string("payloadSha256")
        if (expectedChecksum == null || expectedChecksum != sha256(payload.toString())) {
            return cleanupMiss(file, MetadataSnapshotMissReason.CHECKSUM_MISMATCH)
        }
        val kind =
            envelope.string("payloadKind")?.let(MetadataSnapshotPayloadKind::fromWireName)
                ?: return cleanupMiss(file, MetadataSnapshotMissReason.UNSUPPORTED_PAYLOAD)
        val value =
            codec.decode(EncodedMetadataSnapshotPayload(kind, payload))
                ?: return cleanupMiss(file, MetadataSnapshotMissReason.MALFORMED)
        val savedAt =
            envelope.primitive("savedAt")?.longOrNull
                ?: return cleanupMiss(file, MetadataSnapshotMissReason.MALFORMED)
        val now = clock()
        if (savedAt <= 0L || now < savedAt || now - savedAt >= maxAgeMs) {
            return cleanupMiss(file, MetadataSnapshotMissReason.EXPIRED)
        }
        return MetadataSnapshotReadResult.Hit(value = value, savedAtEpochMs = savedAt)
    }

    internal fun fileFor(namespace: MetadataNamespace, logicalKey: String): File? {
        if (!isValidNamespace(namespace) || !isValidKey(logicalKey)) return null
        val namespaceDirectory = File(canonicalStoreRoot, sha256(namespaceIdentity(namespace)))
        val file = File(namespaceDirectory, "${sha256(logicalKey)}.json")
        val canonicalFile = file.canonicalFile
        val rootPrefix = canonicalStoreRoot.canonicalPath + File.separator
        return file.takeIf { canonicalFile.canonicalPath.startsWith(rootPrefix) }
    }

    private fun prune() {
        if (!storeRoot.isDirectory) return
        val candidates =
            storeRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .mapNotNull(::candidate)
                .sortedWith(compareBy<Candidate> { it.savedAt }.thenBy { it.file.path })
                .toList()
        var remainingBytes = candidates.sumOf { it.size }
        var remainingFiles = candidates.size
        candidates.forEach { candidate ->
            if (remainingFiles > maxFiles || remainingBytes > maxBytes) {
                Files.deleteIfExists(candidate.file.toPath())
                remainingFiles -= 1
                remainingBytes -= candidate.size
            }
        }
    }

    private fun candidate(file: File): Candidate? {
        if (file.length() > maxBytes) {
            runCatching { Files.deleteIfExists(file.toPath()) }
            return null
        }
        val savedAt = runCatching {
            envelopeJson
                .parseToJsonElement(file.readText(Charsets.UTF_8))
                .jsonObject
                .primitive("savedAt")
                ?.longOrNull
        }
            .getOrNull()
        if (savedAt == null || savedAt <= 0L) {
            runCatching { Files.deleteIfExists(file.toPath()) }
            return null
        }
        return Candidate(file = file, savedAt = savedAt, size = file.length())
    }

    private fun cleanupMiss(
        file: File,
        reason: MetadataSnapshotMissReason,
    ): MetadataSnapshotReadResult.Miss {
        runCatching { Files.deleteIfExists(file.toPath()) }
        return MetadataSnapshotReadResult.Miss(reason)
    }

    private fun namespaceLock(namespace: MetadataNamespace): Any =
        namespaceLocks.computeIfAbsent(namespaceIdentity(namespace)) { Any() }

    private fun isValidNamespace(namespace: MetadataNamespace): Boolean =
        namespace.serverId.isNotBlank() &&
            namespace.userId.isNotBlank() &&
            !namespace.serverId.contains('\u0000') &&
            !namespace.userId.contains('\u0000')

    private fun isValidKey(logicalKey: String): Boolean =
        logicalKey.isNotBlank() && !logicalKey.contains('\u0000')

    private fun namespaceIdentity(namespace: MetadataNamespace): String =
        "${namespace.serverId.length}:${namespace.serverId}\n" +
            "${namespace.userId.length}:${namespace.userId}"

    private fun JsonObject.string(name: String): String? =
        primitive(name)?.takeIf { it.isString }?.content

    private fun JsonObject.primitive(name: String): JsonPrimitive? = this[name] as? JsonPrimitive

    private data class Candidate(val file: File, val savedAt: Long, val size: Long)

    private companion object {
        const val SCHEMA_VERSION = 1
        const val DEFAULT_MAX_FILES = 512
        const val DEFAULT_MAX_BYTES = 32L * 1024L * 1024L

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
