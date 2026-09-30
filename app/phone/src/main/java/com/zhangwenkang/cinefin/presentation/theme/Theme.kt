package com.zhangwenkang.cinefin.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme as CoreCinefinTheme
import com.zhangwenkang.cinefin.core.presentation.theme.ContentDomain
import com.zhangwenkang.cinefin.core.presentation.theme.LegacyTypography
import com.zhangwenkang.cinefin.core.presentation.theme.Spacings

/**
 * app:phone 主题入口包装（Typography 归位决策：设计系统核心统一归 `core`，本模块只保留入口与桥接）。
 *
 * 色彩 / 形状 / 排版已由 [CoreCinefinTheme] 统一提供；本包装只负责两件事：
 * 1. 提供存量页面仍在使用的 [LocalSpacings]（历史 6 档间距，后续页面迁移后收敛到 CinefinSpacing）；
 * 2. 过渡期把 [LegacyTypography] 交给 MaterialTheme，保证 145 处存量 `MaterialTheme.typography` 引用排版零回归。W3/W4
 *    页面按 Prism 字阶落地后，改回默认 `CinefinTypography` 并删除桥接。
 *
 * @param domain 当前内容域；默认 [ContentDomain.Neutral]（首页 / 设置例外页）。
 * @param surfaceBackground 播放页控制层必须传 `false`（见 core 主题说明）。
 */
@Composable
fun CinefinTheme(
    domain: ContentDomain = ContentDomain.Neutral,
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    surfaceBackground: Boolean = true,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSpacings provides Spacings) {
        CoreCinefinTheme(
            domain = domain,
            darkTheme = darkTheme,
            dynamicColor = dynamicColor,
            surfaceBackground = surfaceBackground,
            typography = LegacyTypography,
            content = content,
        )
    }
}
