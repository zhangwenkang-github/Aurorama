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

    /** 首页 / 设置等例外页：默认取影视色，可显式并置另两域入口。 */
    Neutral,
}

/** 当前域媒体色（由 [CinefinTheme] 提供）。 */
val LocalMediaColors = staticCompositionLocalOf { MediaFilm }

/** 当前排版（默认设计系统字阶；`app:phone` 桥接期会传入旧字阶，见 [LegacyTypography]）。 */
val LocalCinefinTypography = staticCompositionLocalOf { CinefinTypography }

/**
 * Cinefin Prism 主题（设计系统核心，位于 `core`）。
 *
 * - 色彩：M3 槽位由 [cinefinColorScheme] 按 [domain] 与亮 / 暗生成；组件层另有 [LocalMediaColors]。
 * - 排版：默认 [CinefinTypography]（v1.0 新字阶）；过渡期宿主可传 [LegacyTypography] 让存量页面零回归。
 * - 形状：[CinefinShapes.M3]（28/22/16/12/8）；动效与间距由调用方直接引用 token 对象。
 *
 * @param surfaceBackground 是否铺一层主题底色。播放页控制层叠在视频画面上时必须传 `false`， 否则不透明底会盖住画面（表现为「一显示控制层就全黑」）。
 */
@Composable
fun CinefinTheme(
    domain: ContentDomain = ContentDomain.Neutral,
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    surfaceBackground: Boolean = true,
    typography: Typography = CinefinTypography,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) CinefinColorsDark else CinefinColorsLight
    val media = mediaColorsFor(domain)
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            else -> cinefinColorScheme(domain, darkTheme)
        }

    CompositionLocalProvider(
        LocalMediaColors provides media,
        LocalCinefinColors provides colors,
        LocalCinefinTypography provides typography,
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
