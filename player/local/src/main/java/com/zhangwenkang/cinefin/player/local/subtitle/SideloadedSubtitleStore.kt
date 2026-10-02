package com.zhangwenkang.cinefin.player.local.subtitle

import com.zhangwenkang.cinefin.language.LanguageMatcher
import java.io.File

/**
 * 一条侧载字幕记录（W27）：用户从系统文件选择器导入的字幕文件。
 *
 * 文件保存在 App 私有目录 `files/player_subtitles/<mediaId>/` 下，按播放条目隔离， 不写服务器；删除 App 数据即一并消失。
 */
data class SideloadedSubtitle(
    val mediaId: String,
    val fileName: String,
    val displayName: String,
    /** 从文件名推断出的语言标签（识别不出来时为空串） */
    val language: String,
    val path: String,
) {
    /** 文件扩展名（小写，不含点） */
    val extension: String
        get() = fileName.substringAfterLast('.', "").lowercase()

    /** 交给 `PlayerSubtitleSource.codec` 的编码名（与 Jellyfin 的字幕编码命名对齐） */
    val codec: String
        get() = codecOf(extension)

    companion object {
        fun codecOf(extension: String): String =
            when (extension.lowercase()) {
                "ass" -> "ass"
                "ssa" -> "ssa"
                "vtt",
                "webvtt" -> "webvtt"
                else -> "subrip"
            }
    }
}

/**
 * 侧载字幕的文件仓库（W27）。
 *
 * 存储布局：`<root>/<mediaId>/<文件名>`。同名文件重复导入时保留两份（`名字-2.srt`）， 不覆盖旧字幕；删除只删除被移除的那一份。
 *
 * 纯文件逻辑（不依赖 Android API），JVM 单测直接覆盖。
 */
class SideloadedSubtitleStore(private val rootDir: File) {

    companion object {
        /** 需求支持 .srt / .ass / .vtt；ssa 与 ass 同族顺手支持 */
        val SUPPORTED_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")

        /**
         * 侧载字幕在 `PlayerSubtitleSource.index` 上的起始值。
         *
         * Jellyfin 的字幕序号是 0..n（一集最多几十条），从 100000 起不会撞车； 面板与移除逻辑据此区分「服务器字幕」与「侧载字幕」。
         */
        const val INDEX_BASE = 100_000
    }

    fun list(mediaId: String): List<SideloadedSubtitle> = runCatching {
        directory(mediaId)
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.length() > 0L }
            .sortedBy { it.name.lowercase() }
            .map { file -> toRecord(mediaId, file) }
    }
        .getOrDefault(emptyList())

    /** 导入一份字幕；扩展名不支持或内容为空时返回 null（调用方据此提示用户） */
    fun import(mediaId: String, displayName: String, bytes: ByteArray): SideloadedSubtitle? {
        if (bytes.isEmpty()) return null
        val safeName = sanitize(displayName)
        val extension = safeName.substringAfterLast('.', "").lowercase()
        if (extension !in SUPPORTED_EXTENSIONS) return null
        val dir = directory(mediaId)
        if (!dir.exists() && !dir.mkdirs()) return null
        var target = File(dir, safeName)
        var sequence = 2
        while (target.exists()) {
            target = File(dir, appendSequence(safeName, sequence++))
        }
        return runCatching {
            target.writeBytes(bytes)
            toRecord(mediaId, target)
        }
            .getOrNull()
    }

    fun remove(record: SideloadedSubtitle): Boolean = runCatching {
        File(record.path).delete()
    }
        .getOrDefault(false)

    private fun directory(mediaId: String): File = File(rootDir, sanitizeSegment(mediaId))

    private fun toRecord(mediaId: String, file: File) =
        SideloadedSubtitle(
            mediaId = mediaId,
            fileName = file.name,
            displayName = file.name,
            language = LanguageMatcher.fromFileName(file.name).orEmpty(),
            path = file.absolutePath,
        )

    private fun sanitize(name: String): String {
        val trimmed = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return trimmed.ifBlank { "subtitle.srt" }
    }

    private fun appendSequence(name: String, sequence: Int): String {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        return if (extension.isBlank()) "$base-$sequence" else "$base-$sequence.$extension"
    }

    private fun sanitizeSegment(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }
}
