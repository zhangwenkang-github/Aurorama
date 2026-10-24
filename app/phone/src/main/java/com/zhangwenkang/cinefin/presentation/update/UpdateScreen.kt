package com.zhangwenkang.cinefin.presentation.update

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.BuildConfig
import com.zhangwenkang.cinefin.R
import com.zhangwenkang.cinefin.core.presentation.components.CinefinPageTopBar
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.presentation.film.components.LumenCardFrame
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding

@Composable
fun UpdateScreen(
    navigateBack: () -> Unit,
    viewModel: UpdateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = LocalCinefinColors.current
    val safePadding = rememberSafePadding(handleStartInsets = false)
    val gutter = rememberPageGutter()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Column(modifier = Modifier.fillMaxSize().background(colors.surface)) {
        CinefinPageTopBar(
            title = stringResource(R.string.update_title),
            onBack = navigateBack,
            modifier = Modifier.padding(start = safePadding.start),
        )
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = safePadding.start + gutter,
                        end = safePadding.end + gutter,
                        top = CinefinSpacing.Space5,
                        bottom = safePadding.bottom + CinefinSpacing.Space8,
                    ),
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
        ) {
            Text(
                text = stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME),
                style = CinefinType.BodyMedium,
                color = colors.onSurfaceVariant,
            )
            LumenCardFrame(modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(CinefinSpacing.Space5),
                    verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
                ) {
                    UpdateStatus(state)
                    if (state.phase == UpdateUiPhase.CHECKING) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                    if (state.phase == UpdateUiPhase.DOWNLOADING) {
                        LinearProgressIndicator(
                            progress = { state.progress ?: 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        UpdateDownloadedBytes(state)
                    }
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                    Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3)) {
                        OutlinedButton(
                            onClick = viewModel::checkForUpdates,
                            enabled = state.canCheck,
                        ) {
                            Text(stringResource(R.string.update_check))
                        }
                        PrimaryAction(state, viewModel)
                    }
                }
            }
            Text(
                text = stringResource(R.string.update_no_install),
                style = CinefinType.BodySmall,
                color = colors.onSurfaceFaint,
            )
        }
    }
}

@Composable
private fun UpdateStatus(state: UpdateUiState) {
    val colors = LocalCinefinColors.current
    val text =
        when (state.phase) {
            UpdateUiPhase.IDLE -> stringResource(R.string.update_idle)
            UpdateUiPhase.CHECKING -> stringResource(R.string.update_checking)
            UpdateUiPhase.UP_TO_DATE -> stringResource(R.string.update_up_to_date)
            UpdateUiPhase.AVAILABLE ->
                stringResource(R.string.update_available, state.availableVersion.orEmpty())
            UpdateUiPhase.DOWNLOADING -> stringResource(R.string.update_downloading)
            UpdateUiPhase.READY_FOR_VERIFICATION -> stringResource(R.string.update_ready)
            UpdateUiPhase.FAILED -> stringResource(R.string.update_failed)
        }
    Text(text = text, style = CinefinType.TitleMedium, color = colors.onSurface)
}

@Composable
private fun UpdateDownloadedBytes(state: UpdateUiState) {
    val context = LocalContext.current
    val downloaded = Formatter.formatShortFileSize(context, state.downloadedBytes)
    val total = Formatter.formatShortFileSize(context, state.totalBytes)
    Text(
        text = stringResource(R.string.update_progress_bytes, downloaded, total),
        style = CinefinType.BodySmall,
        color = LocalCinefinColors.current.onSurfaceVariant,
    )
}

@Composable
private fun PrimaryAction(
    state: UpdateUiState,
    viewModel: UpdateViewModel,
) {
    when (state.primaryAction) {
        UpdatePrimaryAction.NONE -> Unit
        UpdatePrimaryAction.DOWNLOAD ->
            Button(onClick = viewModel::download) { Text(stringResource(R.string.update_download)) }
        UpdatePrimaryAction.CANCEL ->
            Button(onClick = viewModel::cancelDownload) {
                Text(stringResource(R.string.update_cancel))
            }
        UpdatePrimaryAction.RETRY ->
            Button(onClick = viewModel::retryDownload) {
                Text(stringResource(R.string.update_retry_download))
            }
        UpdatePrimaryAction.RETRY_CHECK ->
            Button(onClick = viewModel::checkForUpdates) {
                Text(stringResource(R.string.update_retry_check))
            }
    }
}
