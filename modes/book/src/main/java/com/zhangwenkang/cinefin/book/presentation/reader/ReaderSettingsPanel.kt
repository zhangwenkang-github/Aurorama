package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.components.CinefinFilterChip
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSegmentedControl
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSlider
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSliderColors
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSwitch
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
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
 *
 * 分段控件与 chip 使用 core 的 Prism 组件；纸色 / 护眼主题下媒体色已被 [ReaderSettings.mediaColors]
 * 替换为纸页棕，组件自动取色，面板不再手工传强调色。
 */
@Composable
internal fun ReaderSettingsPanel(
    settings: ReaderSettings,
    systemDark: Boolean,
    showRtl: Boolean,
    onSettingsChange: (ReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = settings.contentColor(systemDark)
    val accent = settings.accentColor(systemDark)

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
        Text(text = "阅读设置", style = CinefinType.TitleMedium, color = contentColor)

        SectionLabel("阅读模式", contentColor)
        CinefinSegmentedControl(
            items = ReaderMode.entries,
            selected = settings.mode,
            onSelect = { mode -> onSettingsChange(settings.copy(mode = mode)) },
            label = { mode -> mode.label },
        )

        if (showRtl) {
            SectionLabel("翻页方向", contentColor)
            RtlSwitchRow(
                checked = settings.rtl,
                contentColor = contentColor,
                accent = accent,
                onAccent = settings.onAccentColor(systemDark),
                onCheckedChange = { onSettingsChange(settings.copy(rtl = it)) },
            )
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
            onSelect = { onSettingsChange(settings.copy(font = it)) },
        )

        SectionLabel("对齐", contentColor)
        ChipRow(
            options = ReaderTextAlign.entries,
            selected = settings.textAlign,
            label = { it.label },
            onSelect = { onSettingsChange(settings.copy(textAlign = it)) },
        )

        SectionLabel("主题", contentColor)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
        ) {
            ReaderTheme.entries.forEach { theme ->
                ThemeSwatch(
                    theme = theme,
                    selected = settings.theme == theme,
                    systemDark = systemDark,
                    onSelect = { onSettingsChange(settings.copy(theme = theme)) },
                )
            }
        }

        // W64：批注范围说明——用户反馈"为什么别的书没有批注"；搜索 / 批注只对 PDF 开放。
        Text(
            text = "搜索与批注仅支持 PDF；EPUB / CBZ 暂不支持。",
            style = CinefinType.LabelSmall,
            color = contentColor.copy(alpha = 0.6f),
        )
    }
}

/**
 * 漫画右起翻页开关（EB-4 / W9-READER）。
 *
 * 只有页序列文档（PDF / CBZ）显示；三档模式行为写在说明行里——分页 / 双栏右到左，滚动模式 仍自上而下（滚动模式没有可翻转的横向轴，见 [isRtlPaging]）。
 */
@Composable
private fun RtlSwitchRow(
    checked: Boolean,
    contentColor: Color,
    accent: Color,
    onAccent: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = "右起翻页（漫画）", style = CinefinType.BodyMedium, color = contentColor)
            Text(
                text = "分页 / 双栏右到左；滚动模式仍自上而下",
                style = CinefinType.LabelSmall,
                color = contentColor.copy(alpha = 0.72f),
            )
        }
        // W44：组件统一走 core；纸色 / 护眼面板自带底色，配色仍按 ReaderSettings 派生（不用 Prism token）。
        CinefinSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = accent,
                    checkedThumbColor = onAccent,
                    checkedBorderColor = Color.Transparent,
                    uncheckedTrackColor = contentColor.copy(alpha = 0.16f),
                    uncheckedThumbColor = contentColor.copy(alpha = 0.72f),
                    uncheckedBorderColor = Color.Transparent,
                ),
        )
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
        // W44：默认 M3 竖条拇指换成 core 统一圆点滑杆；纸色面板配色按 ReaderSettings 派生。
        CinefinSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            // 平板按 §8.14 的 230dp 宽度呈现；手机（≈392dp 宽）自动收缩，数值文本不再被挤出屏幕
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 230.dp),
            colors =
                CinefinSliderColors(
                    activeTrackColor = accent,
                    inactiveTrackColor = contentColor.copy(alpha = 0.16f),
                    thumbColor = Color.White,
                    glowColor = accent.copy(alpha = 0.35f),
                ),
        )
        Text(
            text = valueText,
            style = CinefinType.LabelMedium,
            color = contentColor.copy(alpha = 0.72f),
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 44.dp),
        )
    }
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            CinefinFilterChip(
                text = label(option),
                selected = option == selected,
                onClick = { onSelect(option) },
                compact = true,
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
                .cinefinClickable(onClick = onSelect),
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
