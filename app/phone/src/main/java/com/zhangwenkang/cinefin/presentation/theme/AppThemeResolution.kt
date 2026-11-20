package com.zhangwenkang.cinefin.presentation.theme

import com.zhangwenkang.cinefin.core.presentation.theme.CinefinThemes

/**
 * App 主题呈现：偏好里的主题 id + 系统明暗 → [CinefinTheme] 的三项入参。
 *
 * 音乐域（W5-T1 返修）与桌面歌词悬浮窗都要跟随 App 全局主题， 主界面（`MainActivity`）是这套解析的唯一来源，本文件即其抽取结果，避免第二套映射。
 */
data class AppThemePresentation(
    /** 已解析的真实色板 id（`system` 已按系统明暗落成 aurora / light；`dynamic` 保留）。 */
    val themeId: String,
    val darkTheme: Boolean,
    val dynamicColor: Boolean,
)

/**
 * 主题解析（纯函数，W6 主界面规则原样抽取）：
 *
 * - 明暗：`light` 固定浅色；`system` / `dynamic` 跟系统明暗；其余（aurora / amoled / lumen / 未知值）固定深色。
 * - 色板：`system` 按系统明暗解析成 aurora / light；`dynamic` 保留 id 让 M3 走系统动态取色。
 * - 动态取色开关：主题是 `dynamic` 或用户开了「跟随系统动态色」都算开启。
 */
internal fun resolveAppThemePresentation(
    themeId: String?,
    systemDark: Boolean,
    dynamicColorsEnabled: Boolean,
): AppThemePresentation {
    val stored = themeId ?: CinefinThemes.DEFAULT
    val darkTheme =
        when (stored) {
            CinefinThemes.LIGHT -> false
            CinefinThemes.SYSTEM,
            CinefinThemes.DYNAMIC -> systemDark
            else -> true
        }
    val resolvedId =
        if (stored == CinefinThemes.SYSTEM) {
            if (systemDark) CinefinThemes.AURORA else CinefinThemes.LIGHT
        } else {
            stored
        }
    return AppThemePresentation(
        themeId = resolvedId,
        darkTheme = darkTheme,
        dynamicColor = resolvedId == CinefinThemes.DYNAMIC || dynamicColorsEnabled,
    )
}
