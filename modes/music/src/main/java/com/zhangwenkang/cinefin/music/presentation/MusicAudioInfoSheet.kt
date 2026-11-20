package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors

/**
 * 音频输出信息面板（W5-T6 · MUSIC-34）。
 *
 * 入口：长按曲目上下文菜单「音频信息」+ 音效面板底部「输出信息」。 结构（设计定稿三）：源格式 · 采样率 · 位深 · 声道 → 输出格式（Exo `Format` 运行时读数）· 是否转码
 * · 码率； 末行恒显「非 bit-perfect 输出（Android 音频混音）」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicAudioInfoSheet(info: MusicAudioInfo, onDismiss: () -> Unit) {
    val colors = LocalCinefinColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainer,
        contentColor = colors.onSurface,
        dragHandle = {
            Box(
                modifier =
                    Modifier.padding(top = CinefinSpacing.Space3)
                        .size(width = 32.dp, height = 4.dp)
                        .clip(CinefinShapes.TwoXs)
                        .background(colors.onSurfaceVariant.copy(alpha = 0.24f))
            )
        },
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = CinefinSpacing.Space5)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = CinefinSpacing.Space4)
        ) {
            Text(
                text = info.title,
                style = CinefinType.TitleMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
            AudioInfoSectionTitle("源")
            AudioInfoRow(label = "格式", value = info.sourceFormat)
            AudioInfoRow(label = "采样率", value = info.sourceSampleRate)
            AudioInfoRow(label = "位深", value = info.sourceBitDepth)
            AudioInfoRow(label = "声道", value = info.sourceChannels)
            Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
            AudioInfoSectionTitle("输出")
            AudioInfoRow(label = "格式", value = info.outputFormat)
            AudioInfoRow(label = "采样率", value = info.outputSampleRate)
            AudioInfoRow(label = "声道", value = info.outputChannels)
            if (!info.outputKnown) {
                Text(
                    text = "当前曲目未在本机播放，输出格式暂不可读。",
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = CinefinSpacing.Space1),
                )
            }
            Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
            AudioInfoRow(label = "是否转码", value = info.transcode)
            AudioInfoRow(label = "码率", value = info.bitrate)
            Spacer(modifier = Modifier.height(CinefinSpacing.Space4))
            Text(
                text = MUSIC_BIT_PERFECT_NOTE,
                style = CinefinType.BodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AudioInfoSectionTitle(text: String) {
    Text(
        text = text,
        style = CinefinType.TitleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = CinefinSpacing.Space1),
    )
}

@Composable
private fun AudioInfoRow(label: String, value: String) {
    val colors = LocalCinefinColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = CinefinSpacing.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = CinefinType.BodyMedium, color = colors.onSurfaceVariant)
        Spacer(modifier = Modifier.width(CinefinSpacing.Space3))
        Text(
            text = value,
            style = CinefinType.MonoDataSmall,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
