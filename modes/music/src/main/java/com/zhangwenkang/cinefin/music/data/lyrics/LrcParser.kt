package com.zhangwenkang.cinefin.music.data.lyrics

/**
 * LRC 文本解析（MU-5，纯函数）。
 *
 * 兼容范围（`REQUIREMENTS` §5.1「外挂歌词来源多样」）：
 * - 时间戳：`[mm:ss]` / `[mm:ss.x]` / `[mm:ss.xx]` / `[mm:ss.xxx]`，也接受 `[mm:ss:xx]` 写法；
 * - 一行多时间戳：`[00:01.00][00:05.00]歌词` → 展开成两行；
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

            val content = line.removePrefix(timeBlock).trim()
            if (content.isEmpty()) continue
            for (tag in TIME_TAG.findAll(timeBlock)) {
                val startMs = (timeOf(tag) - offsetMs).coerceAtLeast(0L)
                result += LyricLine(startMs = startMs, text = content)
            }
        }
        return result
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
