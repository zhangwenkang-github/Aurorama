package com.zhangwenkang.cinefin.local

import kotlinx.serialization.Serializable

/**
 * 本地库视频的同目录外挂字幕（VIDEO-48）：扫描期推断，随索引一起持久化。
 *
 * 只用 SAF 文档 URI：字幕文件与视频在同一棵已授权的树里，树授权已覆盖读权限， 因此**不单独** takePersistableUriPermission。
 */
@Serializable
data class LocalSubtitleFile(
    /** SAF 文档 URI。 */
    val uri: String,
    /** 文件名（含扩展名），用于面板显示与稳定排序。 */
    val fileName: String,
    /** 主名之后的语言 / 标签段：`movie.zh.srt` 对 `movie.mkv` → `zh`；同名无后缀为空串。 */
    val tag: String,
    /** 字幕编码名（与 Jellyfin 流口径一致）：subrip / ass / webvtt。 */
    val codec: String,
)

/** 扫描期的一个同目录候选文件（相对路径用于剔除子目录里的同名文件）。 */
data class LocalSubtitleCandidate(
    val name: String,
    /** 相对文件夹根的路径，如 `电影/字幕/demo.srt`。 */
    val relativePath: String,
    /** SAF 文档 URI。 */
    val uri: String,
)

/**
 * 同目录外挂字幕匹配规则（VIDEO-48，纯函数）。
 *
 * 命中条件：
 * - 扩展名 ∈ .srt / .ass / .ssa / .vtt（大小写不敏感）；
 * - 父目录与视频相同（子目录里的同名文件**不**匹配）；
 * - 字幕主名与视频主名**完全相等**，或 = 视频主名 + `.` + 一段非空语言 / 标签后缀（`movie.zh.srt`、`movie.chs.ass`）。
 */
object LocalSubtitleRules {
    val EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")

    fun isSubtitleFile(fileName: String): Boolean =
        LocalMediaExtensions.extensionOf(fileName) in EXTENSIONS

    /**
     * 视频主名与字幕主名的匹配结果：返回标签后缀（同名返回空串），不匹配返回 null。
     *
     * 主名比较区分大小写（「完全相等」），扩展名大小写不敏感。
     */
    fun tagFor(
        videoName: String,
        subtitleName: String,
    ): String? {
        if (!isSubtitleFile(subtitleName)) return null
        val videoStem = videoName.substringBeforeLast('.', videoName)
        val subtitleStem = subtitleName.substringBeforeLast('.', subtitleName)
        val prefix = "$videoStem."
        return when {
            subtitleStem == videoStem -> ""
            subtitleStem.length > prefix.length && subtitleStem.startsWith(prefix) ->
                subtitleStem.removePrefix(prefix)
            else -> null
        }
    }

    /** 扫描期的同目录匹配：只保留父目录相同的候选，按文件名排序（结果稳定）。 */
    fun match(
        videoName: String,
        videoRelativePath: String,
        candidates: List<LocalSubtitleCandidate>,
    ): List<LocalSubtitleFile> {
        val parent = videoRelativePath.substringBeforeLast('/', "")
        return candidates
            .filter { candidate -> candidate.relativePath.substringBeforeLast('/', "") == parent }
            .mapNotNull { candidate ->
                val tag = tagFor(videoName, candidate.name) ?: return@mapNotNull null
                LocalSubtitleFile(
                    uri = candidate.uri,
                    fileName = candidate.name,
                    tag = tag,
                    codec = codecFor(candidate.name),
                )
            }
            .sortedBy { it.fileName }
    }

    /** 字幕编码名：与 `PlaylistManager` / `SubtitleParser` 认的 codec 口径一致。 */
    fun codecFor(fileName: String): String =
        when (LocalMediaExtensions.extensionOf(fileName)) {
            "srt" -> "subrip"
            "vtt" -> "webvtt"
            "ass",
            "ssa" -> "ass"
            else -> ""
        }
}
