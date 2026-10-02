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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.music.data.MusicLyricsOverlayController

/**
 * 桌面歌词悬浮窗内容（W23-MUSIC · D 组）。
 *
 * 两行：当前句（实色）+ 下一句（60% 同色）；单击歌词打开设置小面板（颜色 / 字号 / 语言 / 锁定 / 关闭），未锁定时可整窗拖动。手势在同一个 `pointerInput`
 * 里判定"轻点 vs 拖动"， 避免 tap 与 drag 两个检测器互相消费事件。
 */
@Composable
fun MusicLyricsOverlayContent(
    state: MusicLyricsOverlayController.State,
    panelOpen: Boolean,
    onTogglePanel: () -> Unit,
    onDrag: (Offset) -> Unit,
    onCycleTint: () -> Unit,
    onCycleSize: () -> Unit,
    onCycleLanguage: () -> Unit,
    onToggleLock: () -> Unit,
    onClose: () -> Unit,
) {
    val tint = Color(state.tint.argb.toInt())
    val container = Color(0xE60E1116)
    val borderColor = Color(0x1FFFFFFF)
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier =
            Modifier.widthIn(min = 240.dp, max = 560.dp)
                .clip(shape)
                .background(container)
                .border(1.dp, borderColor, shape)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier =
                Modifier.widthIn(min = 200.dp, max = 520.dp)
                    .overlayGesture(
                        locked = state.locked,
                        onTap = onTogglePanel,
                        onDrag = onDrag,
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = state.current ?: state.emptyMessage ?: "暂无歌词",
                fontSize = state.size.currentSp.sp,
                fontWeight = FontWeight.Medium,
                color = tint,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
            Text(
                text = state.next ?: " ",
                fontSize = state.size.nextSp.sp,
                color = tint.copy(alpha = 0.6f),
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
        if (panelOpen) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayChip(text = "颜色 · ${state.tint.label}", onClick = onCycleTint)
                OverlayChip(text = "字号 · ${state.size.label}", onClick = onCycleSize)
                OverlayChip(
                    text = "语言 · ${state.language.label}",
                    onClick = onCycleLanguage,
                    enabled = state.languages.isNotEmpty(),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayChip(
                    text = if (state.locked) "已锁定" else "锁定",
                    onClick = onToggleLock,
                    active = state.locked,
                )
                OverlayChip(text = "关闭", onClick = onClose)
            }
        }
    }
}

@Composable
private fun OverlayChip(
    text: String,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.38f
    Box(
        modifier =
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (active) Color(0x33FFFFFF) else Color(0x14FFFFFF))
                .border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = Color(0xFFF2F5F9).copy(alpha = alpha),
            maxLines = 1,
        )
    }
}

/** 轻点 = 开关设置面板；拖动 = 移动悬浮窗（锁定时只响应轻点）。 */
private fun Modifier.overlayGesture(
    locked: Boolean,
    onTap: () -> Unit,
    onDrag: (Offset) -> Unit,
): Modifier =
    this.then(
        Modifier.pointerInput(locked) {
            awaitEachGesture {
                val down = awaitFirstDown()
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
                if (!dragging) onTap()
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
