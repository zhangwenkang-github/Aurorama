package com.zhangwenkang.cinefin.book.presentation.reader.tts

/** 单句在原文中的区间；[start] / [end] 为原文下标（半开区间），已去掉首尾空白。 */
internal data class TtsSentenceSlice(val text: String, val start: Int, val end: Int)

/**
 * 中英双语句切分（W6 T9 / READER-32，定稿六 §1）。
 *
 * 句读边界：中文 `。！？；`、英文 `. ! ? ;`、省略号 `…`、换行；句末的右引号 / 右括号收进本句， 不在 `他说：“走吧。”` 的引号中间断句；`3.14`
 * 这类小数点不断句。超长句（> [MaxSentenceChars]） 在最近的空白 / 逗号处硬切，保证单句可播、可对齐高亮。
 */
internal object TtsSentenceSplitter {
    /** 单句上限：过长的句子一次播太久，也无法按句高亮。 */
    const val MaxSentenceChars = 220

    /** 句末可跟随的闭合符号（右引号 / 右括号）。 */
    private const val CLOSERS = "”’」』）)】》〉\"'"

    /** 硬切长句时优先断在这些标点之后。 */
    private const val SOFT_BREAKS = "，,、：:"

    /** 中文句读 + 英文强终止符（英文句点另有小数点判定）。 */
    private const val HARD_TERMINATORS = "。！？；!?;…"

    /** 切分整段纯文本；空串 / 纯空白返回空表。 */
    fun split(text: String): List<TtsSentenceSlice> {
        if (text.isBlank()) return emptyList()
        val slices = mutableListOf<TtsSentenceSlice>()
        var cursor = 0
        while (cursor < text.length) {
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
            if (cursor >= text.length) break
            val start = cursor
            var stop = text.length
            var index = cursor
            while (index < text.length) {
                val char = text[index]
                if (char == '\n' || char == '\r') {
                    stop = index
                    break
                }
                if (isTerminator(text, index)) {
                    stop = consumeTail(text, index)
                    break
                }
                index++
                if (index - start >= MaxSentenceChars) {
                    stop = softSplitPoint(text, start, index)
                    break
                }
            }
            // 尾部空白不进句子（TTS 读空白会停顿，句区间也要紧贴文本）。
            var trimmed = stop
            while (trimmed > start && text[trimmed - 1].isWhitespace()) trimmed--
            if (trimmed > start) {
                slices += TtsSentenceSlice(text.substring(start, trimmed), start, trimmed)
            }
            cursor = maxOf(stop, start + 1)
        }
        return slices
    }

    private fun isTerminator(text: String, index: Int): Boolean {
        val char = text[index]
        if (char in HARD_TERMINATORS) return true
        if (char != '.') return false
        // 小数点 / 版本号（3.14、1.2.3）不断句。
        val previous = text.getOrNull(index - 1)
        if (previous != null && previous.isDigit()) return false
        val next = text.getOrNull(index + 1) ?: return true
        return next.isWhitespace() || next in CLOSERS
    }

    /** 句末连续终止符（`！！`、`……`、`？！`）与右引号一起收进本句。 */
    private fun consumeTail(text: String, index: Int): Int {
        var stop = index + 1
        while (stop < text.length) {
            val next = text[stop]
            if (next in CLOSERS || next in HARD_TERMINATORS) stop++ else break
        }
        return stop
    }

    /** 长句硬切点：从上限往前找最近的空白 / 逗号；找不到就在上限处硬切。 */
    private fun softSplitPoint(text: String, start: Int, limit: Int): Int {
        val floor = start + MaxSentenceChars / 2
        for (index in limit - 1 downTo floor) {
            val char = text[index]
            if (char.isWhitespace() || char in SOFT_BREAKS) return index + 1
        }
        return limit
    }
}
