package com.zhangwenkang.cinefin.presentation.theme

import android.os.Build
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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
    /**
     * 是否铺一层主题底色。
     *
     * 播放页的控制层是**叠在视频画面上的浮层**，必须传 false：
     * 否则这个不透明的 Surface 会盖住整个视频，表现为「一显示控制层画面就全黑」。
     */
    surfaceBackground: Boolean = true,
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
            if (!surfaceBackground) {
                content()
                return@CompositionLocalProvider
            }
            // 固定铺一层主题底色：应用外观不跟随系统深浅色，
            // 但不能让系统主题的窗口底色从内容下面透出来。
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                content()
            }
        }
    }
}
