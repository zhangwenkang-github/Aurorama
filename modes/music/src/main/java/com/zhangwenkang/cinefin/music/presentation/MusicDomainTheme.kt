package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.core.presentation.theme.CinefinThemes

/**
 * 音乐域主题参数（W5-T1 返修）。
 *
 * 设计定稿一：音乐页是普通内容页，**直接跟随 App 全局主题四变体**（aurora / light / amoled / dynamic，`system` 按系统明暗解析）。
 * 这三个值由宿主解析后传入（`MainActivity` 的 `resolveAppThemePresentation` 结论），音乐域只消费、不自行映射；
 * `ContentDomain.Music` 仍是媒体色板语义，与本类无关。
 */
data class MusicDomainTheme(
    val themeId: String = CinefinThemes.DEFAULT,
    val darkTheme: Boolean = true,
    val dynamicColor: Boolean = false,
)
