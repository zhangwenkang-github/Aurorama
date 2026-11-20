package com.zhangwenkang.cinefin.player.local.mpv

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import timber.log.Timber

/**
 * W5-T10 L7：mpv 的 `sub-add` 打不开 SAF `content://` 外挂字幕。
 *
 * libmpv 走 libc 打开路径，不认 Android 的内容 URI；本地媒体库的外挂字幕又刻意留在用户目录（不拷贝，VIDEO-48）， 于是把 `content://` 交给 mpv
 * 必然失败。这里在 `sub-add` 前复制一份带原扩展名的临时副本到 `cacheDir/mpv-subtitles/`（与 W27 侧载字幕「复制进 App
 * 私有目录」同一取法），`file` 路径 mpv 可以直接打开。
 *
 * 复制失败返回 `null`：这条字幕放弃，不影响整条播放（mpv 侧本来就还有内嵌轨兜底）。
 */
internal class MpvContentSubtitleResolver(private val context: Context) {
    private val copies = mutableSetOf<File>()

    /** `content://` → 临时副本绝对路径；其余（http / file）原样返回。 */
    fun resolve(uriString: String): String? {
        if (!uriString.startsWith("content:", ignoreCase = true)) return uriString
        return runCatching { copyToCache(Uri.parse(uriString)).absolutePath }
            .onFailure { Timber.w(it, "mpv content:// 字幕转临时副本失败") }
            .getOrNull()
    }

    /** 播放器释放时清掉自己创建的副本（cacheDir 只放本次会话的字幕快照）。 */
    fun clear() {
        copies.forEach { file -> runCatching { file.delete() } }
        copies.clear()
    }

    private fun copyToCache(uri: Uri): File {
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("mpv 字幕临时目录不可写：${dir.absolutePath}")
        }
        val target = File(dir, mpvLocalSubtitleCopyName(uri.toString(), displayName(uri)))
        val partial = File(dir, "${target.name}.part")
        val input =
            context.contentResolver.openInputStream(uri)
                ?: throw IOException("SAF 字幕打不开：授权失效或文件已移走")
        input.use { source -> partial.outputStream().use { sink -> source.copyTo(sink) } }
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("mpv 字幕临时副本落盘失败：${target.absolutePath}")
        }
        copies += target
        return target
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }

    private companion object {
        const val CACHE_DIR_NAME = "mpv-subtitles"
    }
}

/** 临时副本的文件名：保留原扩展名（mpv 靠它兜底识别字幕格式），前缀带上 URI 的稳定散列避免不同目录同名互撞。 */
internal fun mpvLocalSubtitleCopyName(uri: String, displayName: String?): String {
    val raw =
        displayName?.takeIf { it.isNotBlank() }
            ?: uri.substringAfterLast('/').takeIf { it.isNotBlank() && !it.contains('?') }
            ?: "subtitle.srt"
    val safe = raw.replace(UNSAFE_FILE_NAME_CHARS, "_").take(MAX_NAME_LENGTH)
    val suffix = uri.hashCode().toUInt().toString(16)
    return "$suffix-$safe"
}

private val UNSAFE_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|]")
private const val MAX_NAME_LENGTH = 80
