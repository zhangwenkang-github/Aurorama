package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsFontScale
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsLineSpacing
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsTextSettings

/**
 * 歌词页 / 歌词面板共用的显示档位条（W79 · #37）。
 *
 * 两排 chip：上排「字号」（小 / 标准 / 大 / 特大），下排「行距」（紧凑 / 标准 / 宽松）。两排共用一个
 * [LyricsTextSettings]（落偏好键，冷启保持），所以歌词页与面板里的选择永远一致。窄屏上 chip 行可横向滚动， 保证 4 档字号不会被裁切。
 */
@Composable
fun LyricsTextSettingsBar(
    settings: LyricsTextSettings,
    onSelectFontScale: (LyricsFontScale) -> Unit,
    onSelectLineSpacing: (LyricsLineSpacing) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LyricsSettingsRow(label = "字号") {
            LyricsFontScale.entries.forEach { scale ->
                LyricsSettingsChip(
                    label = scale.label,
                    selected = scale == settings.fontScale,
                    onClick = { onSelectFontScale(scale) },
                )
            }
        }
        LyricsSettingsRow(label = "行距") {
            LyricsLineSpacing.entries.forEach { spacing ->
                LyricsSettingsChip(
                    label = spacing.label,
                    selected = spacing == settings.lineSpacing,
                    onClick = { onSelectLineSpacing(spacing) },
                )
            }
        }
    }
}

@Composable
private fun LyricsSettingsRow(label: String, content: @Composable () -> Unit) {
    val colors = LocalCinefinColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(start = CinefinSpacing.Space5, end = CinefinSpacing.Space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = CinefinType.BodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(end = CinefinSpacing.Space2),
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

@Composable
private fun LyricsSettingsChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val media = LocalMediaColors.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors =
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = media.container,
                selectedLabelColor = media.bright,
            ),
    )
}

/**
 * 把字号 / 行距档位套到基准 [TextStyle] 上（W79 · #37）。
 *
 * 行高按"实际字号 × 字体自然行高比例 × 行距倍数"算（见 [LyricsTextSettings.lineSp]）：放大字号时行高同步放大，
 * 不会出现"特大"档行间重叠；同时修掉"缩放基准样式 lineHeight 会被渲染层忽略、行距档位失效"这个坑。 默认档（标准 / 标准）与既有渲染观感同档（自然行高）。
 */
internal fun TextStyle.lyricsScaled(settings: LyricsTextSettings): TextStyle {
    if (fontSize.isUnspecified) return this
    return copy(
        fontSize = settings.fontSp(fontSize.value).sp,
        lineHeight = settings.lineSp(fontSize.value).sp,
    )
}

/** 歌词正文的水平对齐（W79 · #26）：三处歌词界面统一居中。 */
internal val lyricsTextAlign: TextAlign = TextAlign.Center
