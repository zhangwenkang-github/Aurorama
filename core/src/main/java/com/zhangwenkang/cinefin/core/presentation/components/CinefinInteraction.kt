package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 无 ripple 的可点击修饰符：按下 / 悬停 / 聚焦反馈统一由组件自绘状态层表达（§8.1）， 避免 Material 默认涟漪与「细线 + 无投影」的质感冲突。
 *
 * 页面自绘可点击区域（列表行、缩略图、拖动柄等）同样使用本修饰符，保持全站一致的交互反馈。
 */
@Composable
fun Modifier.cinefinClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return clickable(
        interactionSource = source,
        indication = null,
        enabled = enabled,
        onClickLabel = onClickLabel,
        onClick = onClick,
    )
}

/**
 * 内嵌焦点环：画在布局边界之内，供被 `clip` 裁剪的组件（分段控件、chip）使用。
 *
 * 外环版本见 `CinefinButton`：外环必须排在 `clip` 之前才不会被裁掉，内嵌版本则是 2dp 描边贴边绘制。
 */
internal fun Modifier.cinefinInnerFocusRing(color: Color, cornerRadius: Dp): Modifier = drawBehind {
    if (color.alpha == 0f) return@drawBehind
    val strokePx = 2.dp.toPx()
    val inset = strokePx / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2f, size.height - inset * 2f),
        cornerRadius = CornerRadius(cornerRadius.toPx() - inset),
        style = Stroke(width = strokePx),
    )
}
