package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.compositionLocalOf

/** 迁移前的 6 档间距桥接（原 `app:phone` 主题入口，W83 并入 core；数值保持不变）。 */
val MaterialTheme.spacings
    get() = Spacings

val LocalSpacings = compositionLocalOf { MaterialTheme.spacings }
