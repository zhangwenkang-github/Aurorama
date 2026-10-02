package com.zhangwenkang.cinefin.music.data.lyrics

/**
 * LRC 文本解析（MU-5，纯函数）。
 *
 * 兼容范围（`REQUIREMENTS` §5.1「外挂歌词来源多样」）：
 * - 时间戳：`[mm:ss]` / `[mm:ss.x]` / `[mm:ss.xx]` / `[mm:ss.xxx]`，也接受 `[mm:ss:xx]` 写法；
 * - 一行多时间戳：`[00:01.00][00:05.00]歌词` → 展开成两行；
 * - 逐字（增强 LRC）：行内 `<mm:ss.xx>` 标签解析为 [LyricWord] 列表（W28-MUSIC），文本去掉标签后照常显示；
 * - 标签：`[offset:±ms]`（整体时间平移）、ID 标签 `[ti:]` `[ar:]` `[al:]` `[by:]` `[length:]` 等 → 元数据行；
 * - 空行 / 无时间戳的普通文本行保留为未同步行（`startMs = null`），由 [LyricsNormalizer] 再做清洗。
 *
 * `offset` 语义按主流实现取 `时间戳 - offset`：正值让歌词整体提前。
 */
object LrcParser {

    /** 行首连续时间戳块，例如 `[00:12.34][00:15.00]`。 */
    private val TIME_BLOCK = Regex("""^(?:\s*\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?\])+""")

    /** 单个时间戳，例如 `[01:02.345]`。 */
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")

    /** 标签行，例如 `[ti:aLIEz]` / `[offset:+300]` / `[offset:-300]`。 */
    private val ID_TAG = Regex("""^\s*\[([A-Za-z#]{1,12})\s*:\s*(.*?)\]\s*$""")

    /** 行内逐字标签（增强 LRC），例如 `<00:12.34>`。 */
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    /**
     * 解析 [text]；返回行序与文件一致（时间戳升序由 [LyricsNormalizer] 保证）。
     *
     * 同一个时间戳块里的多个时间戳会各产生一行 [LyricLine]。
     */
    fun parse(text: String): List<LyricLine> {
        val rawLines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val offsetMs = rawLines.firstNotNullOfOrNull { line -> offsetOf(line) } ?: 0L
        val result = mutableListOf<LyricLine>()

        for (raw in rawLines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val idTag = ID_TAG.matchEntire(line)
            if (idTag != null && !isTimeStamp(line)) {
                // offset 是控制标签（已在上方读取），不再作为行输出；其余 ID 标签保留为元数据行
                if (!idTag.groupValues[1].equals("offset", ignoreCase = true)) {
                    result += LyricLine(startMs = null, text = line, isMetadata = true)
                }
                continue
            }

            val timeBlock = TIME_BLOCK.find(line)?.value
            if (timeBlock == null) {
                result += LyricLine(startMs = null, text = line)
                continue
            }

            val (content, words) = parseWords(line.removePrefix(timeBlock).trim(), offsetMs)
            if (content.isEmpty()) continue
            for (tag in TIME_TAG.findAll(timeBlock)) {
                val startMs = (timeOf(tag) - offsetMs).coerceAtLeast(0L)
                result += LyricLine(startMs = startMs, text = content, words = words)
            }
        }
        return result
    }

    /**
     * 解析增强 LRC 的行内逐字标签：`[00:12.34]<00:12.34>词<00:12.80>句` → 文本 `词句` + 两段 [LyricWord]。
     *
     * 没有逐字标签时原样返回文本、片段为空（整行高亮照旧）；词时间与行时间共用同一个 `[offset:]` 语义；
     * 相邻标签之间的空词段跳过。文本取各词段拼接（保留词间空格），仅当标签间全是空白时回落"去掉标签的整行文本"。
     */
    internal fun parseWords(content: String, offsetMs: Long = 0L): Pair<String, List<LyricWord>> {
        val matches = WORD_TAG.findAll(content).toList()
        if (matches.isEmpty()) return content to emptyList()

        val words = mutableListOf<LyricWord>()
        // 首个标签之前的残留文本（非标准写法）：按首词时间兜底成一段，不丢内容
        val leading = content.substring(0, matches.first().range.first).trim()
        if (leading.isNotEmpty()) {
            words += LyricWord((timeOf(matches.first()) - offsetMs).coerceAtLeast(0L), leading)
        }
        matches.forEachIndexed { index, tag ->
            val wordText =
                content.substring(
                    tag.range.last + 1,
                    matches.getOrNull(index + 1)?.range?.first ?: content.length,
                )
            if (wordText.isNotBlank()) {
                words += LyricWord((timeOf(tag) - offsetMs).coerceAtLeast(0L), wordText)
            }
        }

        val text =
            words
                .joinToString("") { it.text }
                .trim()
                .ifEmpty { WORD_TAG.replace(content, "").trim() }
        return text to words
    }

    /** 读取 `[offset:±ms]`；没有该标签时返回 null。 */
    private fun offsetOf(line: String): Long? {
        val tag = ID_TAG.matchEntire(line.trim()) ?: return null
        if (!tag.groupValues[1].equals("offset", ignoreCase = true)) return null
        return tag.groupValues[2].trim().removePrefix("+").toLongOrNull()
    }

    private fun isTimeStamp(line: String) = TIME_BLOCK.find(line) != null

    /** `[mm:ss(.fff)]` → 毫秒；1 位小数按 0.1s、2 位按 0.01s、3 位按 0.001s 计算。 */
    private fun timeOf(tag: MatchResult): Long {
        val minutes = tag.groupValues[1].toLong()
        val seconds = tag.groupValues[2].toLong()
        val fraction = tag.groupValues[3]
        val fractionMs =
            when (fraction.length) {
                0 -> 0L
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                else -> fraction.take(3).toLong()
            }
        return minutes * 60_000 + seconds * 1_000 + fractionMs
    }
}
