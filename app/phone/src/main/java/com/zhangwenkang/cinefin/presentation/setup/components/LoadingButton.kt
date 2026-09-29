package com.zhangwenkang.cinefin.presentation.setup.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.spacings

@Composable
fun LoadingButton(
    text: String,
    onClick: () -> Unit,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box {
        if (isLoading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                modifier = Modifier.size(24.dp).align(Alignment.CenterStart).offset(x = 8.dp),
            )
        }
        Button(
            onClick = onClick,
            enabled = !isLoading,
            shape = MaterialTheme.shapes.extraLarge,
            contentPadding =
                PaddingValues(
                    horizontal = MaterialTheme.spacings.large,
                    vertical = MaterialTheme.spacings.medium,
                ),
            modifier = modifier,
        ) {
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
@Preview
private fun LoadingButtonPreview() {
    CinefinTheme { LoadingButton(text = "Connect", onClick = {}, isLoading = true) }
}
