package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * 极光幕配色：墨底、宣纸白、朱砂印。
 *
 * 取色只有一条规则——**画面即色彩**。海报与剧照承担全部饱和度， 界面自身退进墨、纸、发丝线三种中性色；唯一的强调色朱砂只表示一件事： 接下来要看的内容（继续观看、播放、当前选中项）。
 * 因此这一层不再引入第二种彩色强调色，层级靠明度与发丝线区分。
 */

/** 亮色方案（宣纸）：仅在系统强制浅色或用户手动选择时使用。 */
data object ColorLight {
    val primaryLight = Color(0xFF9C3623)
    val onPrimaryLight = Color(0xFFFFF7F4)
    val primaryContainerLight = Color(0xFFFFDAD2)
    val onPrimaryContainerLight = Color(0xFF3B0A03)
    val secondaryLight = Color(0xFF57534C)
    val onSecondaryLight = Color(0xFFFFFFFF)
    val secondaryContainerLight = Color(0xFFE7E1D8)
    val onSecondaryContainerLight = Color(0xFF1B1C1E)
    val tertiaryLight = Color(0xFF6E5716)
    val onTertiaryLight = Color(0xFFFFFFFF)
    val tertiaryContainerLight = Color(0xFFF2E2B4)
    val onTertiaryContainerLight = Color(0xFF241C00)
    val errorLight = Color(0xFFBA1A1A)
    val onErrorLight = Color(0xFFFFFFFF)
    val errorContainerLight = Color(0xFFFFDAD6)
    val onErrorContainerLight = Color(0xFF410002)
    val backgroundLight = Color(0xFFF7F4EF)
    val onBackgroundLight = Color(0xFF1B1C1E)
    val surfaceLight = Color(0xFFF7F4EF)
    val onSurfaceLight = Color(0xFF1B1C1E)
    val surfaceVariantLight = Color(0xFFE2DDD5)
    val onSurfaceVariantLight = Color(0xFF4B4843)
    val outlineLight = Color(0xFF7C7873)
    val outlineVariantLight = Color(0xFFCEC8BF)
    val scrimLight = Color(0xFF000000)
    val inverseSurfaceLight = Color(0xFF2F3033)
    val inverseOnSurfaceLight = Color(0xFFF2F0EB)
    val inversePrimaryLight = Color(0xFFFFB4A3)
    val surfaceDimLight = Color(0xFFD9D4CC)
    val surfaceBrightLight = Color(0xFFF7F4EF)
    val surfaceContainerLowestLight = Color(0xFFFFFFFF)
    val surfaceContainerLowLight = Color(0xFFF1EDE6)
    val surfaceContainerLight = Color(0xFFEBE7E0)
    val surfaceContainerHighLight = Color(0xFFE5E1DA)
    val surfaceContainerHighestLight = Color(0xFFDFDBD4)
}

/** 暗色方案（默认）：墨底、宣纸白字、朱砂点睛。 */
data object ColorDark {
    // 朱砂印：唯一的强调色
    val primaryDark = Color(0xFFD2553C)
    val onPrimaryDark = Color(0xFF2A0A03)
    val primaryContainerDark = Color(0xFF4A1A10)
    val onPrimaryContainerDark = Color(0xFFFFDAD2)

    // 温灰：次级文字与图标
    val secondaryDark = Color(0xFFB4AFA6)
    val onSecondaryDark = Color(0xFF1E1F21)
    val secondaryContainerDark = Color(0xFF2A2B2E)
    val onSecondaryContainerDark = Color(0xFFE8E2D9)

    // 黄铜：极少量点缀（评分、徽标）
    val tertiaryDark = Color(0xFFC9A227)
    val onTertiaryDark = Color(0xFF2A2100)
    val tertiaryContainerDark = Color(0xFF3D3210)
    val onTertiaryContainerDark = Color(0xFFF3E3B5)

    val errorDark = Color(0xFFE8705F)
    val onErrorDark = Color(0xFF2C0904)
    val errorContainerDark = Color(0xFF5E1F16)
    val onErrorContainerDark = Color(0xFFFFDAD6)

    // 墨：背景与表面只差一点明度，靠发丝线分界
    val backgroundDark = Color(0xFF0B0C0E)
    val onBackgroundDark = Color(0xFFF1EDE6)
    val surfaceDark = Color(0xFF0F1114)
    val onSurfaceDark = Color(0xFFF1EDE6)
    val surfaceVariantDark = Color(0xFF22262A)
    val onSurfaceVariantDark = Color(0xFFA8A49C)
    val outlineDark = Color(0xFF6B6F74)
    val outlineVariantDark = Color(0xFF24282C)
    val scrimDark = Color(0xFF000000)
    val inverseSurfaceDark = Color(0xFFF1EDE6)
    val inverseOnSurfaceDark = Color(0xFF1B1C1E)
    val inversePrimaryDark = Color(0xFF9C3623)
    val surfaceDimDark = Color(0xFF0B0C0E)
    val surfaceBrightDark = Color(0xFF2A2E33)
    val surfaceContainerLowestDark = Color(0xFF08090A)
    val surfaceContainerLowDark = Color(0xFF131518)
    val surfaceContainerDark = Color(0xFF17191D)
    val surfaceContainerHighDark = Color(0xFF1D2024)
    val surfaceContainerHighestDark = Color(0xFF23262A)
}

val Yellow = Color(0xFFC9A227)
