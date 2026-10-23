package com.zhangwenkang.cinefin.music.presentation

enum class PlaylistExchangeFormat(
    val extension: String,
    val mimeType: String,
) {
    JSON(extension = "json", mimeType = "application/json"),
    M3U8(extension = "m3u8", mimeType = "audio/x-mpegurl"),
}

object PlaylistExchangeFileTypes {
    val importMimeTypes: Array<String> =
        arrayOf(
            "application/json",
            "audio/x-mpegurl",
            "application/vnd.apple.mpegurl",
            "audio/mpegurl",
            "text/plain",
        )

    /** 只按有明确格式头的文件内容分流；扩展名和曲目标题都不参与身份判断。 */
    fun detect(content: String): PlaylistExchangeFormat? {
        val normalized = content.removePrefix("\uFEFF").trimStart()
        return when {
            normalized.startsWith("{") -> PlaylistExchangeFormat.JSON
            normalized.startsWith("#EXTM3U") -> PlaylistExchangeFormat.M3U8
            else -> null
        }
    }

    fun suggestedFileName(playlistName: String, format: PlaylistExchangeFormat): String {
        val safeName =
            playlistName
                .replace(INVALID_FILE_NAME_CHARS, "_")
                .trim()
                .ifEmpty { "歌单" }
                .take(MAX_FILE_NAME_BASE_LENGTH)
        return "$safeName.${format.extension}"
    }

    private const val MAX_FILE_NAME_BASE_LENGTH = 120
    private val INVALID_FILE_NAME_CHARS = Regex("""[\\/:*?"<>|\r\n]+""")
}

data class PlaylistExchangeFeedback(val message: String)

data class PlaylistExportReady(
    val fileName: String,
    val format: PlaylistExchangeFormat,
    val content: String,
)
