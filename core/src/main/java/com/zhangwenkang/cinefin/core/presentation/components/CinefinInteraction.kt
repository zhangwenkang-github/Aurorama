package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/** 无 ripple 的可点击修饰符：按下 / 悬停 / 聚焦反馈统一由组件自绘状态层表达（§8.1）， 避免 Material 默认涟漪与「细线 + 无投影」的质感冲突。 */
@Composable
internal fun Modifier.cinefinClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        onClickLabel = onClickLabel,
        onClick = onClick,
    )
}
