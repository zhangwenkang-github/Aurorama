package com.zhangwenkang.cinefin.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.compositionLocalOf
import com.zhangwenkang.cinefin.core.presentation.theme.Spacings

val MaterialTheme.spacings
    get() = Spacings

val LocalSpacings = compositionLocalOf { MaterialTheme.spacings }
