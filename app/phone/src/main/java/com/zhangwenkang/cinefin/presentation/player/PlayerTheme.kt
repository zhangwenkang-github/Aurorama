package com.zhangwenkang.cinefin.presentation.player

import com.zhangwenkang.cinefin.core.presentation.theme.CinefinThemes

/**
 * 播放页色板映射（W4-L3 拍板）：播放页保持影院深色基调，只跟随 aurora ↔ amoled。
 *
 * light / dynamic / system（含系统解析出的 light）以及未知值一律落 aurora 深色；amoled 直通。
 */
internal fun resolvePlayerThemeId(themeId: String?): String =
    if (themeId == CinefinThemes.AMOLED) CinefinThemes.AMOLED else CinefinThemes.AURORA
