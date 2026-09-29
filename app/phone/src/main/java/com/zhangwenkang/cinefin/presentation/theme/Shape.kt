package com.zhangwenkang.cinefin.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 圆角克制：影阁的气质来自留白与发丝线，而不是大圆角。 海报与剧照保留 12dp 的轻微圆角（避免裁切成硬边）， 按钮用全圆角做"印"的意象，其余层级一律小圆角。 */
val shapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(22.dp),
    )
