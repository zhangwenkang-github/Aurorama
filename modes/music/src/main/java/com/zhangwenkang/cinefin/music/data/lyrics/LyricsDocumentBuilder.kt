package com.zhangwenkang.cinefin.music.data.lyrics

/**
 * 原始歌词行 → [LyricsDocument]（MU-5，纯函数）。
 *
 * 流程：清洗（[LyricsNormalizer]）→ 配对（[LyricsPairer]）→ 逐行语言识别并聚合可用语言列表。 服务端 DTO / 外挂 LRC 都先转成 [LyricLine]
 * 再走这里，保证两条来源的显示行为一致。
 */
object LyricsDocumentBuilder {

    fun build(rawLines: List<LyricLine>, source: LyricsSource): LyricsDocument {
        val lines = LyricsNormalizer.normalize(rawLines)
        val blocks = LyricsPairer.pair(lines)
        val languages =
            blocks
                .flatMap { it.lines }
                .map { LineLanguageDetector.detect(it.text) }
                .distinct()
                .sortedBy { LyricsPresenter.languageRank(it) }
        return LyricsDocument(
            blocks = blocks,
            availableLanguages = languages,
            synced = blocks.any { it.startMs != null },
            source = source,
        )
    }
}
