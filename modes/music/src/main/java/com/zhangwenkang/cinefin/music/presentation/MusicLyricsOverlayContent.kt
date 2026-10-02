package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.music.R
import com.zhangwenkang.cinefin.music.data.LyricsOverlayLines
import com.zhangwenkang.cinefin.music.data.MusicLyricsOverlayController
import com.zhangwenkang.cinefin.music.data.overlayChromeVisible
import com.zhangwenkang.cinefin.music.data.overlayDisplayLines
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * 桌面歌词悬浮窗内容（W23-MUSIC · D 组）。
 *
 * 两行：当前句（实色）+ 下一句（60% 同色）；无歌词时回落"歌名 / 歌手"。单击歌词打开图标工具条（颜色 / 字号 / 语言 / 锁定 / 关闭），未锁定时可整窗拖动。手势在同一个
 * `pointerInput` 里判定"轻点 vs 拖动"，避免 tap 与 drag 两个检测器互相消费事件。
 *
 * W24-MUSIC · A 组：无操作后隐藏背景 / 边框（只留歌词文字），触摸 / 拖动恢复；锁定后保持隐藏、单击只唤出设置工具条。 W25-MUSIC：隐藏等待时长由
 * [com.zhangwenkang.cinefin.music.data.LyricsOverlayIdle] 档位决定（2 / 3 / 5 / 10 秒 + 常显）。 工具条按钮全部
 * **只用图标、无边框 / 底色**。
 */
@Composable
fun MusicLyricsOverlayContent(
    state: MusicLyricsOverlayController.State,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onCycleTint: () -> Unit,
    onCycleSize: () -> Unit,
    onCycleLanguage: () -> Unit,
    onCycleIdle: () -> Unit,
    onToggleLock: () -> Unit,
    onClose: () -> Unit,
) {
    val tint = Color(state.tint.argb.toInt())
    val container = Color(0xE60E1116)
    val borderColor = Color(0x1FFFFFFF)
    val shape = RoundedCornerShape(22.dp)
    // 背景 / 边框的显示窗口：面板打开或"最近一次交互后未超时且未锁定"（锁定 = 恒隐藏；常显档位 = 不超时）
    var interacting by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    var interactionSeq by remember { mutableIntStateOf(0) }

    LaunchedEffect(interactionSeq, panelOpen, state.idle) {
        val duration = state.idle.durationMs
        if (duration == null) {
            // 常显：重新进入时把背景 / 边框亮回来并保持
            interacting = true
            return@LaunchedEffect
        }
        delay(duration)
        interacting = false
        panelOpen = false
    }

    fun touch() {
        interacting = true
        interactionSeq++
    }

    val showChrome = overlayChromeVisible(locked = state.locked, interacting = interacting)
    LaunchedEffect(showChrome, state.locked) {
        Timber.i(
            "桌面歌词背景：%s（锁定=%s）",
            if (showChrome) "显示" else "隐藏",
            state.locked,
        )
    }
    val lines =
        overlayDisplayLines(
            LyricsOverlayLines(current = state.current, next = state.next),
            title = state.title,
            artist = state.artist,
        )
    Column(
        modifier =
            Modifier.widthIn(min = 240.dp, max = 560.dp)
                .then(
                    if (showChrome) {
                        Modifier.clip(shape).background(container).border(1.dp, borderColor, shape)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier =
                Modifier.widthIn(min = 200.dp, max = 520.dp)
                    .overlayGesture(
                        locked = state.locked,
                        onInteraction = ::touch,
                        onTap = {
                            touch()
                            panelOpen = !panelOpen
                        },
                        onDrag = onDrag,
                        onDragEnd = onDragEnd,
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // W28-MUSIC：有逐字数据时当前句按词递进高亮（未唱 45% → 已唱实色）；无数据仍是整行实色
            val wordText =
                state.current
                    ?.takeIf { it == lines.first }
                    ?.let { current ->
                        wordHighlightedText(
                            text = current,
                            words = state.currentWords,
                            positionMs = state.positionMs,
                            lineEndMs = null,
                            idleColor = tint.copy(alpha = 0.45f),
                            highlightColor = tint,
                        )
                    }
            if (wordText != null) {
                Text(
                    text = wordText,
                    fontSize = state.size.currentSp.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = lines.first,
                    fontSize = state.size.currentSp.sp,
                    fontWeight = FontWeight.Medium,
                    color = tint,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = lines.second ?: " ",
                fontSize = state.size.nextSp.sp,
                color = tint.copy(alpha = 0.6f),
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
        if (panelOpen) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier =
                    Modifier.clip(RoundedCornerShape(14.dp))
                        .background(if (showChrome) Color.Transparent else Color(0xB30E1116))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayIconButton(
                    iconRes = R.drawable.ic_music_palette,
                    contentDescription = "颜色 · ${state.tint.label}",
                    tint = tint,
                    onClick = {
                        touch()
                        onCycleTint()
                    },
                )
                OverlayIconButton(
                    iconRes = R.drawable.ic_music_text_size,
                    contentDescription = "字号 · ${state.size.label}",
                    onClick = {
                        touch()
                        onCycleSize()
                    },
                )
                OverlayIconButton(
                    iconRes = CoreR.drawable.ic_globe,
                    contentDescription = "语言 · ${state.language.label}",
                    enabled = state.languages.isNotEmpty(),
                    onClick = {
                        touch()
                        onCycleLanguage()
                    },
                )
                OverlayIconButton(
                    iconRes = R.drawable.ic_music_timer,
                    contentDescription = "保持显示 · ${state.idle.label}",
                    onClick = {
                        touch()
                        onCycleIdle()
                    },
                )
                OverlayIconButton(
                    iconRes =
                        if (state.locked) CoreR.drawable.ic_lock else CoreR.drawable.ic_unlock,
                    contentDescription = if (state.locked) "已锁定，单击解锁" else "锁定歌词",
                    tint = if (state.locked) Color(0xFF5CE1D2) else Color(0xFFF2F5F9),
                    onClick = {
                        touch()
                        onToggleLock()
                    },
                )
                OverlayIconButton(
                    iconRes = CoreR.drawable.ic_close,
                    contentDescription = "关闭桌面歌词",
                    onClick = {
                        touch()
                        onClose()
                    },
                )
            }
        }
    }
}

/** 工具条图标按钮：44dp 触控区、只有图标，无文字 / 无边框 / 无底色（W24 · A2）。 */
@Composable
private fun OverlayIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = Color(0xFFF2F5F9),
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.38f
    Box(
        modifier =
            Modifier.size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint.copy(alpha = alpha),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 轻点 = 开关设置工具条；拖动 = 移动悬浮窗（锁定时只响应轻点）。 */
private fun Modifier.overlayGesture(
    locked: Boolean,
    onInteraction: () -> Unit,
    onTap: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
): Modifier =
    this.then(
        Modifier.pointerInput(locked) {
            awaitEachGesture {
                val down = awaitFirstDown()
                onInteraction()
                var total = Offset.Zero
                var dragging = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    val delta = change.positionChange()
                    if (delta != Offset.Zero) {
                        total += delta
                        if (!dragging && total.getDistance() > viewConfiguration.touchSlop) {
                            dragging = true
                        }
                        if (dragging) {
                            if (!locked) onDrag(delta)
                            change.consume()
                        }
                    }
                }
                if (dragging) onDragEnd() else onTap()
            }
        }
    )

/** 桌面歌词权限引导（§8.11 对话框规格）：解释"为什么要悬浮在其他应用上层"， 主行动去系统页授权，取消则不开开关。 */
@Composable
fun LyricsOverlayPermissionDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalCinefinColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainerHighest,
        shape = CinefinShapes.Xl,
        title = {
            Text(text = "开启桌面歌词", style = CinefinType.HeadlineSmall, color = colors.onSurface)
        },
        text = {
            Text(
                text = "桌面歌词需要在其他应用上层显示两行歌词（当前句 + 下一句）。\n" + "请在接下来的系统页面里允许「显示在其他应用上层」，返回后即可生效。",
                style = CinefinType.BodyMedium,
                color = colors.onSurfaceVariant,
            )
        },
        confirmButton = {
            CinefinButton(
                text = "去授权",
                onClick = onConfirm,
                size = CinefinButtonSize.Medium,
                variant = CinefinButtonVariant.Filled,
            )
        },
        dismissButton = {
            CinefinButton(
                text = "取消",
                onClick = onDismiss,
                size = CinefinButtonSize.Medium,
                variant = CinefinButtonVariant.Text,
            )
        },
    )
}
