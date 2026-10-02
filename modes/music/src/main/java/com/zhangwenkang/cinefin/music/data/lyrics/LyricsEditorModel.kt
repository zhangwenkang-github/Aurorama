package com.zhangwenkang.cinefin.music.data.lyrics

import java.util.Locale

/**
 * 歌词编辑器的纯函数模型（W25-MUSIC）。
 *
 * 时间戳在编辑态保留为文本（便于边输边改），保存时统一校验；[id] 只用于列表 key / 回调定位，不参与落盘。 W28-MUSIC：整段时间轴偏移 / 单行 ±100ms
 * 微调（[shiftLyricEditLines] / [nudgeLyricEditLine]）与逐字数据透传。
 */
data class LyricEditLine(
    val id: Long,
    val timeText: String,
    val text: String,
    /** 逐字数据（整行文本未改时随行时间一起平移并保留；文本改过后保存时丢弃，回落整行高亮）。 */
    val words: List<LyricWord> = emptyList(),
)

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

/** 自定义偏移文本 → 毫秒：接受 `500` / `+500` / `-500`；空 / 非法返回 null。 */
fun parseLyricOffsetMs(text: String): Long? = text.trim().removePrefix("+").toLongOrNull()

/**
 * 整段时间轴偏移（W28-MUSIC）：所有有时间戳的行统一平移 [deltaMs]（结果不小于 0）。
 *
 * 未同步行（留空）与时间戳非法的行原样保留；逐字数据随行平移。
 */
fun shiftLyricEditLines(lines: List<LyricEditLine>, deltaMs: Long): List<LyricEditLine> =
    lines.map { line ->
        shiftLyricEditLine(line, deltaMs)
    }

/** 单行时间微调（W28-MUSIC）：只改 [id] 命中的行，其余原样；行时间被钳制时逐字按实际差值平移。 */
fun nudgeLyricEditLine(lines: List<LyricEditLine>, id: Long, deltaMs: Long): List<LyricEditLine> =
    lines.map { line ->
        if (line.id == id) shiftLyricEditLine(line, deltaMs) else line
    }

/** 编辑框文本变化对应的实际偏移量（用于把逐字数据一起平移）；任一侧无有效时间戳返回 null。 */
internal fun lyricTimeDeltaMs(oldText: String, newText: String): Long? {
    val old = parseLyricTime(oldText)
    val new = parseLyricTime(newText)
    if (!old.valid || !new.valid) return null
    val oldStart = old.startMs ?: return null
    val newStart = new.startMs ?: return null
    return newStart - oldStart
}

/** 逐字数据整体平移（结果不小于 0）。 */
internal fun shiftLyricWords(words: List<LyricWord>, deltaMs: Long): List<LyricWord> = words.map {
    it.copy(startMs = (it.startMs + deltaMs).coerceAtLeast(0L))
}

/** 逐字数据在保存时是否仍然有效：文本一致，且首词起点与行时间戳重合（手动改时间后不再重合 → 丢弃逐字、回落整行）。 */
internal fun lyricWordsConsistentWithLine(
    words: List<LyricWord>,
    text: String,
    startMs: Long?,
): Boolean = startMs != null && lyricWordsMatchText(words, text) && words.first().startMs == startMs

private fun shiftLyricEditLine(line: LyricEditLine, deltaMs: Long): LyricEditLine {
    if (deltaMs == 0L) return line
    val parsed = parseLyricTime(line.timeText)
    val start = parsed.startMs ?: return line
    val shifted = (start + deltaMs).coerceAtLeast(0L)
    if (shifted == start) return line
    val applied = shifted - start
    return line.copy(
        timeText = formatLyricTime(shifted),
        words = shiftLyricWords(line.words, applied),
    )
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
                words = line.words,
            )
        }

/** LRC 文本 → 编辑行（导入后回填编辑器）。 */
fun lyricEditLinesFromText(text: String): List<LyricEditLine> =
    LrcParser.parse(text).mapIndexed { index, line ->
        LyricEditLine(
            id = index.toLong(),
            timeText = formatLyricTime(line.startMs),
            text = line.text,
            words = line.words,
        )
    }

/** 编辑行 → 保存用的 [LyricLine]；任一时间戳格式非法时返回 null（由调用方提示）。 */
fun lyricEditLinesToLyricLines(lines: List<LyricEditLine>): List<LyricLine>? {
    val result = mutableListOf<LyricLine>()
    for (line in lines) {
        if (line.text.isBlank()) continue
        val parsed = parseLyricTime(line.timeText)
        if (!parsed.valid) return null
        val text = line.text.trim()
        result +=
            LyricLine(
                startMs = parsed.startMs,
                text = text,
                words =
                    line.words
                        .takeIf { lyricWordsConsistentWithLine(it, text, parsed.startMs) }
                        .orEmpty(),
            )
    }
    return result
}
