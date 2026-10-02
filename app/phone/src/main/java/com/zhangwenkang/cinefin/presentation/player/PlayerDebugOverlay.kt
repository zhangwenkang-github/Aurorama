package com.zhangwenkang.cinefin.presentation.player

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.player.local.R as PlayerR
import com.zhangwenkang.cinefin.player.local.domain.PlayerMediaInfoFormat
import com.zhangwenkang.cinefin.player.local.presentation.PlayerDebugStats
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * PlayerDebugOverlay（W27 §1.17）：长按播放页标题打开的内核 / 解码 / 码率 / 缓冲 / 丢帧实时面板。
 *
 * 数据每秒刷新一次；面板可见期间由控制层宿主保持 Compose 层合成（与片头尾提示条同一条通路）。 关闭键独立可见，不随控制层淡出。
 */
@Composable
internal fun PlayerDebugOverlay(
    statsProvider: suspend () -> PlayerDebugStats,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    var stats by remember { mutableStateOf<PlayerDebugStats?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            stats = runCatching { statsProvider() }.getOrNull() ?: stats
            delay(DEBUG_REFRESH_INTERVAL_MS)
        }
    }

    val shape = CinefinShapes.Md
    Column(
        modifier =
            modifier
                .widthIn(min = 232.dp, max = 320.dp)
                .clip(shape)
                .background(colors.surfaceDim.copy(alpha = 0.92f))
                .border(1.dp, colors.outlineVariant, shape)
                .padding(CinefinSpacing.Space3),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(PlayerR.string.player_debug_title),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            PlayerIconButton(
                iconRes = CoreR.drawable.ic_close,
                contentDescription = stringResource(PlayerR.string.player_debug_close),
                onClick = onClose,
                size = 32.dp,
                iconSize = 18.dp,
                glass = false,
            )
        }
        DebugRow(PlayerR.string.player_debug_kernel, backendLabel(stats))
        DebugRow(PlayerR.string.player_debug_decoder, decoderLabel(stats))
        DebugRow(PlayerR.string.player_controls_info_codec, stats?.videoCodec ?: "—")
        DebugRow(PlayerR.string.player_controls_info_resolution, resolutionLabel(stats))
        DebugRow(PlayerR.string.player_controls_info_bitrate, bitrateLabel(stats))
        DebugRow(PlayerR.string.player_controls_info_frame_rate, frameRateLabel(stats))
        DebugRow(PlayerR.string.player_debug_buffer, bufferLabel(stats))
        DebugRow(PlayerR.string.player_debug_dropped, droppedLabel(stats))
    }
}

private const val DEBUG_REFRESH_INTERVAL_MS = 1_000L

@Composable
private fun DebugRow(
    @StringRes labelRes: Int,
    value: String,
) {
    val colors = LocalCinefinColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun backendLabel(stats: PlayerDebugStats?): String =
    when (stats?.backend) {
        "mpv" -> "mpv"
        "exoplayer" -> "ExoPlayer"
        else -> stats?.backend?.ifBlank { "—" } ?: "—"
    }

@Composable
private fun decoderLabel(stats: PlayerDebugStats?): String {
    val stage = decodeStageLabel(stats?.decodeStage)
    val codec = stats?.videoCodec
    val hardwareDecoder = stats?.decoder
    val parts = buildList {
        stage?.let { add(it) }
        hardwareDecoder?.let { add(it) }
        codec?.let { add(it) }
    }
    return parts.joinToString(" · ").ifBlank { "—" }
}

@Composable
private fun decodeStageLabel(stage: String?): String? =
    when (stage) {
        "EXO_HARDWARE" -> stringResource(PlayerR.string.player_controls_decode_stage_exo_hardware)
        "EXO_SOFTWARE" -> stringResource(PlayerR.string.player_controls_decode_stage_exo_software)
        "MPV_HARDWARE" -> stringResource(PlayerR.string.player_controls_decode_stage_mpv_hardware)
        "MPV_SOFTWARE" -> stringResource(PlayerR.string.player_controls_decode_stage_mpv_software)
        "SERVER_TRANSCODE" -> stringResource(PlayerR.string.player_controls_decode_stage_server)
        else -> null
    }

private fun resolutionLabel(stats: PlayerDebugStats?): String {
    val width = stats?.width ?: return "—"
    val height = stats.height ?: return "—"
    return "$width×$height"
}

private fun bitrateLabel(stats: PlayerDebugStats?): String =
    PlayerMediaInfoFormat.bitrate(stats?.videoBitrate)

private fun frameRateLabel(stats: PlayerDebugStats?): String {
    val frameRate = PlayerMediaInfoFormat.frameRate(stats?.frameRate)
    val speed = stats?.speed ?: 1f
    return "$frameRate · ${formatDebugSpeed(speed)}"
}

private fun bufferLabel(stats: PlayerDebugStats?): String {
    val bufferedMs = stats?.bufferedMs ?: return "—"
    val seconds = String.format(Locale.US, "%.1f s", bufferedMs / 1000.0)
    val percent = stats.bufferedPercent?.let { " · $it%" }.orEmpty()
    return seconds + percent
}

private fun droppedLabel(stats: PlayerDebugStats?): String {
    val dropped = stats?.droppedFrames ?: return "—"
    val rendered = stats.renderedFrames
    return if (rendered != null) "$dropped / $rendered" else dropped.toString()
}

private fun formatDebugSpeed(speed: Float): String {
    val rounded = (speed * 100).toInt() / 100f
    return if (rounded == rounded.toLong().toFloat()) {
        "${rounded.toLong()}×"
    } else {
        "$rounded×"
    }
}
