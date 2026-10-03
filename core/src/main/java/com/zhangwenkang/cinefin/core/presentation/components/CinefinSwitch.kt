package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors

/**
 * Cinefin 统一开关（W44）：全 App 的 `Switch` 一律经这里取色，页面不再各写一份 [SwitchDefaults.colors]。
 *
 * 关闭态拇指用 `onSurfaceVariant` 提亮——M3 默认取 `outline`，在暗底上几乎看不见（用户实测：音乐音效「开启均衡器」关闭态小圆点不明显）； 轨道用
 * `surfaceContainerHigh` 填充 + `onSurfaceFaint` 描边提高轮廓对比；开启态保持当前域强调色；禁用态降透明度但仍可辨。
 *
 * Prism 与 Lumen 两套皮肤都由 [LocalCinefinColors] / [LocalMediaColors] 自动切换（`ProvideLumen` 已同步覆盖这两个
 * CompositionLocal），**不新增任何色值**。
 *
 * @param colors 仅限自带底色的特殊面板覆盖（如阅读器纸色面板），普通页面保持默认。
 */
@Composable
fun CinefinSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = cinefinSwitchColors(),
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
    )
}

/** 全 App 默认开关配色（Prism / Lumen 同源，随主题与域自动切换）。 */
@Composable
fun cinefinSwitchColors(): SwitchColors {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val disabledAlpha = colors.disabledAlpha
    return SwitchDefaults.colors(
        checkedThumbColor = media.onBase,
        checkedTrackColor = media.base,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = colors.onSurfaceVariant,
        uncheckedTrackColor = colors.surfaceContainerHigh,
        uncheckedBorderColor = colors.onSurfaceFaint,
        disabledCheckedThumbColor = colors.onSurfaceFaint,
        disabledCheckedTrackColor = media.base.copy(alpha = disabledAlpha),
        disabledCheckedBorderColor = Color.Transparent,
        disabledUncheckedThumbColor = colors.onSurfaceFaint,
        disabledUncheckedTrackColor = colors.surfaceContainerHigh,
        disabledUncheckedBorderColor = colors.onSurfaceFaint.copy(alpha = 0.55f),
    )
}
