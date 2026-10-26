package com.zhangwenkang.cinefin.utils

import android.content.Context
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import timber.log.Timber

/** W34 下载层级：条目所属媒体类型（下载列表层级化用）。 */
enum class DownloadMediaKind {
    VIDEO,
    MUSIC,
    BOOK,
}

/** W88-B：下载创建时持久化的可证明来源；媒体源适用于视频 / 音乐。 */
data class DownloadProvenance(
    val serverId: String,
    val userId: UUID,
    val itemId: UUID,
    val mediaSourceId: String?,
)

/** W34 / W88-B 侧车记录：媒体元数据 + 下载发起时的完整来源。 */
data class DownloadMediaRecord(
    val itemId: String,
    val kind: DownloadMediaKind,
    val albumName: String? = null,
    val artist: String? = null,
    val trackIndex: Int = 0,
    val serverId: String? = null,
    val userId: String? = null,
    val sourceId: String? = null,
    val displayName: String? = null,
) {
    /** 视频 / 音乐必须有媒体 sourceId；书籍没有媒体源，前三段完整即可。 旧五列记录或任一关键段无法解析时返回 null，调用方必须按来源未知处理。 */
    fun provenance(): DownloadProvenance? {
        val parsedItemId = itemId.toUuidOrNull() ?: return null
        val parsedUserId = userId?.toUuidOrNull() ?: return null
        val resolvedServerId = serverId?.takeIf { it.isNotBlank() } ?: return null
        val resolvedSourceId = sourceId?.takeIf { it.isNotBlank() }
        if (kind != DownloadMediaKind.BOOK && resolvedSourceId == null) return null
        return DownloadProvenance(
            serverId = resolvedServerId,
            userId = parsedUserId,
            itemId = parsedItemId,
            mediaSourceId = resolvedSourceId,
        )
    }
}

/**
 * W34：音乐 / 书籍下载的本地元数据侧车（`filesDir/download_media.tsv`）。
 *
 * 音乐曲目在本地库里只按 movies 表存标题，专辑名 / 艺人在离线读库时拿不到；书籍离线文件 （ReaderRepository 的 books 目录）也不经过 sources
 * 表。为了让下载列表在离线 / 重启后 仍能组织层级与封面，这里记录「媒体类型 + 专辑 / 艺人」。
 *
 * 编码：
 * - v2：`v2, kind, base64(itemId), base64(album), base64(artist), trackIndex, base64(serverId),
 *   base64(userId), base64(sourceId), base64(displayName)`；
 * - 旧五列记录继续可读，但不猜测 server / user / source，显式降级为来源未知。
 *
 * 每次写入均先落同目录临时文件再替换目标文件；[lock] 串行化同一进程内调用，避免并发 `put / remove` 互相覆盖。
 */
class DownloadMediaSidecar private constructor(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "download_media.tsv"))

    /** 按完整 provenance canonical key 建索引；同 itemId 的不同来源保留为不同项。 */
    fun records(): Map<String, DownloadMediaRecord> =
        allRecords().associateBy { it.canonicalIdentity().canonicalKey }

    /** 全部记录（保留文件顺序；同一 itemId 的不同来源不被 map 压扁）。 */
    fun allRecords(): List<DownloadMediaRecord> =
        synchronized(lock) {
            runCatching {
                if (!file.isFile) return@synchronized emptyList()
                file.readLines().mapNotNull(DownloadMediaCodec::decodeLine)
            }
                .getOrElse {
                    Timber.w(it, "读取下载媒体侧车失败，按空处理")
                    emptyList()
                }
        }

    fun put(record: DownloadMediaRecord): Boolean = updateResult { records ->
        upsert(records, record)
    }

    /** 原子写入整批接受记录；返回 false 时调用方不得报告 accepted。 */
    fun putAll(records: List<DownloadMediaRecord>): Boolean = updateResult { existing ->
        records.fold(existing) { current, record -> upsert(current, record) }
    }

    /**
     * Room source 插入失败后的补偿：把已冻结 sourceId 的记录原子替换回同一 namespace 的 pending。
     *
     * 只移除本次替换对应的 sourceId；同条目的其他已解析来源保持不变。
     */
    fun replaceResolvedWithPending(
        pending: DownloadMediaRecord,
        failedSourceId: String,
    ): Boolean {
        require(pending.sourceId.isNullOrBlank())
        require(failedSourceId.isNotBlank())
        return updateResult { records ->
            records.filterNot { existing ->
                existing.itemId == pending.itemId &&
                    existing.kind == pending.kind &&
                    existing.serverId == pending.serverId &&
                    existing.userId == pending.userId &&
                    existing.sourceId == failedSourceId
            } + pending
        }
    }

    private fun upsert(
        records: List<DownloadMediaRecord>,
        record: DownloadMediaRecord,
    ): List<DownloadMediaRecord> {
        val identity = record.canonicalIdentity()
        if (!identity.hasCompleteNamespace) {
            // 来源未知无法证明两行相同，只能显式保留为独立行。
            if (record.sourceId.isNullOrBlank()) return records + record
            val pendingIndex = records.indexOfFirst { existing ->
                existing.canonicalIdentity().hasCompleteNamespace.not() &&
                    existing.itemId == record.itemId &&
                    existing.kind == record.kind &&
                    existing.albumName == record.albumName &&
                    existing.artist == record.artist &&
                    existing.trackIndex == record.trackIndex &&
                    existing.serverId == record.serverId &&
                    existing.userId == record.userId &&
                    existing.sourceId.isNullOrBlank() &&
                    existing.displayName == record.displayName
            }
            return if (pendingIndex >= 0) {
                records.toMutableList().also { it[pendingIndex] = record }
            } else {
                records + record
            }
        }
        val withoutResolvedPlaceholder =
            if (record.sourceId.isNullOrBlank()) {
                records
            } else {
                records.filterNot { existing ->
                    existing.itemId == record.itemId &&
                        existing.serverId == record.serverId &&
                        existing.userId == record.userId &&
                        existing.sourceId.isNullOrBlank()
                }
            }
        val key = record.storageKey()
        val existingIndex = withoutResolvedPlaceholder.indexOfFirst { it.storageKey() == key }
        return if (existingIndex >= 0) {
            withoutResolvedPlaceholder.toMutableList().also { it[existingIndex] = record }
        } else {
            withoutResolvedPlaceholder + record
        }
    }

    fun remove(identity: DownloadCanonicalIdentity) {
        update { records -> records.filterNot { it.canonicalIdentity() == identity } }
    }

    /** 书籍只有一个私有 `.book` 文件；成功重下时替换该 item 的旧 BOOK 来源。 */
    fun replaceBook(record: DownloadMediaRecord) {
        require(record.kind == DownloadMediaKind.BOOK)
        update { records ->
            records.filterNot { it.kind == DownloadMediaKind.BOOK && it.itemId == record.itemId } +
                record
        }
    }

    fun removeBook(itemId: UUID) {
        update { records ->
            records.filterNot {
                it.kind == DownloadMediaKind.BOOK && it.itemId == itemId.toString()
            }
        }
    }

    private fun update(transform: (List<DownloadMediaRecord>) -> List<DownloadMediaRecord>) {
        updateResult(transform)
    }

    private fun updateResult(
        transform: (List<DownloadMediaRecord>) -> List<DownloadMediaRecord>
    ): Boolean =
        synchronized(lock) {
            runCatching {
                    val next = transform(allRecords())
                    file.parentFile?.mkdirs()
                    val temp = File(file.parentFile, "${file.name}.tmp")
                    temp.writeText(
                        next.joinToString("\n", transform = DownloadMediaCodec::encodeLine)
                    )
                    try {
                        Files.move(
                            temp.toPath(),
                            file.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(
                            temp.toPath(),
                            file.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                    true
                }
                .onFailure { error ->
                    File(file.parentFile, "${file.name}.tmp").delete()
                    Timber.w(error, "写入下载媒体侧车失败")
                }
                .getOrDefault(false)
        }

    internal companion object {
        /** 同一进程可能有下载器 / 离线仓库等多个实例，以全局对象串行化同一侧车文件。 */
        val lock = Any()

        /** JVM 测试接缝；生产方只通过 Context 构造。 */
        internal fun forFile(file: File): DownloadMediaSidecar = DownloadMediaSidecar(file)
    }
}

object DownloadMediaCodec {
    private const val SEPARATOR = "\t"
    private const val VERSION = "v2"
    private const val V2_COLUMN_COUNT = 10

    fun encodeLine(record: DownloadMediaRecord): String =
        listOf(
                VERSION,
                record.kind.name,
                encode(record.itemId),
                encode(record.albumName),
                encode(record.artist),
                record.trackIndex.toString(),
                encode(record.serverId),
                encode(record.userId),
                encode(record.sourceId),
                encode(record.displayName),
            )
            .joinToString(SEPARATOR)

    fun decodeLine(line: String): DownloadMediaRecord? {
        if (line.isBlank()) return null
        val parts = line.split(SEPARATOR)
        return if (parts.firstOrNull() == VERSION) decodeV2(parts) else decodeLegacy(parts)
    }

    private fun decodeV2(parts: List<String>): DownloadMediaRecord? {
        if (parts.size != V2_COLUMN_COUNT) return null
        val kind = parts[1].toKindOrNull() ?: return null
        val itemId = decode(parts[2])?.takeIf { it.isNotBlank() } ?: return null
        return DownloadMediaRecord(
            itemId = itemId,
            kind = kind,
            albumName = decode(parts[3]),
            artist = decode(parts[4]),
            trackIndex = parts[5].toIntOrNull() ?: return null,
            serverId = decode(parts[6]),
            userId = decode(parts[7]),
            sourceId = decode(parts[8]),
            displayName = decode(parts[9]),
        )
    }

    private fun decodeLegacy(parts: List<String>): DownloadMediaRecord? {
        if (parts.size != 5) return null
        val kind = parts[0].toKindOrNull() ?: return null
        if (parts[1].isBlank()) return null
        return DownloadMediaRecord(
            itemId = parts[1],
            kind = kind,
            albumName = decodeLegacyValue(parts[2]),
            artist = decodeLegacyValue(parts[3]),
            trackIndex = parts[4].toIntOrNull() ?: 0,
        )
    }

    private fun String.toKindOrNull(): DownloadMediaKind? =
        DownloadMediaKind.entries.firstOrNull { it.name == this }

    private fun encode(value: String?): String =
        value
            ?.takeIf { it.isNotBlank() }
            ?.toByteArray(Charsets.UTF_8)
            ?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) } ?: ""

    private fun decode(value: String): String? =
        value
            .takeIf { it.isNotBlank() }
            ?.let {
                runCatching {
                    Base64.getUrlDecoder().decode(it).toString(Charsets.UTF_8)
                }
                    .getOrNull()
            }

    /** v1 使用 Android Base64（可含 `+` / `/` / `=`）；v2 使用 URL-safe 无 padding。 */
    private fun decodeLegacyValue(value: String): String? =
        value
            .takeIf { it.isNotBlank() }
            ?.let {
                runCatching {
                    Base64.getMimeDecoder().decode(it).toString(Charsets.UTF_8)
                }
                    .getOrNull()
            }
}

fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

private fun DownloadMediaRecord.storageKey(): String = canonicalIdentity().canonicalKey
