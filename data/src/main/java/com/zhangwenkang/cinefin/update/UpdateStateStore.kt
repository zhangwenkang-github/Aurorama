package com.zhangwenkang.cinefin.update

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val UPDATE_STATE_SCHEMA_VERSION = 1
private val UPDATE_ID_PATTERN = Regex("^[0-9a-f]{64}$")

@Serializable
data class UpdateReleaseIdentity(
    val releaseTag: String,
    val version: String,
    val assetName: String,
    val assetSize: Long,
    val assetDigest: String?,
    val downloadUrl: String,
) {
    init {
        require(AppVersion.parseReleaseTag(releaseTag)?.versionName == version)
        require(
            assetName == releaseAssetName(version, AssetArchitecture.ARM64) ||
                assetName == releaseAssetName(version, AssetArchitecture.UNIVERSAL)
        )
        require(assetSize > 0)
        require(assetDigest == null || assetDigest.isNotBlank())
        require(downloadUrl == releaseAssetUrl(version, assetName))
    }
}

enum class UpdateTransferPhase {
    DOWNLOADING,
    READY_FOR_VERIFICATION,
    FAILED,
    CANCELLED,
}

enum class UpdateFailureCode {
    NETWORK_ERROR,
    HTTP_SERVER_ERROR,
    RATE_LIMITED,
    STORAGE_ERROR,
    INVALID_RESPONSE,
    RANGE_NOT_SATISFIABLE,
    STATE_ERROR,
}

@Serializable
data class StoredReleaseState(
    val schemaVersion: Int,
    val releaseTag: String,
    val version: String,
    val assetName: String,
    val assetSize: Long,
    val assetDigest: String?,
    val downloadUrl: String,
    val discoveredAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val releaseNotes: String? = null,
) {
    init {
        require(schemaVersion == UPDATE_STATE_SCHEMA_VERSION)
        require(discoveredAtEpochMs >= 0)
        require(updatedAtEpochMs >= discoveredAtEpochMs)
        release
    }

    val release: UpdateReleaseIdentity
        get() =
            UpdateReleaseIdentity(
                releaseTag = releaseTag,
                version = version,
                assetName = assetName,
                assetSize = assetSize,
                assetDigest = assetDigest,
                downloadUrl = downloadUrl,
            )
}

@Serializable
data class UpdateTransferState(
    val schemaVersion: Int,
    val updateId: String,
    val releaseTag: String,
    val version: String,
    val assetName: String,
    val assetSize: Long,
    val assetDigest: String?,
    val downloadUrl: String,
    val phase: UpdateTransferPhase,
    val etag: String?,
    val lastModified: String?,
    val startedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val failureCode: UpdateFailureCode?,
) {
    init {
        require(schemaVersion == UPDATE_STATE_SCHEMA_VERSION)
        require(UPDATE_ID_PATTERN.matches(updateId))
        require(stableUpdateId(release) == updateId)
        require(etag == null || etag.isNotBlank())
        require(lastModified == null || lastModified.isNotBlank())
        require(startedAtEpochMs >= 0)
        require(updatedAtEpochMs >= startedAtEpochMs)
        require((phase == UpdateTransferPhase.FAILED) == (failureCode != null))
    }

    val release: UpdateReleaseIdentity
        get() =
            UpdateReleaseIdentity(
                releaseTag = releaseTag,
                version = version,
                assetName = assetName,
                assetSize = assetSize,
                assetDigest = assetDigest,
                downloadUrl = downloadUrl,
            )
}

sealed interface UpdateStateReadResult<out T> {
    data class Present<T>(val value: T) : UpdateStateReadResult<T>

    data object Absent : UpdateStateReadResult<Nothing>

    data class Invalid(val reason: String) : UpdateStateReadResult<Nothing>
}

sealed interface UpdateStateWriteResult {
    data object Success : UpdateStateWriteResult

    data class Failure(val reason: String) : UpdateStateWriteResult
}

sealed interface TransferFileInspection {
    data class ReadyToResume(val offset: Long) : TransferFileInspection

    data class ResetRequired(val reason: String) : TransferFileInspection

    data class Invalid(val reason: String) : TransferFileInspection

    data object AlreadyComplete : TransferFileInspection
}

fun stableUpdateId(release: UpdateReleaseIdentity): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val canonical =
        listOf(
                release.releaseTag,
                release.version,
                release.assetName,
                release.assetSize.toString(),
                release.assetDigest ?: "<absent>",
                release.downloadUrl,
            )
            .joinToString(separator = "|") { "${it.toByteArray(StandardCharsets.UTF_8).size}:$it" }
    return digest.digest(canonical.toByteArray(StandardCharsets.UTF_8)).toHex()
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

class UpdateStateStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
    }

    fun saveDiscoveredRelease(
        release: UpdateReleaseIdentity,
        nowEpochMs: Long = clock(),
        releaseNotes: String? = null,
    ): UpdateStateWriteResult {
        val existing = loadDiscoveredRelease()
        if (existing is UpdateStateReadResult.Invalid) {
            return UpdateStateWriteResult.Failure(existing.reason)
        }
        val previous = (existing as? UpdateStateReadResult.Present)?.value
        val sameRelease = previous?.release == release
        val notes =
            releaseNotes?.takeIf(String::isNotBlank)
                // 同一 release 再次发现但本次没带回说明时，保留上次已存下的说明。
                ?: previous?.releaseNotes?.takeIf { sameRelease }
        val discoveredAt =
            if (sameRelease) {
                previous.discoveredAtEpochMs
            } else {
                nowEpochMs
            }
        val updatedAt =
            if (sameRelease) {
                maxOf(previous.updatedAtEpochMs, nowEpochMs)
            } else {
                nowEpochMs
            }
        return writeState(
            fileName = RELEASE_STATE_FILE,
            value =
                StoredReleaseState(
                    schemaVersion = UPDATE_STATE_SCHEMA_VERSION,
                    releaseTag = release.releaseTag,
                    version = release.version,
                    assetName = release.assetName,
                    assetSize = release.assetSize,
                    assetDigest = release.assetDigest,
                    downloadUrl = release.downloadUrl,
                    discoveredAtEpochMs = discoveredAt,
                    updatedAtEpochMs = updatedAt,
                    releaseNotes = notes,
                ),
        )
    }

    fun loadDiscoveredRelease(): UpdateStateReadResult<StoredReleaseState> =
        readState(RELEASE_STATE_FILE)

    @Synchronized
    fun beginDownload(
        release: UpdateReleaseIdentity,
        nowEpochMs: Long = clock(),
    ): UpdateStateWriteResult {
        val existing = loadTransfer()
        val newUpdateId = stableUpdateId(release)
        val previous =
            when (existing) {
                is UpdateStateReadResult.Present -> existing.value
                is UpdateStateReadResult.Invalid -> {
                    // 损坏 / 被清理的 transfer 记录（完整包已删除、旧 schema、长度不符）必须丢弃：
                    // 否则 download() 会永久返回 STATE_ERROR，「下载更新」点击后永不发网。
                    if (!discardCorruptTransferState(newUpdateId, release.assetSize)) {
                        return UpdateStateWriteResult.Failure(
                            "cannot discard corrupt transfer state"
                        )
                    }
                    null
                }
                UpdateStateReadResult.Absent -> null
            }
        val transfer =
            if (previous != null && previous.release == release) {
                previous.copy(
                    phase =
                        if (isCompleteFileValid(previous)) {
                            UpdateTransferPhase.READY_FOR_VERIFICATION
                        } else {
                            UpdateTransferPhase.DOWNLOADING
                        },
                    updatedAtEpochMs = maxOf(previous.updatedAtEpochMs, nowEpochMs),
                    failureCode = null,
                )
            } else {
                newTransfer(release, newUpdateId, nowEpochMs)
            }

        if (previous?.updateId != newUpdateId) {
            runCatching { Files.deleteIfExists(partFile(newUpdateId).toPath()) }
                .getOrElse {
                    return UpdateStateWriteResult.Failure("cannot reset orphan part: ${it.message}")
                }
        }

        if (!isCompleteFileValid(transfer)) {
            val part = partFile(transfer)
            if (part.isFile && part.length() > release.assetSize) {
                runCatching { resetPart(transfer) }
                    .getOrElse {
                        return UpdateStateWriteResult.Failure(
                            "cannot reset oversized part: ${it.message}"
                        )
                    }
            } else if (
                previous?.phase == UpdateTransferPhase.FAILED &&
                    part.isFile &&
                    part.length() == release.assetSize
            ) {
                runCatching { resetPart(transfer) }
                    .getOrElse {
                        return UpdateStateWriteResult.Failure(
                            "cannot reset failed full-sized part: ${it.message}"
                        )
                    }
            }
        }
        return writeTransfer(transfer)
    }

    @Synchronized
    fun loadTransfer(): UpdateStateReadResult<UpdateTransferState> {
        val loaded = readState<UpdateTransferState>(TRANSFER_STATE_FILE)
        if (loaded !is UpdateStateReadResult.Present) {
            return loaded
        }
        val transfer = loaded.value
        val complete = completeFile(transfer)
        if (complete.exists()) {
            if (!complete.isFile || complete.length() != transfer.assetSize) {
                return UpdateStateReadResult.Invalid("complete file size/identity mismatch")
            }
            if (transfer.phase != UpdateTransferPhase.READY_FOR_VERIFICATION) {
                val recovered =
                    transfer.copy(
                        phase = UpdateTransferPhase.READY_FOR_VERIFICATION,
                        updatedAtEpochMs = maxOf(transfer.updatedAtEpochMs, clock()),
                        failureCode = null,
                    )
                return when (val write = writeTransfer(recovered)) {
                    UpdateStateWriteResult.Success -> UpdateStateReadResult.Present(recovered)
                    is UpdateStateWriteResult.Failure -> UpdateStateReadResult.Invalid(write.reason)
                }
            }
        } else if (transfer.phase == UpdateTransferPhase.READY_FOR_VERIFICATION) {
            return UpdateStateReadResult.Invalid("ready state has no complete file")
        }
        return UpdateStateReadResult.Present(transfer)
    }

    @Synchronized
    fun cancelDownload(
        updateId: String,
        nowEpochMs: Long = clock(),
    ): UpdateStateWriteResult {
        val transfer =
            when (val loaded = loadTransfer()) {
                is UpdateStateReadResult.Present -> loaded.value
                is UpdateStateReadResult.Invalid,
                UpdateStateReadResult.Absent -> null
            } ?: return UpdateStateWriteResult.Failure("download transfer is absent or invalid")
        if (transfer.updateId != updateId) {
            return UpdateStateWriteResult.Failure("download identity mismatch")
        }
        if (transfer.phase != UpdateTransferPhase.DOWNLOADING) {
            return UpdateStateWriteResult.Failure("download is not active")
        }
        return writeTransfer(
            transfer.copy(
                phase = UpdateTransferPhase.CANCELLED,
                updatedAtEpochMs = maxOf(transfer.updatedAtEpochMs, nowEpochMs),
                failureCode = null,
            )
        )
    }

    fun inspectTransferFiles(transfer: UpdateTransferState): TransferFileInspection {
        val complete = completeFile(transfer)
        if (complete.exists()) {
            return if (complete.isFile && complete.length() == transfer.assetSize) {
                TransferFileInspection.AlreadyComplete
            } else {
                TransferFileInspection.Invalid("complete file size mismatch")
            }
        }
        if (transfer.phase == UpdateTransferPhase.READY_FOR_VERIFICATION) {
            return TransferFileInspection.Invalid("ready state has no complete file")
        }
        val part = partFile(transfer)
        if (!part.exists()) {
            return TransferFileInspection.ReadyToResume(offset = 0)
        }
        if (!part.isFile) {
            return TransferFileInspection.Invalid("part path is not a file")
        }
        return if (part.length() > transfer.assetSize) {
            TransferFileInspection.ResetRequired("part length exceeds expected asset size")
        } else {
            TransferFileInspection.ReadyToResume(offset = part.length())
        }
    }

    fun partFile(transfer: UpdateTransferState): File = partFile(transfer.updateId)

    fun completeFile(transfer: UpdateTransferState): File = completeFile(transfer.updateId)

    @Synchronized
    fun recordValidators(
        transfer: UpdateTransferState,
        etag: String?,
        lastModified: String?,
        nowEpochMs: Long = clock(),
    ): UpdateStateWriteResult {
        activeTransferFailure(transfer)?.let {
            return it
        }
        return writeTransfer(
            transfer.copy(
                phase = UpdateTransferPhase.DOWNLOADING,
                etag = etag?.takeIf(String::isNotBlank),
                lastModified = lastModified?.takeIf(String::isNotBlank),
                updatedAtEpochMs = maxOf(transfer.updatedAtEpochMs, nowEpochMs),
                failureCode = null,
            )
        )
    }

    @Synchronized
    fun markFailed(
        transfer: UpdateTransferState,
        failureCode: UpdateFailureCode,
        nowEpochMs: Long = clock(),
    ): UpdateStateWriteResult {
        activeTransferFailure(transfer)?.let {
            return it
        }
        return writeTransfer(
            transfer.copy(
                phase = UpdateTransferPhase.FAILED,
                updatedAtEpochMs = maxOf(transfer.updatedAtEpochMs, nowEpochMs),
                failureCode = failureCode,
            )
        )
    }

    @Synchronized
    fun markReady(
        transfer: UpdateTransferState,
        nowEpochMs: Long = clock(),
    ): UpdateStateWriteResult {
        activeTransferFailure(transfer)?.let {
            return it
        }
        val complete = completeFile(transfer)
        if (!complete.isFile || complete.length() != transfer.assetSize) {
            return UpdateStateWriteResult.Failure("complete file size mismatch")
        }
        return writeTransfer(
            transfer.copy(
                phase = UpdateTransferPhase.READY_FOR_VERIFICATION,
                updatedAtEpochMs = maxOf(transfer.updatedAtEpochMs, nowEpochMs),
                failureCode = null,
            )
        )
    }

    @Synchronized
    fun markVerificationFailed(
        transfer: UpdateTransferState,
        nowEpochMs: Long = clock(),
    ): UpdateStateWriteResult {
        return when (val current = readState<UpdateTransferState>(TRANSFER_STATE_FILE)) {
            UpdateStateReadResult.Absent ->
                UpdateStateWriteResult.Failure("transfer state is absent")
            is UpdateStateReadResult.Invalid -> UpdateStateWriteResult.Failure(current.reason)
            is UpdateStateReadResult.Present -> {
                if (
                    current.value.updateId != transfer.updateId ||
                        current.value.phase != UpdateTransferPhase.READY_FOR_VERIFICATION
                ) {
                    UpdateStateWriteResult.Failure("transfer is not ready for verification")
                } else {
                    writeTransfer(
                        current.value.copy(
                            phase = UpdateTransferPhase.FAILED,
                            updatedAtEpochMs = maxOf(current.value.updatedAtEpochMs, nowEpochMs),
                            failureCode = UpdateFailureCode.INVALID_RESPONSE,
                        )
                    )
                }
            }
        }
    }

    fun resetPart(transfer: UpdateTransferState) {
        FileOutputStream(partFile(transfer), false).use { output -> output.fd.sync() }
    }

    @Synchronized
    fun finalizePart(
        transfer: UpdateTransferState,
        nowEpochMs: Long = clock(),
    ): UpdateStateWriteResult {
        activeTransferFailure(transfer)?.let {
            return it
        }
        val part = partFile(transfer)
        if (!part.isFile || part.length() != transfer.assetSize) {
            return UpdateStateWriteResult.Failure("part size mismatch")
        }
        return runCatching {
                FileOutputStream(part, true).use { output -> output.fd.sync() }
                atomicMove(part, completeFile(transfer))
                fsyncDirectory()
            }
            .fold(
                onSuccess = { markReady(transfer, nowEpochMs) },
                onFailure = {
                    UpdateStateWriteResult.Failure("cannot finalize part: ${it.message}")
                },
            )
    }

    private fun newTransfer(
        release: UpdateReleaseIdentity,
        updateId: String,
        nowEpochMs: Long,
    ): UpdateTransferState =
        UpdateTransferState(
            schemaVersion = UPDATE_STATE_SCHEMA_VERSION,
            updateId = updateId,
            releaseTag = release.releaseTag,
            version = release.version,
            assetName = release.assetName,
            assetSize = release.assetSize,
            assetDigest = release.assetDigest,
            downloadUrl = release.downloadUrl,
            phase = UpdateTransferPhase.DOWNLOADING,
            etag = null,
            lastModified = null,
            startedAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
            failureCode = null,
        )

    private fun isCompleteFileValid(transfer: UpdateTransferState): Boolean {
        val complete = completeFile(transfer)
        return complete.isFile && complete.length() == transfer.assetSize
    }

    private fun partFile(updateId: String): File = directory.resolve("$updateId.apk.part")

    /**
     * 丢弃无法解析的 transfer 记录，以及与之同名但长度不符的旧完整包。
     *
     * 返回 false 表示清理失败（调用方必须继续失败，不能假装可下载）；true 表示状态已清空、可按 release 重建。
     */
    private fun discardCorruptTransferState(
        newUpdateId: String,
        expectedSize: Long,
    ): Boolean = runCatching {
        Files.deleteIfExists(directory.resolve(TRANSFER_STATE_FILE).toPath())
        val complete = completeFile(newUpdateId)
        if (complete.exists() && (!complete.isFile || complete.length() != expectedSize)) {
            Files.deleteIfExists(complete.toPath())
        }
    }
        .isSuccess

    private fun completeFile(updateId: String): File = directory.resolve("$updateId.apk")

    private inline fun <reified T> readState(fileName: String): UpdateStateReadResult<T> {
        val file = directory.resolve(fileName)
        if (!file.exists()) {
            return UpdateStateReadResult.Absent
        }
        if (!file.isFile) {
            return UpdateStateReadResult.Invalid("$fileName is not a file")
        }
        return try {
            UpdateStateReadResult.Present(
                json.decodeFromString<T>(file.readText(StandardCharsets.UTF_8))
            )
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            UpdateStateReadResult.Invalid("invalid $fileName: ${exception.message}")
        }
    }

    private inline fun <reified T> writeState(
        fileName: String,
        value: T,
    ): UpdateStateWriteResult = writeAtomic(fileName, json.encodeToString(value))

    private fun writeTransfer(transfer: UpdateTransferState): UpdateStateWriteResult =
        writeAtomic(TRANSFER_STATE_FILE, json.encodeToString(transfer))

    private fun activeTransferFailure(
        transfer: UpdateTransferState
    ): UpdateStateWriteResult.Failure? =
        when (val current = readState<UpdateTransferState>(TRANSFER_STATE_FILE)) {
            UpdateStateReadResult.Absent ->
                UpdateStateWriteResult.Failure("transfer state is absent")
            is UpdateStateReadResult.Invalid -> UpdateStateWriteResult.Failure(current.reason)
            is UpdateStateReadResult.Present ->
                when {
                    current.value.updateId != transfer.updateId ->
                        UpdateStateWriteResult.Failure("download identity mismatch")
                    current.value.phase != UpdateTransferPhase.DOWNLOADING ->
                        UpdateStateWriteResult.Failure("download is not active")
                    else -> null
                }
        }

    private fun writeAtomic(
        fileName: String,
        content: String,
    ): UpdateStateWriteResult = runCatching {
        Files.createDirectories(directory.toPath())
        val temporary = directory.resolve("$fileName.tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(content.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        atomicMove(temporary, directory.resolve(fileName))
        fsyncDirectory()
    }
        .fold(
            onSuccess = { UpdateStateWriteResult.Success },
            onFailure = { UpdateStateWriteResult.Failure("cannot write $fileName: ${it.message}") },
        )

    private fun atomicMove(
        source: File,
        target: File,
    ) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (exception: AtomicMoveNotSupportedException) {
            throw IllegalStateException("atomic replacement is not supported", exception)
        }
    }

    private fun fsyncDirectory() {
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        }
    }

    private companion object {
        const val RELEASE_STATE_FILE = "release.json"
        const val TRANSFER_STATE_FILE = "transfer.json"
    }
}
