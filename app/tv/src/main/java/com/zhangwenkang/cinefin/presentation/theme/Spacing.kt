package com.zhangwenkang.cinefin.presentation.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.tv.material3.MaterialTheme
import com.zhangwenkang.cinefin.core.presentation.theme.Spacings

val MaterialTheme.spacings
    get() = Spacings

val LocalSpacings = compositionLocalOf { MaterialTheme.spacings }
