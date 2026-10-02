package com.zhangwenkang.cinefin.music.data.lyrics

/** 歌词显示语言；[ORIGINAL] 恒显示原文侧。 */
enum class LyricsDisplayLanguage(val label: String) {
    SIMPLIFIED_CHINESE("简体中文"),
    TRADITIONAL_CHINESE("繁體中文"),
    JAPANESE("日文"),
    ENGLISH("英文"),
    ORIGINAL("原文"),
}

/**
 * 显示设置（MU-5 能力 3 / 4）。
 *
 * [language] 默认 [LyricsDisplayLanguage.SIMPLIFIED_CHINESE]，由 [LyricsPresenter.defaultDisplay]
 * 按"歌词里实际有什么"回落； [bilingual] 打开后主行 + 副行（原文 + 译文）同时展示。
 */
data class LyricsDisplayState(
    val language: LyricsDisplayLanguage = LyricsDisplayLanguage.SIMPLIFIED_CHINESE,
    val bilingual: Boolean = false,
    val follow: Boolean = true,
)

/** 一行渲染结果；[subText] 仅在双语对照且确实有第二种语言时非空。 */
data class LyricsRow(
    val startMs: Long?,
    val mainText: String,
    val subText: String? = null,
    /** 主行的逐字数据（W28-MUSIC）；空列表 = 无逐字数据，显示侧回落整行高亮。 */
    val words: List<LyricWord> = emptyList(),
)

/** 逐字高亮渲染单元：[progress] 0 = 未唱、1 = 已唱完。 */
data class WordHighlight(val text: String, val progress: Float)

/** 全屏播放页的歌词窗口：当前行 ±1（W24-MUSIC · C 组）。 */
data class LyricsWindow(val previous: String?, val current: String?, val next: String?)

/**
 * 歌词显示选择与滚动同步（MU-5，纯函数）。
 *
 * 语言切换只影响显示侧：配对与时间轴始终取自 [LyricsDocument]。
 */
object LyricsPresenter {

    /** 语言列表显示顺序：简中 > 繁中 > 日文 > 英文 > 混合 > 其他。 */
    fun languageRank(language: LyricLanguage): Int =
        when (language) {
            LyricLanguage.SIMPLIFIED_CHINESE -> 0
            LyricLanguage.TRADITIONAL_CHINESE -> 1
            LyricLanguage.JAPANESE -> 2
            LyricLanguage.ENGLISH -> 3
            LyricLanguage.MIXED -> 4
            LyricLanguage.OTHER -> 5
        }

    /** 可选显示语言：永远包含"原文"，再按聚合结果补充实际出现的语言。 */
    fun displayLanguages(document: LyricsDocument): List<LyricsDisplayLanguage> {
        val result = mutableListOf<LyricsDisplayLanguage>()
        document.availableLanguages.forEach { language ->
            val display = language.toDisplay() ?: return@forEach
            if (display !in result) result += display
        }
        result += LyricsDisplayLanguage.ORIGINAL
        return result
    }

    /** 默认显示：存在中文（简 / 繁）→ 简体中文优先；否则回落"原文"（REQUIREMENTS §5.1 能力 3）。 */
    fun defaultDisplay(
        document: LyricsDocument,
        bilingual: Boolean = false,
        follow: Boolean = true,
    ): LyricsDisplayState {
        val language =
            when {
                LyricLanguage.SIMPLIFIED_CHINESE in document.availableLanguages ->
                    LyricsDisplayLanguage.SIMPLIFIED_CHINESE
                LyricLanguage.TRADITIONAL_CHINESE in document.availableLanguages ->
                    LyricsDisplayLanguage.TRADITIONAL_CHINESE
                else -> LyricsDisplayLanguage.ORIGINAL
            }
        return LyricsDisplayState(language = language, bilingual = bilingual, follow = follow)
    }

    /** 按 [display] 把歌词块渲染成行列表。 */
    fun rows(document: LyricsDocument, display: LyricsDisplayState): List<LyricsRow> =
        document.blocks.map { block ->
            val main = pick(block, display.language) ?: block.primary
            val other = block.lines.firstOrNull { it !== main && it.text != main.text }
            LyricsRow(
                startMs = block.startMs,
                mainText = main.text,
                subText = if (display.bilingual) other?.text else null,
                words = main.words,
            )
        }

    /**
     * 逐字高亮进度（W28-MUSIC，纯函数）。
     *
     * 每个词按"自身起点 → 下一个词起点"折算 0..1 的推进比例；最后一个词用 [lineEndMs]（通常是下一行的开始时间） 兜底，缺失时用
     * [DEFAULT_WORD_MS]。没有逐字数据时返回空列表，调用方回落整行高亮。
     */
    fun wordHighlights(
        words: List<LyricWord>,
        positionMs: Long,
        lineEndMs: Long? = null,
    ): List<WordHighlight> = words.mapIndexed { index, word ->
        val nextStart = words.getOrNull(index + 1)?.startMs ?: lineEndMs
        val end = nextStart?.takeIf { it > word.startMs } ?: (word.startMs + DEFAULT_WORD_MS)
        val progress =
            when {
                positionMs <= word.startMs -> 0f
                positionMs >= end -> 1f
                else -> (positionMs - word.startMs).toFloat() / (end - word.startMs).toFloat()
            }
        WordHighlight(text = word.text, progress = progress.coerceIn(0f, 1f))
    }

    /** 当前行索引（滚动同步 / 高亮，纯函数）：最后一行"开始时间 ≤ [positionMs]" 的行； 播放位置早于首行时停在第一行， 无歌词时返回 -1。 */
    fun activeIndex(rows: List<LyricsRow>, positionMs: Long): Int {
        if (rows.isEmpty()) return -1
        var index = 0
        rows.forEachIndexed { i, row ->
            val start = row.startMs ?: return@forEachIndexed
            if (start <= positionMs) index = i
        }
        return index
    }

    /** 当前逐字片段下标（最后一段"开始时间 ≤ [positionMs]"）；未到首词或无数据返回 -1（W28-MUSIC）。 */
    fun activeWordIndex(words: List<LyricWord>, positionMs: Long): Int {
        var index = -1
        words.forEachIndexed { i, word -> if (word.startMs <= positionMs) index = i }
        return index
    }

    /**
     * 全屏播放页歌词：当前行 ±1（纯函数，W24-MUSIC · C8）。
     *
     * 首行 / 末行自然缺一侧；越界索引收敛到最近一行，空歌词返回全 null。
     */
    fun window(rows: List<LyricsRow>, activeIndex: Int): LyricsWindow {
        if (rows.isEmpty()) return LyricsWindow(null, null, null)
        val index = activeIndex.coerceIn(0, rows.lastIndex)
        return LyricsWindow(
            previous = rows.getOrNull(index - 1)?.mainText,
            current = rows[index].mainText,
            next = rows.getOrNull(index + 1)?.mainText,
        )
    }

    private fun pick(block: LyricBlock, language: LyricsDisplayLanguage): LyricLine? {
        if (language == LyricsDisplayLanguage.ORIGINAL) return block.primary
        // 优先取译文侧：日文原文若全为汉字会被判成中文（规格规定"仅汉字 → 中文"），
        // 此时若按"第一行匹配"取值会拿到原文而不是中文译文。
        val secondary = block.secondary
        if (
            secondary != null && language == LineLanguageDetector.detect(secondary.text).toDisplay()
        ) {
            return secondary
        }
        return block.lines.firstOrNull { line ->
            language == LineLanguageDetector.detect(line.text).toDisplay()
        }
    }

    private fun LyricLanguage.toDisplay(): LyricsDisplayLanguage? =
        when (this) {
            LyricLanguage.SIMPLIFIED_CHINESE -> LyricsDisplayLanguage.SIMPLIFIED_CHINESE
            LyricLanguage.TRADITIONAL_CHINESE -> LyricsDisplayLanguage.TRADITIONAL_CHINESE
            LyricLanguage.JAPANESE -> LyricsDisplayLanguage.JAPANESE
            LyricLanguage.ENGLISH -> LyricsDisplayLanguage.ENGLISH
            LyricLanguage.MIXED,
            LyricLanguage.OTHER -> null
        }

    /** 最后一个词没有结束时间时的兜底时长（与 500 ms 位置采样同量级）。 */
    const val DEFAULT_WORD_MS = 500L
}
