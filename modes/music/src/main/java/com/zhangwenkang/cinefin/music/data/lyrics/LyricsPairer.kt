package com.zhangwenkang.cinefin.music.data.lyrics

/**
 * 双语配对（MU-5，纯函数）。
 *
 * 服务端实测（10.11.8，三样例）：原文与译文**共享同一 `Start` 时间戳**成对返回；外挂 LRC 则常见"相邻两行、时间戳差几十毫秒"。 因此配对判据 = 相同时间戳，或时间戳差
 * ≤ [DEFAULT_TOLERANCE_MS]（250 ms）。
 *
 * 原文 / 译文顺序：
 * - 一行中文、一行非中文 → 非中文侧为原文（primary），中文侧为译文（secondary）——对应"默认简体中文展示译文"；
 * - 同为中文但简繁不同 → 繁体侧为原文、简体侧为译文；
 * - 其余情况保持源顺序。
 */
object LyricsPairer {

    const val DEFAULT_TOLERANCE_MS = 250L

    /**
     * 把清洗后的 [lines] 配对成 [LyricBlock]。
     *
     * 输入必须是 [LyricsNormalizer.normalize] 的输出（已按时间戳升序、无元数据行）。
     */
    fun pair(lines: List<LyricLine>, toleranceMs: Long = DEFAULT_TOLERANCE_MS): List<LyricBlock> {
        val blocks = mutableListOf<LyricBlock>()
        val pending = mutableListOf<LyricLine>()

        for (line in lines) {
            if (pending.isEmpty()) {
                pending += line
                continue
            }
            val head = pending.first()
            if (isPair(head, line, toleranceMs)) {
                blocks += toBlock(head, line)
                pending.clear()
            } else {
                blocks += LyricBlock(startMs = head.startMs, primary = head)
                pending.clear()
                pending += line
            }
        }
        pending.forEach { blocks += LyricBlock(startMs = it.startMs, primary = it) }
        return blocks
    }

    /**
     * 配对判据：时间戳完全相同 → 直接成对（服务端双语实测行为）； 时间戳"极近"（≤ [toleranceMs]）→ 只在两侧字形家族不同（中文 ⇄ 非中文、繁 ⇄ 简）时成对。
     *
     * 第二条约束用于保护"纯中文歌里相邻很近的两句"不被误判成原文 + 译文（`爱的回归线` 类样例）。
     */
    private fun isPair(first: LyricLine, second: LyricLine, toleranceMs: Long): Boolean {
        val a = first.startMs ?: return false
        val b = second.startMs ?: return false
        if (a == b) return true
        if (kotlin.math.abs(a - b) > toleranceMs) return false
        val firstLanguage = LineLanguageDetector.detect(first.text)
        val secondLanguage = LineLanguageDetector.detect(second.text)
        return glyphFamily(firstLanguage) != glyphFamily(secondLanguage)
    }

    /** 字形家族：汉字简 / 汉字繁 / 其他（日文假名、拉丁、混合、符号都算"其他"）。 */
    private fun glyphFamily(language: LyricLanguage): Int =
        when (language) {
            LyricLanguage.SIMPLIFIED_CHINESE -> 1
            LyricLanguage.TRADITIONAL_CHINESE -> 2
            else -> 0
        }

    private fun toBlock(first: LyricLine, second: LyricLine): LyricBlock {
        val (primary, secondary) = orderOriginalFirst(first, second)
        return LyricBlock(startMs = primary.startMs ?: secondary.startMs, primary, secondary)
    }

    /** 决定哪一行是原文；同为原文时按简繁优先级与源顺序。 */
    private fun orderOriginalFirst(
        first: LyricLine,
        second: LyricLine,
    ): Pair<LyricLine, LyricLine> {
        val firstIsChinese = isChinese(first)
        val secondIsChinese = isChinese(second)
        return when {
            firstIsChinese && !secondIsChinese -> second to first
            !firstIsChinese && secondIsChinese -> first to second
            firstIsChinese && secondIsChinese -> orderChinesePair(first, second)
            else -> first to second
        }
    }

    private fun orderChinesePair(first: LyricLine, second: LyricLine): Pair<LyricLine, LyricLine> {
        val firstLang = LineLanguageDetector.detect(first.text)
        val secondLang = LineLanguageDetector.detect(second.text)
        return when {
            firstLang == LyricLanguage.TRADITIONAL_CHINESE &&
                secondLang == LyricLanguage.SIMPLIFIED_CHINESE -> first to second
            firstLang == LyricLanguage.SIMPLIFIED_CHINESE &&
                secondLang == LyricLanguage.TRADITIONAL_CHINESE -> second to first
            else -> first to second
        }
    }

    private fun isChinese(line: LyricLine): Boolean =
        when (LineLanguageDetector.detect(line.text)) {
            LyricLanguage.SIMPLIFIED_CHINESE,
            LyricLanguage.TRADITIONAL_CHINESE -> true
            else -> false
        }
}
