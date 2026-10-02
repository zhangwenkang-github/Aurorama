package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.player.local.audio.MUSIC_EQUALIZER_MAX_GAIN_DB
import com.zhangwenkang.cinefin.player.local.audio.MUSIC_EQUALIZER_MIN_GAIN_DB
import com.zhangwenkang.cinefin.player.local.audio.MusicCrossfadeMath
import com.zhangwenkang.cinefin.player.local.audio.MusicEqualizerFrequencies
import com.zhangwenkang.cinefin.player.local.audio.MusicEqualizerPreset
import com.zhangwenkang.cinefin.player.local.audio.ReplayGainMode
import java.util.Locale

/**
 * 音效面板（W30-MUSIC-FX）：EQ（预设 + 五段自定义）、ReplayGain 三态、淡入淡出档位。
 *
 * 入口 = 全屏播放页功能行「音效」；只作用于音乐会话（播放链按会话门控，视频不受影响）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicEffectsSheet(
    equalizerEnabled: Boolean,
    preset: MusicEqualizerPreset,
    bands: List<Float>,
    replayGainMode: ReplayGainMode,
    replayGainLabel: String?,
    crossfadeSeconds: Int,
    onDismiss: () -> Unit,
    onToggleEqualizer: (Boolean) -> Unit,
    onSelectPreset: (MusicEqualizerPreset) -> Unit,
    onPreviewBand: (Int, Float) -> Unit,
    onCommitBands: () -> Unit,
    onSelectReplayGain: (ReplayGainMode) -> Unit,
    onSelectCrossfade: (Int) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp)
        ) {
            Text(
                text = "音效",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            )
            Text(
                text = "仅作用于音乐播放；视频播放不受影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))

            // ---------- 均衡器 ----------
            SectionTitle("均衡器")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "开启均衡器", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (equalizerEnabled) preset.label else "关闭（原声）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = equalizerEnabled, onCheckedChange = onToggleEqualizer)
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(MusicEqualizerPreset.entries.size) { index ->
                    val item = MusicEqualizerPreset.entries[index]
                    FilterChip(
                        selected = preset == item,
                        onClick = { onSelectPreset(item) },
                        label = { Text(item.label) },
                    )
                }
            }
            bands.forEachIndexed { index, gain ->
                EqualizerBandRow(
                    index = index,
                    gainDb = gain,
                    enabled = equalizerEnabled,
                    onPreview = onPreviewBand,
                    onCommit = onCommitBands,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---------- ReplayGain ----------
            SectionTitle("ReplayGain")
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ReplayGainMode.entries.size) { index ->
                    val item = ReplayGainMode.entries[index]
                    FilterChip(
                        selected = replayGainMode == item,
                        onClick = { onSelectReplayGain(item) },
                        label = { Text(item.label) },
                    )
                }
            }
            Text(
                text =
                    replayGainMode.let { mode ->
                        if (mode == ReplayGainMode.OFF) {
                            "读取音频文件内嵌的 ReplayGain 标签；服务器标签缺失时不改变音量。"
                        } else {
                            replayGainLabel ?: "正在读取标签…"
                        }
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ---------- 淡入淡出 ----------
            SectionTitle("淡入淡出")
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(MusicCrossfadeMath.CROSSFADE_OPTIONS.size) { index ->
                    val seconds = MusicCrossfadeMath.CROSSFADE_OPTIONS[index]
                    FilterChip(
                        selected = crossfadeSeconds == seconds,
                        onClick = { onSelectCrossfade(seconds) },
                        label = { Text(if (seconds == 0) "关闭" else "${seconds} 秒") },
                    )
                }
            }
            Text(
                text = "曲尾淡出 + 曲首淡入（无重叠近似），内核仍无缝衔接下一首；默认关闭。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
    )
}

@Composable
private fun EqualizerBandRow(
    index: Int,
    gainDb: Float,
    enabled: Boolean,
    onPreview: (Int, Float) -> Unit,
    onCommit: () -> Unit,
) {
    val frequency = MusicEqualizerFrequencies.getOrNull(index) ?: return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = formatFrequency(frequency), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = String.format(Locale.US, "%+.1f dB", gainDb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = gainDb,
            onValueChange = { value -> onPreview(index, value) },
            onValueChangeFinished = onCommit,
            enabled = enabled,
            valueRange = MUSIC_EQUALIZER_MIN_GAIN_DB..MUSIC_EQUALIZER_MAX_GAIN_DB,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatFrequency(frequency: Float): String =
    if (frequency >= 1000f) {
        String.format(Locale.US, "%.1f kHz", frequency / 1000f)
    } else {
        String.format(Locale.US, "%.0f Hz", frequency)
    }
