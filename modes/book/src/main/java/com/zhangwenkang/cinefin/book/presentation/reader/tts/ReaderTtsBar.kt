package com.zhangwenkang.cinefin.book.presentation.reader.tts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSlider
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSwitch
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import java.util.Locale

/** 迷你控制条内容高度（定稿六 §2：高 56dp）。 */
private val READER_TTS_BAR_HEIGHT = 56.dp

/** 条上按钮触控区：56dp 内放 6 个入口，图标 / 文字各 44dp。 */
private val READER_TTS_BUTTON = 44.dp

/** 容器同控制层底片：只上圆角（定稿一 §2 / 定稿六 §2）。 */
private val ReaderTtsBarShape =
    RoundedCornerShape(
        topStart = CinefinShapes.Card.topStart,
        topEnd = CinefinShapes.Card.topEnd,
        bottomEnd = CornerSize(0.dp),
        bottomStart = CornerSize(0.dp),
    )

/**
 * 听书迷你控制条（W6 T9 / READER-32，定稿六 §2）。
 *
 * `⏮ 上一句 | ▶/⏸ | ⏭ 下一句 | 语速 | 定时` + 「原文」（READER-34 的「回到原文」，跳回当前朗读句）。 容器同控制层底片：深色 / OLED = 玻璃，纸色 /
 * 护眼 = 页色 96%；控制层唤出时由调用方隐藏本条。
 */
@Composable
internal fun ReaderTtsBar(
    state: ReaderTtsUiState,
    darkChrome: Boolean,
    chromeColor: Color,
    contentColor: Color,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTimer: () -> Unit,
    onJumpToReading: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val container = if (darkChrome) colors.glass else chromeColor.copy(alpha = 0.96f)
    val border = if (darkChrome) colors.glassBorder else contentColor.copy(alpha = 0.08f)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(ReaderTtsBarShape)
                .background(container)
                .border(1.dp, border, ReaderTtsBarShape)
                .navigationBarsPadding()
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .height(READER_TTS_BAR_HEIGHT)
                    .padding(horizontal = CinefinSpacing.Space1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TtsIconButton(
                iconRes = CoreR.drawable.ic_skip_back,
                label = "上一句",
                contentColor = contentColor,
                onClick = onPrevious,
            )
            TtsPlayButton(state = state, contentColor = contentColor, onClick = onToggle)
            TtsIconButton(
                iconRes = CoreR.drawable.ic_skip_forward,
                label = "下一句",
                contentColor = contentColor,
                onClick = onNext,
            )
            TtsLabelButton(
                text = readerTtsRateLabel(state),
                label = "语速与音色",
                contentColor = contentColor,
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f),
            )
            TtsLabelButton(
                text = readerTtsTimerLabel(state),
                label = "定时关闭",
                contentColor = contentColor,
                onClick = onOpenTimer,
                modifier = Modifier.weight(1f),
            )
            TtsLabelButton(
                text = "原文",
                label = "回到朗读位置",
                contentColor = contentColor,
                onClick = onJumpToReading,
            )
        }
    }
}

/** 播放 / 暂停；准备章节时显示进度圈。 */
@Composable
private fun TtsPlayButton(
    state: ReaderTtsUiState,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier.size(READER_TTS_BUTTON)
                .clip(CircleShape)
                .cinefinClickable(
                    onClickLabel = if (state.playing) "暂停朗读" else "继续朗读",
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (state.playback == TtsPlayback.Preparing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = contentColor,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                painter =
                    painterResource(
                        id = if (state.playing) CoreR.drawable.ic_pause else CoreR.drawable.ic_play
                    ),
                contentDescription = if (state.playing) "暂停" else "播放",
                tint = contentColor,
            )
        }
    }
}

@Composable
private fun TtsIconButton(
    iconRes: Int,
    label: String,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier.size(READER_TTS_BUTTON)
                .clip(CircleShape)
                .cinefinClickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = label,
            tint = contentColor,
        )
    }
}

@Composable
private fun TtsLabelButton(
    text: String,
    label: String,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(READER_TTS_BUTTON)
                .clip(CinefinShapes.Button)
                .cinefinClickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = CinefinType.LabelMedium.copy(fontSize = 12.sp),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 听书的三个弹层（定稿六 §2/§3）：起点二选一、语速与音色、定时关闭。
 *
 * 收在一处只为让阅读页的接线保持一行调用；样式沿用阅读页既有弹层（容器 = 阅读外壳色， 圆角 / 手柄同 `ReaderSettingsPanel` 的 ModalBottomSheet）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderTtsSheets(
    showStart: Boolean,
    showSettings: Boolean,
    showTimer: Boolean,
    state: ReaderTtsUiState,
    voices: List<TtsVoiceOption>,
    selectedVoice: String?,
    tapFollowsReading: Boolean,
    pauseOnTap: Boolean,
    chromeColor: Color,
    contentColor: Color,
    accent: Color,
    onDismissStart: () -> Unit,
    onStart: (ReaderTtsStartPoint) -> Unit,
    onDismissSettings: () -> Unit,
    onRateChange: (Float) -> Unit,
    onVoiceSelected: (String) -> Unit,
    onTapFollowChange: (Boolean) -> Unit,
    onPauseOnTapChange: (Boolean) -> Unit,
    onDismissTimer: () -> Unit,
    onTimerSelected: (TtsTimerOption) -> Unit,
) {
    if (showStart) {
        TtsSheet(
            onDismiss = onDismissStart,
            chromeColor = chromeColor,
            contentColor = contentColor,
        ) {
            Text(text = "听书", style = CinefinType.TitleMedium, color = contentColor)
            Text(
                text = "从当前位置或本章开头开始朗读。",
                style = CinefinType.BodySmall,
                color = contentColor.copy(alpha = 0.72f),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = CinefinSpacing.Space2))
            ReaderTtsStartPoint.entries.forEach { point ->
                TtsSheetRow(
                    label = point.label,
                    contentColor = contentColor,
                    accent = accent,
                    onClick = { onStart(point) },
                )
            }
        }
    }

    if (showSettings) {
        TtsSheet(
            onDismiss = onDismissSettings,
            chromeColor = chromeColor,
            contentColor = contentColor,
        ) {
            Text(text = "语速与音色", style = CinefinType.TitleMedium, color = contentColor)
            // 拖动过程只动本地值：语速变更会重启当前句，必须等松手再提交（避免拖动中连续重读）。
            var draggingRate by remember(state.rate) { mutableFloatStateOf(state.rate) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = CinefinSpacing.Space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "语速",
                    style = CinefinType.BodyMedium,
                    color = contentColor,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = readerTtsRateLabel(draggingRate),
                    style = CinefinType.LabelLarge,
                    color = contentColor,
                )
            }
            CinefinSlider(
                value = draggingRate,
                onValueChange = { draggingRate = it },
                valueRange = ReaderTtsRateRange,
                steps = 14,
                onValueChangeFinished = { onRateChange(draggingRate) },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = CinefinSpacing.Space2))
            Text(
                text = "音色（系统提供）",
                style = CinefinType.LabelMedium,
                color = contentColor.copy(alpha = 0.72f),
            )
            if (voices.isEmpty()) {
                Text(
                    text = "系统未提供中文 / 英文音色，正在使用默认音色。",
                    style = CinefinType.BodySmall,
                    color = contentColor.copy(alpha = 0.72f),
                    modifier = Modifier.padding(vertical = CinefinSpacing.Space2),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 224.dp)) {
                    items(voices, key = { it.name }) { voice ->
                        TtsSheetRow(
                            label = voice.label,
                            contentColor = contentColor,
                            accent = accent,
                            selected = voice.name == selectedVoice,
                            onClick = { onVoiceSelected(voice.name) },
                        )
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = CinefinSpacing.Space2))
            TtsSwitchRow(
                label = "点按正文跳转朗读位置",
                checked = tapFollowsReading,
                contentColor = contentColor,
                onCheckedChange = onTapFollowChange,
            )
            TtsSwitchRow(
                label = "点按正文时暂停朗读",
                checked = pauseOnTap,
                contentColor = contentColor,
                onCheckedChange = onPauseOnTapChange,
            )
        }
    }

    if (showTimer) {
        TtsSheet(
            onDismiss = onDismissTimer,
            chromeColor = chromeColor,
            contentColor = contentColor,
        ) {
            Text(text = "定时关闭", style = CinefinType.TitleMedium, color = contentColor)
            HorizontalDivider(modifier = Modifier.padding(vertical = CinefinSpacing.Space2))
            TtsTimerOption.menu.forEach { option ->
                TtsSheetRow(
                    label = option.label,
                    contentColor = contentColor,
                    accent = accent,
                    selected = option == state.timer,
                    onClick = { onTimerSelected(option) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsSheet(
    onDismiss: () -> Unit,
    chromeColor: Color,
    contentColor: Color,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = chromeColor,
        contentColor = contentColor,
        scrimColor = CinefinTokens.Scrim.copy(alpha = CinefinTokens.ScrimAlpha),
        dragHandle = { TtsSheetHandle(contentColor) },
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = CinefinSpacing.Space6)
                    .padding(bottom = CinefinSpacing.Space6),
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
        ) {
            content()
        }
    }
}

@Composable
private fun TtsSheetHandle(contentColor: Color) {
    Box(
        modifier =
            Modifier.padding(top = CinefinSpacing.Space3)
                .size(width = 32.dp, height = 4.dp)
                .clip(CinefinShapes.TwoXs)
                .background(contentColor.copy(alpha = 0.24f))
    )
}

@Composable
private fun TtsSheetRow(
    label: String,
    contentColor: Color,
    accent: Color,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .cinefinClickable(onClickLabel = label, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = CinefinType.BodyMedium,
            color = contentColor,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Icon(
                painter = painterResource(id = CoreR.drawable.ic_check),
                contentDescription = "已选",
                tint = accent,
            )
        }
    }
}

@Composable
private fun TtsSwitchRow(
    label: String,
    checked: Boolean,
    contentColor: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = CinefinType.BodyMedium,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        CinefinSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 语速标签：`1.0×`。 */
internal fun readerTtsRateLabel(rate: Float): String = String.format(Locale.US, "%.1f×", rate)

internal fun readerTtsRateLabel(state: ReaderTtsUiState): String = readerTtsRateLabel(state.rate)

/** 定时标签：`定时` / `15 分` / `本章止`。 */
internal fun readerTtsTimerLabel(state: ReaderTtsUiState): String {
    if (state.timer == TtsTimerOption.EndOfChapter) return "本章止"
    val remaining = state.timerRemainingMs ?: return "定时"
    val minutes = ((remaining + 59_999L) / 60_000L).coerceAtLeast(1L)
    return "${minutes} 分"
}

/** 控制条进入 / 退出动画时长（与控制层同节奏：200 / 160ms，定稿八）。 */
internal const val READER_TTS_BAR_ENTER_MS = 200

internal const val READER_TTS_BAR_EXIT_MS = 160
