package com.zhangwenkang.cinefin.presentation.setup.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

@Composable
fun LoadingButton(
    text: String,
    onClick: () -> Unit,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val loadingIcon: (@Composable (tint: Color) -> Unit)? =
        if (isLoading) {
            { tint ->
                CircularProgressIndicator(
                    color = tint,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            null
        }
    CinefinButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        variant = CinefinButtonVariant.Filled,
        size = CinefinButtonSize.Large,
        enabled = !isLoading,
        icon = loadingIcon,
    )
}

@Composable
@Preview
private fun LoadingButtonPreview() {
    CinefinTheme { LoadingButton(text = "Connect", onClick = {}, isLoading = true) }
}
