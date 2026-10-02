package com.zhangwenkang.cinefin.music.data.lyrics

import java.util.Locale

/**
 * 歌词编辑器的纯函数模型（W25-MUSIC）。
 *
 * 时间戳在编辑态保留为文本（便于边输边改），保存时统一校验；[id] 只用于列表 key / 回调定位，不参与落盘。
 */
data class LyricEditLine(val id: Long, val timeText: String, val text: String)

/** 时间戳解析结果：`valid=false` 表示格式非法；`startMs=null` 且 `valid=true` 表示留空（未同步行）。 */
data class LyricTimeParse(val startMs: Long?, val valid: Boolean)

/** `mm:ss` / `mm:ss.x` / `mm:ss.xx` / `mm:ss.xxx`，也接受 `mm:ss:xx` 写法。 */
private val LYRIC_TIME_PATTERN = Regex("""^(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?$""")

/** 毫秒 → 编辑框文本；留空表示未同步行。 */
fun formatLyricTime(startMs: Long?): String {
    if (startMs == null) return ""
    val ms = startMs.coerceAtLeast(0L)
    return String.format(
        Locale.US,
        "%02d:%02d.%03d",
        ms / 60_000,
        (ms % 60_000) / 1_000,
        ms % 1_000,
    )
}

/** 编辑框文本 → 毫秒（1 位小数按 0.1s、2 位按 0.01s、3 位按 0.001s）。 */
fun parseLyricTime(text: String): LyricTimeParse {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return LyricTimeParse(startMs = null, valid = true)
    val match = LYRIC_TIME_PATTERN.matchEntire(trimmed) ?: return LyricTimeParse(null, false)
    val seconds = match.groupValues[2].toLongOrNull() ?: return LyricTimeParse(null, false)
    if (seconds >= 60) return LyricTimeParse(null, false)
    val fraction = match.groupValues[3]
    val fractionMs =
        when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
    val startMs = match.groupValues[1].toLong() * 60_000 + seconds * 1_000 + fractionMs
    return LyricTimeParse(startMs = startMs, valid = true)
}

/** 打开编辑器时的预填行：当前文档的全部原始行（原文 + 译文按块序展开）。 */
fun lyricEditLines(document: LyricsDocument?): List<LyricEditLine> =
    document
        ?.blocks
        ?.flatMap { block -> block.lines }
        .orEmpty()
        .mapIndexed { index, line ->
            LyricEditLine(
                id = index.toLong(),
                timeText = formatLyricTime(line.startMs),
                text = line.text,
            )
        }

/** LRC 文本 → 编辑行（导入后回填编辑器）。 */
fun lyricEditLinesFromText(text: String): List<LyricEditLine> =
    LrcParser.parse(text).mapIndexed { index, line ->
        LyricEditLine(
            id = index.toLong(),
            timeText = formatLyricTime(line.startMs),
            text = line.text,
        )
    }

/** 编辑行 → 保存用的 [LyricLine]；任一时间戳格式非法时返回 null（由调用方提示）。 */
fun lyricEditLinesToLyricLines(lines: List<LyricEditLine>): List<LyricLine>? {
    val result = mutableListOf<LyricLine>()
    for (line in lines) {
        if (line.text.isBlank()) continue
        val parsed = parseLyricTime(line.timeText)
        if (!parsed.valid) return null
        result += LyricLine(startMs = parsed.startMs, text = line.text.trim())
    }
    return result
}
