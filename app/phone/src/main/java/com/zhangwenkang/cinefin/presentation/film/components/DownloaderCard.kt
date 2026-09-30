package com.zhangwenkang.cinefin.presentation.film.components

import android.app.DownloadManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinCard
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloaderState
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import kotlin.math.roundToInt

@Composable
fun DownloaderCard(state: DownloaderState, onCancelClick: () -> Unit, onRetryClick: () -> Unit) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val animatedProgress by
        animateFloatAsState(
            targetValue = state.progress,
            animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        )

    val textColor =
        when (state.status) {
            DownloadManager.STATUS_PAUSED -> colors.onSurfaceVariant
            DownloadManager.STATUS_FAILED -> colors.error
            else -> colors.onSurface
        }

    val statusText =
        when (state.status) {
            DownloadManager.STATUS_PENDING -> stringResource(CoreR.string.download_pending)
            DownloadManager.STATUS_PAUSED -> stringResource(CoreR.string.download_paused)
            DownloadManager.STATUS_FAILED -> stringResource(CoreR.string.download_failed)
            else -> stringResource(CoreR.string.download_downloading)
        }

    val progressIndicatorColor =
        when (state.status) {
            DownloadManager.STATUS_FAILED -> colors.error
            else -> media.base
        }

    val progressTrackColor = colors.progressTrack

    CinefinCard(contentPadding = PaddingValues(CinefinSpacing.Space4)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = statusText,
                        color = textColor,
                        style = CinefinType.BodyLarge,
                    )
                    Text(
                        text = animatedProgress.times(100).roundToInt().toString() + "%",
                        color = textColor,
                        style = CinefinType.MonoDataSmall,
                    )
                }
                Spacer(Modifier.height(CinefinSpacing.Space2))
                when (state.status) {
                    DownloadManager.STATUS_PENDING -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = progressIndicatorColor,
                            trackColor = progressTrackColor,
                        )
                    }
                }
                Spacer(Modifier.height(CinefinSpacing.Space2))
                if (state.errorText != null) {
                    Text(
                        text = state.errorText!!.asString(),
                        color = colors.error,
                        style = CinefinType.BodyMedium,
                    )
                }
            }
            when (state.status) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING -> {
                    CinefinIconButton(
                        onClick = onCancelClick,
                        icon = { tint ->
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_x),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )
                }
                DownloadManager.STATUS_FAILED -> {
                    CinefinIconButton(
                        onClick = onRetryClick,
                        icon = { tint ->
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
@Preview
private fun DownloaderCardPendingPreview() {
    CinefinTheme {
        DownloaderCard(
            state = DownloaderState(status = DownloadManager.STATUS_PENDING),
            onCancelClick = {},
            onRetryClick = {},
        )
    }
}

@Composable
@Preview
private fun DownloaderCardDownloadingPreview() {
    CinefinTheme {
        DownloaderCard(
            state = DownloaderState(status = DownloadManager.STATUS_RUNNING, progress = 0.5f),
            onCancelClick = {},
            onRetryClick = {},
        )
    }
}

@Composable
@Preview
private fun DownloaderCardFailedPreview() {
    CinefinTheme {
        DownloaderCard(
            state =
                DownloaderState(
                    status = DownloadManager.STATUS_FAILED,
                    progress = 0.5f,
                    errorText = UiText.DynamicString("Not enough storage space"),
                ),
            onCancelClick = {},
            onRetryClick = {},
        )
    }
}
