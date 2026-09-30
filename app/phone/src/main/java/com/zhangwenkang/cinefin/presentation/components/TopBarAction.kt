package com.zhangwenkang.cinefin.presentation.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors

/**
 * 44dp 顶栏图标键（§8.1 Icon / §7.1）：无 ripple，按下 / 悬停由容器状态层表达。
 *
 * 默认着色 `OnSurfaceVariant`（导航类操作），需要强调时显式传 [tint]。
 */
@Composable
fun TopBarAction(
    @DrawableRes icon: Int? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = LocalCinefinColors.current
    Box(
        modifier = modifier.size(44.dp).clip(CinefinShapes.Sm).cinefinClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) {
            content()
        } else if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint = tint ?: colors.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
