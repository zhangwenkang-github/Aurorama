package com.zhangwenkang.cinefin.utils

import android.content.Context
import android.util.Base64
import java.io.File
import java.util.UUID
import timber.log.Timber

/** W34 侧车记录：条目的媒体类型 + 音乐专辑 / 艺人。 */
data class DownloadMediaRecord(
    val itemId: String,
    val kind: DownloadMediaKind,
    val albumName: String? = null,
    val artist: String? = null,
    val trackIndex: Int = 0,
)

/**
 * W34：音乐 / 书籍下载的本地元数据侧车（`filesDir/download_media.tsv`）。
 *
 * 音乐曲目在本地库里只按 movies 表存标题，专辑名 / 艺人在离线读库时拿不到；书籍离线文件 （ReaderRepository 的 books 目录）也不经过 sources
 * 表。为了让下载列表在离线 / 重启后 仍能组织层级与封面，这里记录「媒体类型 + 专辑 / 艺人」。
 *
 * 编码：每行五列，用制表符分隔——kind、itemId、base64(album)、base64(artist)、trackIndex。
 */
class DownloadMediaSidecar(context: Context) {
    private val file = File(context.filesDir, "download_media.tsv")

    /** 返回 itemId → 记录（读取失败按空处理，不影响下载列表）。 */
    fun records(): Map<String, DownloadMediaRecord> = runCatching {
        if (!file.isFile) return emptyMap()
        file.readLines().mapNotNull { line -> parseLine(line) }.associateBy { it.itemId }
    }
        .getOrElse {
            Timber.w(it, "读取下载媒体侧车失败，按空处理")
            emptyMap()
        }

    fun put(record: DownloadMediaRecord) {
        update { records -> records + (record.itemId to record) }
    }

    fun remove(itemId: UUID) {
        update { records -> records - itemId.toString() }
    }

    private inline fun update(
        transform: (Map<String, DownloadMediaRecord>) -> Map<String, DownloadMediaRecord>
    ) {
        runCatching {
            val next = transform(records())
            file.parentFile?.mkdirs()
            file.writeText(next.values.joinToString("\n") { encodeLine(it) })
        }
            .onFailure { Timber.w(it, "写入下载媒体侧车失败") }
    }

    private companion object {
        private const val SEPARATOR = "\t"

        private fun encodeLine(record: DownloadMediaRecord): String =
            listOf(
                    record.kind.name,
                    record.itemId,
                    encode(record.albumName),
                    encode(record.artist),
                    record.trackIndex.toString(),
                )
                .joinToString(SEPARATOR)

        private fun parseLine(line: String): DownloadMediaRecord? {
            val parts = line.split(SEPARATOR)
            if (parts.size < 5) return null
            val kind = DownloadMediaKind.entries.firstOrNull { it.name == parts[0] } ?: return null
            if (parts[1].isBlank()) return null
            return DownloadMediaRecord(
                itemId = parts[1],
                kind = kind,
                albumName = decode(parts[2]),
                artist = decode(parts[3]),
                trackIndex = parts[4].toIntOrNull() ?: 0,
            )
        }

        private fun encode(value: String?): String =
            value
                ?.takeIf { it.isNotBlank() }
                ?.let { Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) }
                ?: ""

        private fun decode(value: String): String? =
            value
                .takeIf { it.isNotBlank() }
                ?.let {
                    runCatching {
                        String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8)
                    }
                        .getOrNull()
                }
    }
}
