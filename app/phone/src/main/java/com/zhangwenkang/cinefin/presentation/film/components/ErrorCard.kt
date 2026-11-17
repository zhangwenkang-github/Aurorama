package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.presentation.components.ErrorDialog
import com.zhangwenkang.cinefin.presentation.components.TopBarAction

@Composable
fun ErrorCard(
    onShowStacktrace: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    OutlinedCard(
        modifier = modifier,
        colors =
            androidx.compose.material3.CardDefaults.outlinedCardColors(
                containerColor = colors.errorContainer,
                contentColor = colors.onErrorContainer,
            ),
        border = BorderStroke(width = 1.dp, color = colors.error),
    ) {
        Row {
            Spacer(modifier = Modifier.width(CinefinSpacing.Space3))
            Icon(
                painter = painterResource(CoreR.drawable.ic_alert_circle),
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            Spacer(modifier = Modifier.width(CinefinSpacing.Space3))
            Text(
                text = stringResource(CoreR.string.error_loading_data),
                style = CinefinType.BodyMedium,
                modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
            )
            TopBarAction(
                icon = CoreR.drawable.ic_logs,
                onClick = onShowStacktrace,
                contentDescription = stringResource(CoreR.string.show_stacktrace),
            )
            TopBarAction(
                icon = CoreR.drawable.ic_rotate_ccw,
                onClick = onRetryClick,
                contentDescription = stringResource(CoreR.string.retry),
            )
        }
    }
}

@Composable
fun DetailLoadError(
    exception: Throwable,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showErrorDialog by remember { mutableStateOf(false) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        ErrorCard(
            onShowStacktrace = { showErrorDialog = true },
            onRetryClick = {
                showErrorDialog = false
                onRetryClick()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (showErrorDialog) {
        ErrorDialog(exception = exception, onDismissRequest = { showErrorDialog = false })
    }
}

@Preview
@Composable
private fun ErrorCardPreview() {
    CinefinTheme { ErrorCard(onShowStacktrace = {}, onRetryClick = {}) }
}
