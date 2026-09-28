package com.zhangwenkang.cinefin.player.local.subtitle

import timber.log.Timber

/**
 * 一条解析好的字幕。时间为媒体时间轴上的毫秒。
 *
 * 只保留「文字 + 起止时间」：字体、位置、动画等 ASS 特效留给后续 libass 渲染
 * （见 docs/PLAYER_PLAN.md §1.18）；当前渲染层要的是延迟可调、双语可叠的纯文本。
 */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String) {

    /** 加上 [delayMs] 的偏移后，覆盖到 [positionMs] 时显示 */
    fun isVisibleAt(positionMs: Long, delayMs: Long): Boolean =
        positionMs >= startMs + delayMs && positionMs < endMs + delayMs
}

/**
 * 文本字幕解析器：SRT / WebVTT / ASS(SSA) 三种最常见的外挂字幕。
 *
 * 设计取舍：
 * - 解析结果只用于自研字幕渲染（延迟 / 双语 / 外观），图形字幕与服务端没给独立文件的字幕
 *   依旧交给播放内核原生渲染；
 * - ASS 的样式标签直接剥掉——本阶段的目标是「能调延迟、能叠双语」，
 *   特效还原是 §1.18 libass 的工作，不做半吊子实现。
 */
object SubtitleParser {

    /** 时间行：`00:00:01,000 --> 00:00:04,000`（SRT / VTT 共用） */
    private val TIME_RANGE_REGEX =
        Regex("""(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3}|\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3}|\d{1,2}:\d{2}[.,]\d{1,3})""")

    /** 单个时间戳：`1:02:03.50` / `00:00:01,000` */
    private val SINGLE_TIME_REGEX = Regex("""^(\d{1,2}):(\d{2}):(\d{2})[.,](\d{1,3})$""")

    private val HTML_TAG_REGEX = Regex("""<[^>]*>""")

    /**
     * ASS override 块 `{\i1}`。
     *
     * 注意：Android 的 ICU 正则引擎不接受 `\{` 这种转义（会抛
     * PatternSyntaxException: Syntax error near index ... \{[^}]*}），
     * 所以用字符类 `[{]` 表达左花括号——真机上就是在这一行崩溃过一次。
     */
    private val ASS_TAG_REGEX = Regex("""[{][^}]*[}]""")

    /** ASS 默认字段顺序（没有 Format 行时用） */
    private val ASS_DEFAULT_FIELDS =
        listOf(
            "layer",
            "start",
            "end",
            "style",
            "name",
            "marginl",
            "marginr",
            "marginv",
            "effect",
            "text",
        )

    /**
     * 按编码选择解析器。
     *
     * @param codec Jellyfin 给出的源编码（`subrip` / `ass` / `webvtt` ...）
     */
    fun parse(
        content: String,
        codec: String = "",
    ): List<SubtitleCue> =
        try {
            val cues =
                when (codec.lowercase()) {
                    "ass", "ssa" -> parseAss(content)
                    else -> parseTimeRanges(content)
                }
            cues.filter { it.text.isNotBlank() && it.endMs > it.startMs }
        } catch (e: Exception) {
            Timber.w(e, "字幕解析失败（codec=%s）", codec)
            emptyList()
        }

    /**
     * SRT / WebVTT 通用解析：逐行找时间行，时间行之后到空行为止的内容即字幕文本。
     *
     * 这样 VTT 的 `WEBVTT` 头、`NOTE` / `STYLE` 块、cue 标识行都会被自然跳过。
     */
    private fun parseTimeRanges(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val pending = StringBuilder()
        var cueStart = Long.MIN_VALUE
        var cueEnd = Long.MIN_VALUE

        fun flush() {
            if (cueStart != Long.MIN_VALUE && cueEnd != Long.MIN_VALUE) {
                val text = cleanMarkup(pending.toString())
                if (text.isNotBlank()) {
                    cues.add(SubtitleCue(cueStart, cueEnd, text))
                }
            }
            pending.setLength(0)
            cueStart = Long.MIN_VALUE
            cueEnd = Long.MIN_VALUE
        }

        for (raw in content.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trimEnd()
            val match = TIME_RANGE_REGEX.find(line)
            if (match != null) {
                flush()
                cueStart = parseTimestamp(match.groupValues[1]) ?: Long.MIN_VALUE
                cueEnd = parseTimestamp(match.groupValues[2]) ?: Long.MIN_VALUE
                continue
            }
            if (cueStart == Long.MIN_VALUE) continue
            if (line.isBlank()) {
                flush()
            } else {
                if (pending.isNotEmpty()) pending.append('\n')
                pending.append(line)
            }
        }
        flush()
        return cues
    }

    /** ASS / SSA：从 `[Events]` 段读 `Format:` 列序，再解析 `Dialogue:` 行 */
    private fun parseAss(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        var inEvents = false
        var fields = ASS_DEFAULT_FIELDS

        for (raw in content.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("[") -> inEvents = line.equals("[Events]", ignoreCase = true)
                !inEvents -> Unit
                line.startsWith("Format:", ignoreCase = true) -> {
                    val parsed = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                    if (parsed.isNotEmpty()) fields = parsed
                }
                line.startsWith("Dialogue:", ignoreCase = true) -> {
                    val payload = line.substringAfter(':')
                    val parts = payload.split(',', limit = fields.size.coerceAtLeast(1))
                    val startIndex = fields.indexOf("start")
                    val endIndex = fields.indexOf("end")
                    val textIndex = fields.indexOf("text")
                    if (startIndex < 0 || endIndex < 0 || textIndex < 0) continue
                    val start = parts.getOrNull(startIndex)?.trim()?.let(::parseTimestamp)
                    val end = parts.getOrNull(endIndex)?.trim()?.let(::parseTimestamp)
                    if (start == null || end == null) continue
                    // Text 是最后一个字段，split 用了 limit，因此下标直接可见
                    val text = parts.getOrNull(textIndex)?.let(::cleanAssText).orEmpty()
                    if (text.isNotBlank()) {
                        cues.add(SubtitleCue(start, end, text))
                    }
                }
            }
        }
        return cues
    }

    /** 解析 `h:mm:ss.cc` / `hh:mm:ss,mmm` / `mm:ss.mmm` */
    fun parseTimestamp(text: String): Long? {
        val match = SINGLE_TIME_REGEX.matchEntire(text.trim()) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val fractionRaw = match.groupValues[4]
        // 1 位 = 十分秒（百毫秒），2 位 = 厘秒，3 位 = 毫秒
        val millis =
            when (fractionRaw.length) {
                1 -> fractionRaw.toLong() * 100
                2 -> fractionRaw.toLong() * 10
                else -> fractionRaw.toLong()
            }
        return hours * 3_600_000 + minutes * 60_000 + seconds * 1_000 + millis
    }

    /** 去掉 SRT / VTT 的内联标签（`<i>`、`<c.colorE5E5E5>` 等） */
    private fun cleanMarkup(raw: String): String =
        raw.replace(HTML_TAG_REGEX, "").trim('\n', ' ', '\t')

    /** 去掉 ASS 的 override 块与换行转义 */
    private fun cleanAssText(raw: String): String =
        raw.replace(ASS_TAG_REGEX, "")
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " ")
            .trim('\n', ' ', '\t')
}
