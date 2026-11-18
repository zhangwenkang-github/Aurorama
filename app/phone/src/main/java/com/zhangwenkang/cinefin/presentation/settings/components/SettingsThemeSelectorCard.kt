package com.zhangwenkang.cinefin.presentation.settings.components

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinThemes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.resolveThemeSelectorSelection
import com.zhangwenkang.cinefin.core.presentation.theme.themeSelectorIds
import com.zhangwenkang.cinefin.settings.R as SettingsR
import com.zhangwenkang.cinefin.settings.domain.models.Preference
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceThemeSelector

/**
 * 外观 → 主题选择器（W1-T5）：一张卡内并列可选主题，每行 = 预览块 + 名称 / 描述 + 选中标记。
 *
 * 行高固定 64dp，行间沿用设置页的发丝线与 16dp 起始缩进；预览块的配色直接取自主题注册表（`CinefinThemes`）， dynamic
 * 行改取系统动态取色，因此换主题后这里读到的就是真实色板。
 */
@Composable
fun SettingsThemeSelectorCard(
    preference: PreferenceThemeSelector,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val darkTheme = isSystemInDarkTheme()
    val selection = resolveThemeSelectorSelection(preference.value, preference.dynamicColorsEnabled)
    val themeIds = themeSelectorIds(Build.VERSION.SDK_INT)

    Column(modifier = modifier.fillMaxWidth()) {
        themeIds.fastForEachIndexed { index, themeId ->
            ThemeSelectorRow(
                themeId = themeId,
                selected = themeId == selection,
                darkTheme = darkTheme,
                enabled = preference.enabled,
                onClick = { onSelect(themeId) },
            )
            if (index < themeIds.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = SettingsRowHorizontalPadding),
                    color = colors.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun ThemeSelectorRow(
    themeId: String,
    selected: Boolean,
    darkTheme: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(64.dp)
                .cinefinClickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = SettingsRowHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemePreviewSwatch(themeId = themeId, darkTheme = darkTheme, selected = selected)
        Spacer(modifier = Modifier.width(CinefinSpacing.Space3))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(themeNameRes(themeId)),
                style = CinefinType.TitleMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(themeDescRes(themeId)),
                style = CinefinType.BodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(modifier = Modifier.width(20.dp), contentAlignment = Alignment.CenterEnd) {
            if (selected) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_check),
                    contentDescription = null,
                    tint = colors.aurora1,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 预览块：72×48，三条等高横带 + 右下角极光小条；选中态用品牌渐变描边，未选中用 1dp 发丝线。 */
@Composable
private fun ThemePreviewSwatch(themeId: String, darkTheme: Boolean, selected: Boolean) {
    val colors = LocalCinefinColors.current
    val swatch = themeSwatchColors(themeId, darkTheme)
    val borderBrush =
        if (selected) {
            Brush.linearGradient(listOf(colors.aurora1, colors.aurora2, colors.aurora3))
        } else {
            SolidColor(colors.outline)
        }

    Box(modifier = Modifier.size(width = 72.dp, height = 48.dp).clip(CinefinShapes.Xs)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f).background(swatch.surface))
            Box(modifier = Modifier.fillMaxWidth().weight(1f).background(swatch.surfaceContainer))
            Box(
                modifier =
                    Modifier.fillMaxWidth().weight(1f).background(swatch.surfaceContainerHigh)
            )
        }
        Box(
            modifier =
                Modifier.align(Alignment.BottomEnd)
                    .padding(CinefinSpacing.Space1)
                    .size(width = 20.dp, height = 4.dp)
                    .clip(CinefinShapes.Full)
                    .background(
                        Brush.linearGradient(listOf(swatch.accent1, swatch.accent2, swatch.accent3))
                    )
        )
        // 描边作为最后一个子项绘制，避免被三条横带的底色盖住。
        Box(
            modifier =
                Modifier.matchParentSize()
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        brush = borderBrush,
                        shape = CinefinShapes.Xs,
                    )
        )
    }
}

private data class ThemeSwatchColors(
    val surface: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val accent1: Color,
    val accent2: Color,
    val accent3: Color,
)

@Composable
private fun themeSwatchColors(themeId: String, darkTheme: Boolean): ThemeSwatchColors {
    if (themeId == CinefinThemes.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        val scheme =
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        return ThemeSwatchColors(
            surface = scheme.surface,
            surfaceContainer = scheme.surfaceContainer,
            surfaceContainerHigh = scheme.surfaceContainerHigh,
            accent1 = scheme.primary,
            accent2 = scheme.secondary,
            accent3 = scheme.tertiary,
        )
    }
    val palette = CinefinThemes.colorsFor(themeId, dark = themeId != CinefinThemes.LIGHT)
    return ThemeSwatchColors(
        surface = palette.surface,
        surfaceContainer = palette.surfaceContainer,
        surfaceContainerHigh = palette.surfaceContainerHigh,
        accent1 = palette.aurora1,
        accent2 = palette.aurora2,
        accent3 = palette.aurora3,
    )
}

@StringRes
private fun themeNameRes(themeId: String): Int =
    when (themeId) {
        CinefinThemes.LIGHT -> SettingsR.string.theme_light
        CinefinThemes.AMOLED -> SettingsR.string.theme_amoled
        CinefinThemes.DYNAMIC -> SettingsR.string.theme_dynamic
        else -> SettingsR.string.theme_aurora
    }

@StringRes
private fun themeDescRes(themeId: String): Int =
    when (themeId) {
        CinefinThemes.LIGHT -> SettingsR.string.theme_light_desc
        CinefinThemes.AMOLED -> SettingsR.string.theme_amoled_desc
        CinefinThemes.DYNAMIC -> SettingsR.string.theme_dynamic_desc
        else -> SettingsR.string.theme_aurora_desc
    }

@Preview
@Composable
private fun SettingsThemeSelectorCardPreview() {
    CinefinTheme {
        SettingsThemeSelectorCard(
            preference =
                PreferenceThemeSelector(
                    nameStringResource = SettingsR.string.theme,
                    backendPreference = Preference("pref_theme", "aurora"),
                    value = "aurora",
                ),
            onSelect = {},
            modifier = Modifier.padding(20.dp),
        )
    }
}
