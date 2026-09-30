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
)

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
            )
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
}
