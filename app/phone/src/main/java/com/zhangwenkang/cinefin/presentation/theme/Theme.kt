package com.zhangwenkang.cinefin.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme as CoreCinefinTheme
import com.zhangwenkang.cinefin.core.presentation.theme.ContentDomain
import com.zhangwenkang.cinefin.core.presentation.theme.Spacings

/**
 * app:phone 主题入口包装（Typography 归位决策：设计系统核心统一归 `core`，本模块只保留入口与桥接）。
 *
 * 色彩 / 形状 / 排版已由 [CoreCinefinTheme] 统一提供；本包装只负责两件事：
 * 1. 提供存量页面仍在使用的 [LocalSpacings]（历史 6 档间距，后续页面迁移后收敛到 CinefinSpacing）；
 * 2. W4-R3 起排版直接使用设计系统字阶 `CinefinTypography`（旧 `LegacyTypography` 桥接已删除）， 存量
 *    `MaterialTheme.typography` 引用自动收敛到 Prism 字阶。
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
            content = content,
        )
    }
}
