package com.zhangwenkang.cinefin.presentation.theme

import android.os.Build
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.zhangwenkang.cinefin.core.presentation.theme.Spacings

/**
 * Cinefin 主题。
 *
 * 默认固定使用深灰蓝影院配色：无论系统是否开启深色模式，应用都保持深色外观，
 * 以保证海报墙与播放页的观感一致。动态取色默认关闭，希望跟随系统壁纸的用户
 * 可在设置中开启。
 */
@Composable
fun CinefinTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> darkScheme
            else -> lightScheme
        }

    MaterialTheme(colorScheme = colorScheme, typography = Typography, shapes = shapes) {
        CompositionLocalProvider(
            LocalContentColor provides contentColorFor(MaterialTheme.colorScheme.background),
            LocalSpacings provides Spacings,
        ) {
            content()
        }
    }
}
