package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * Cinefin 亮色方案：冷调灰蓝，仅在系统强制浅色或用户手动选择时使用。
 */
data object ColorLight {
    val primaryLight = Color(0xFF2A5C86)
    val onPrimaryLight = Color(0xFFFFFFFF)
    val primaryContainerLight = Color(0xFFD3E7FA)
    val onPrimaryContainerLight = Color(0xFF001C33)
    val secondaryLight = Color(0xFF4C5D70)
    val onSecondaryLight = Color(0xFFFFFFFF)
    val secondaryContainerLight = Color(0xFFD7E5F2)
    val onSecondaryContainerLight = Color(0xFF10202E)
    val tertiaryLight = Color(0xFF7A5C1E)
    val onTertiaryLight = Color(0xFFFFFFFF)
    val tertiaryContainerLight = Color(0xFFF7E2B8)
    val onTertiaryContainerLight = Color(0xFF261A00)
    val errorLight = Color(0xFFBA1A1A)
    val onErrorLight = Color(0xFFFFFFFF)
    val errorContainerLight = Color(0xFFFFDAD6)
    val onErrorContainerLight = Color(0xFF410002)
    val backgroundLight = Color(0xFFF7F9FC)
    val onBackgroundLight = Color(0xFF171C22)
    val surfaceLight = Color(0xFFF7F9FC)
    val onSurfaceLight = Color(0xFF171C22)
    val surfaceVariantLight = Color(0xFFDCE3EB)
    val onSurfaceVariantLight = Color(0xFF414B56)
    val outlineLight = Color(0xFF717C88)
    val outlineVariantLight = Color(0xFFC1C9D2)
    val scrimLight = Color(0xFF000000)
    val inverseSurfaceLight = Color(0xFF2B323A)
    val inverseOnSurfaceLight = Color(0xFFEFF2F6)
    val inversePrimaryLight = Color(0xFF8FC2F0)
    val surfaceDimLight = Color(0xFFD5DBE2)
    val surfaceBrightLight = Color(0xFFF7F9FC)
    val surfaceContainerLowestLight = Color(0xFFFFFFFF)
    val surfaceContainerLowLight = Color(0xFFF1F4F8)
    val surfaceContainerLight = Color(0xFFEBEFF4)
    val surfaceContainerHighLight = Color(0xFFE5EAF0)
    val surfaceContainerHighestLight = Color(0xFFDFE5EC)
}

/**
 * Cinefin 暗色方案：深灰蓝影院底色 + 冰蓝主色 + 暖金点缀。
 * 这是应用的默认外观。
 */
data object ColorDark {
    // 主色：银幕冷光（冰蓝）
    val primaryDark = Color(0xFF8FC2F0)
    val onPrimaryDark = Color(0xFF08243C)
    val primaryContainerDark = Color(0xFF1B3A57)
    val onPrimaryContainerDark = Color(0xFFD3E7FA)

    // 次色：雾蓝灰
    val secondaryDark = Color(0xFF9DB2C6)
    val onSecondaryDark = Color(0xFF10202E)
    val secondaryContainerDark = Color(0xFF24384A)
    val onSecondaryContainerDark = Color(0xFFD7E5F2)

    // 第三色：放映机暖金
    val tertiaryDark = Color(0xFFE8C07A)
    val onTertiaryDark = Color(0xFF3A2A08)
    val tertiaryContainerDark = Color(0xFF4E3A12)
    val onTertiaryContainerDark = Color(0xFFF7E2B8)

    val errorDark = Color(0xFFFFB4AB)
    val onErrorDark = Color(0xFF690005)
    val errorContainerDark = Color(0xFF93000A)
    val onErrorContainerDark = Color(0xFFFFDAD6)

    // 背景与表面：深灰蓝分层
    val backgroundDark = Color(0xFF0A0E13)
    val onBackgroundDark = Color(0xFFE4EAF2)
    val surfaceDark = Color(0xFF0F141A)
    val onSurfaceDark = Color(0xFFE4EAF2)
    val surfaceVariantDark = Color(0xFF26323F)
    val onSurfaceVariantDark = Color(0xFFA9B7C6)
    val outlineDark = Color(0xFF55677A)
    val outlineVariantDark = Color(0xFF2A3644)
    val scrimDark = Color(0xFF000000)
    val inverseSurfaceDark = Color(0xFFE4EAF2)
    val inverseOnSurfaceDark = Color(0xFF1A222B)
    val inversePrimaryDark = Color(0xFF2A5C86)
    val surfaceDimDark = Color(0xFF0A0E13)
    val surfaceBrightDark = Color(0xFF33414F)
    val surfaceContainerLowestDark = Color(0xFF070A0E)
    val surfaceContainerLowDark = Color(0xFF131A22)
    val surfaceContainerDark = Color(0xFF172029)
    val surfaceContainerHighDark = Color(0xFF1E2833)
    val surfaceContainerHighestDark = Color(0xFF26323F)
}

val Yellow = Color(0xFFF2C94C)
