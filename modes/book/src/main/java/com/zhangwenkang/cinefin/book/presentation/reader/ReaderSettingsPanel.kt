package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 阅读器排版面板（UI_DESIGN_SYSTEM §8.14）。
 *
 * 宽 512dp、圆角 22dp、内边距 30/28dp；字号 / 行距 / 边距滑块，字体 / 对齐 chip 行， 主题缩略图 5 个（78dp 圆角
 * 14dp）。所有修改即时回调，由调用方提交导航器并持久化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSettingsPanel(
    settings: ReaderSettings,
    systemDark: Boolean,
    onSettingsChange: (ReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = settings.contentColor(systemDark)
    val accent = settings.accentColor(systemDark)
    val onAccent = settings.onAccentColor(systemDark)

    Column(
        modifier =
            modifier
                .widthIn(max = 512.dp)
                .fillMaxWidth()
                .clip(CinefinShapes.Lg)
                .background(settings.chromeColor(systemDark))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 30.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space5),
    ) {
        Text(text = "阅读设置", style = CinefinType.LabelLarge, color = contentColor)

        SectionLabel("阅读模式", contentColor)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ReaderMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.mode == mode,
                    onClick = { onSettingsChange(settings.copy(mode = mode)) },
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ReaderMode.entries.size,
                        ),
                    colors =
                        SegmentedButtonDefaults.colors(
                            activeContainerColor = accent,
                            activeContentColor = onAccent,
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = contentColor.copy(alpha = 0.72f),
                        ),
                ) {
                    Text(mode.label, style = CinefinType.LabelMedium)
                }
            }
        }

        SliderRow(
            label = "字号",
            valueText = "${(settings.fontSize * 100).roundToInt()}%",
            value = settings.fontSize,
            range = ReaderFontSizeRange,
            contentColor = contentColor,
            accent = accent,
            onValueChange = { onSettingsChange(settings.copy(fontSize = it)) },
        )
        SliderRow(
            label = "行距",
            valueText = String.format(Locale.US, "%.1f", settings.lineHeight),
            value = settings.lineHeight,
            range = ReaderLineHeightRange,
            contentColor = contentColor,
            accent = accent,
            onValueChange = { onSettingsChange(settings.copy(lineHeight = it)) },
        )
        SliderRow(
            label = "边距",
            valueText = String.format(Locale.US, "%.1f", settings.pageMargins),
            value = settings.pageMargins,
            range = ReaderPageMarginsRange,
            contentColor = contentColor,
            accent = accent,
            onValueChange = { onSettingsChange(settings.copy(pageMargins = it)) },
        )

        SectionLabel("字体", contentColor)
        ChipRow(
            options = ReaderFont.entries,
            selected = settings.font,
            label = { it.label },
            contentColor = contentColor,
            accent = accent,
            onSelect = { onSettingsChange(settings.copy(font = it)) },
        )

        SectionLabel("对齐", contentColor)
        ChipRow(
            options = ReaderTextAlign.entries,
            selected = settings.textAlign,
            label = { it.label },
            contentColor = contentColor,
            accent = accent,
            onSelect = { onSettingsChange(settings.copy(textAlign = it)) },
        )

        SectionLabel("主题", contentColor)
        Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3)) {
            ReaderTheme.entries.forEach { theme ->
                ThemeSwatch(
                    theme = theme,
                    selected = settings.theme == theme,
                    systemDark = systemDark,
                    onSelect = { onSettingsChange(settings.copy(theme = theme)) },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, contentColor: Color) {
    Text(
        text = text,
        style = CinefinType.LabelMedium,
        color = contentColor.copy(alpha = 0.72f),
    )
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    contentColor: Color,
    accent: Color,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
    ) {
        Text(
            text = label,
            modifier = Modifier.width(48.dp),
            style = CinefinType.BodyMedium,
            color = contentColor,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.width(230.dp),
            colors =
                SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = accent,
                    inactiveTrackColor = contentColor.copy(alpha = 0.16f),
                ),
        )
        Text(
            text = valueText,
            style = CinefinType.LabelMedium,
            color = contentColor.copy(alpha = 0.72f),
        )
    }
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    contentColor: Color,
    accent: Color,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option), style = CinefinType.LabelMedium) },
                colors =
                    FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent,
                        labelColor = contentColor.copy(alpha = 0.72f),
                        selectedContainerColor = accent.copy(alpha = 0.16f),
                        selectedLabelColor = contentColor,
                    ),
            )
        }
    }
}

@Composable
private fun ThemeSwatch(
    theme: ReaderTheme,
    selected: Boolean,
    systemDark: Boolean,
    onSelect: () -> Unit,
) {
    val background = theme.surfaceColor(systemDark)
    val foreground = theme.contentColor(systemDark)
    val accent = theme.accentColor(systemDark)
    Box(
        modifier =
            Modifier.size(78.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(background)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) accent else foreground.copy(alpha = 0.24f),
                    shape = RoundedCornerShape(14.dp),
                )
                .clickable(onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            SwatchLine(foreground, 0.9f)
            SwatchLine(foreground, 0.7f)
            SwatchLine(foreground, 0.8f)
        }
        Text(
            text = theme.label,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
            style = CinefinType.LabelSmall,
            color = foreground,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SwatchLine(color: Color, fraction: Float) {
    Box(
        modifier =
            Modifier.height(3.dp)
                .fillMaxWidth(fraction)
                .clip(CinefinShapes.TwoXs)
                .background(color.copy(alpha = 0.55f))
    )
}
