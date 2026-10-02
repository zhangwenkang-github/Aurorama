package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.zhangwenkang.cinefin.music.data.lyrics.LyricWord
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsPresenter
import com.zhangwenkang.cinefin.music.data.lyrics.lyricWordsMatchText

/**
 * 逐字高亮文本（W28-MUSIC，显示侧共用）。
 *
 * 把 [LyricsPresenter.wordHighlights] 的推进比例映射成"未唱色 → 已唱色"的颜色渐变；没有逐字数据（或片段与整行文本 不一致）时返回
 * null，调用方回落整行高亮（原有滚动 / 高亮同步不变）。
 */
internal fun wordHighlightedText(
    text: String,
    words: List<LyricWord>,
    positionMs: Long,
    lineEndMs: Long?,
    idleColor: Color,
    highlightColor: Color,
): AnnotatedString? {
    if (!lyricWordsMatchText(words, text)) return null
    val highlights = LyricsPresenter.wordHighlights(words, positionMs, lineEndMs)
    if (highlights.isEmpty()) return null
    return buildAnnotatedString {
        highlights.forEach { word ->
            withStyle(SpanStyle(color = lerp(idleColor, highlightColor, word.progress))) {
                append(word.text)
            }
        }
    }
}
