package com.zhangwenkang.cinefin.core.presentation.theme

import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/** 内容域：决定当前屏唯一的媒体色（§2.5 三色纪律）。 */
enum class ContentDomain {
    Movie,
    Music,
    Book,

    /** 首页 / 设置等例外页：中性域，媒体色取极光色板（2.0.1 定稿 §2 批①）。 */
    Neutral,
}

/** 当前域媒体色（由 [CinefinTheme] 提供；无主题时回落中性域的极光色板）。 */
val LocalMediaColors = staticCompositionLocalOf { MediaAurora }

/** 当前排版（默认设计系统字阶；`app:phone` 桥接期会传入旧字阶，见 [LegacyTypography]）。 */
val LocalCinefinTypography = staticCompositionLocalOf { CinefinTypography }

/**
 * Cinefin 主题（设计系统核心，位于 `core`）。
 *
 * - 色彩：色板由 [themeId] 从 [CinefinThemes] 注册表取，M3 槽位由 [cinefinColorScheme] 按亮 / 暗生成（域媒体色只进
 *   [LocalMediaColors]， 不再进 M3 强调槽位）；组件层另有 [LocalMediaColors]。
 * - 排版：默认 [CinefinTypography]（v1.0 新字阶）；过渡期宿主可传 [LegacyTypography] 让存量页面零回归。
 * - 形状：[CinefinShapes.M3]（玻璃 32 / Xl 28 / 卡片 20 / 按钮 16 / 海报 14 / 8 / 4）；动效与间距由调用方直接引用 token 对象。
 *
 * @param themeId 主题 id（[CinefinThemes] 的键）；未知 id 回落 aurora。
 * @param dynamicColor 是否用系统动态取色生成 M3 方案（Material You）。开启时 M3 primary / secondary / tertiary
 *   走系统动态色，且 [CinefinColors.aurora1] / [CinefinColors.aurora2] / [CinefinColors.aurora3] 映射为系统动态的
 *   primary / secondary / tertiary （2.0.1 定稿 §2 批①，AUDIT-18），消除「系统色 + 固定极光」混色。
 * @param surfaceBackground 是否铺一层主题底色。播放页控制层叠在视频画面上时必须传 `false`， 否则不透明底会盖住画面（表现为「一显示控制层就全黑」）。
 */
@Composable
fun CinefinTheme(
    domain: ContentDomain = ContentDomain.Neutral,
    darkTheme: Boolean = true,
    themeId: String = CinefinThemes.DEFAULT,
    dynamicColor: Boolean = false,
    surfaceBackground: Boolean = true,
    typography: Typography = CinefinTypography,
    content: @Composable () -> Unit,
) {
    val colors = CinefinThemes.colorsFor(themeId, darkTheme)
    val dynamicScheme =
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            null
        }
    val colorScheme = dynamicScheme ?: cinefinColorScheme(colors, darkTheme)
    val effectiveColors =
        if (dynamicScheme != null) {
            colors.copy(
                aurora1 = dynamicScheme.primary,
                aurora2 = dynamicScheme.secondary,
                aurora3 = dynamicScheme.tertiary,
                onAurora = dynamicScheme.onPrimary,
            )
        } else {
            colors
        }
    val media = mediaColorsFor(domain, effectiveColors)

    CompositionLocalProvider(
        LocalMediaColors provides media,
        LocalCinefinColors provides effectiveColors,
        LocalCinefinTypography provides typography,
        LocalSpacings provides Spacings,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = CinefinShapes.M3,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides contentColorFor(MaterialTheme.colorScheme.background)
            ) {
                if (!surfaceBackground) {
                    content()
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        content()
                    }
                }
            }
        }
    }
}
