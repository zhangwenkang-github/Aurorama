package com.zhangwenkang.cinefin.presentation.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.player.local.subtitle.SubtitleCue
import com.zhangwenkang.cinefin.player.local.subtitle.SubtitleOverlayState

/**
 * 自研字幕渲染层（§1.1）。
 *
 * 贴在 PlayerView 之上、控制层之下：只画字幕，不处理任何触摸。
 * 之所以不用 Media3 的 SubtitleView：这里要同时显示主/次两条字幕、
 * 要能整体做 ±0.1s 时间偏移、还要字号/颜色/背景/描边/位置全可控。
 *
 * 时间同步：每帧读一次播放位置，和 cue 的起止时间比对——字幕切换的精度
 * 与画面刷新同步，比固定 100ms 轮询更准，且只在文本变化时才重组。
 */
@Composable
fun PlayerSubtitleOverlay(
    player: Player,
    state: SubtitleOverlayState,
    modifier: Modifier = Modifier,
) {
    var primaryText by remember { mutableStateOf("") }
    var secondaryText by remember { mutableStateOf("") }

    LaunchedEffect(player, state.primaryCues, state.secondaryCues, state.primaryManaged, state.secondaryManaged, state.delayMs) {
        while (true) {
            withFrameNanos { }
            val positionMs = player.currentPosition
            if (positionMs < 0L) continue
            val nextPrimary =
                if (state.primaryManaged) {
                    visibleText(state.primaryCues, positionMs, state.delayMs)
                } else {
                    ""
                }
            val nextSecondary =
                if (state.secondaryManaged) {
                    visibleText(state.secondaryCues, positionMs, state.delayMs)
                } else {
                    ""
                }
            if (nextPrimary != primaryText) primaryText = nextPrimary
            if (nextSecondary != secondaryText) secondaryText = nextSecondary
        }
    }

    if (primaryText.isEmpty() && secondaryText.isEmpty()) return

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val style = state.style
        val baseSize = maxHeight * 0.042f
        val density = LocalDensity.current
        val primarySize = with(density) { (baseSize * style.textScale).toSp() }
        val secondarySize = primarySize * 0.9f

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier.padding(
                    start = 24.dp,
                    end = 24.dp,
                    bottom = maxHeight * style.bottomFraction,
                )
        ) {
            // 次字幕在主字幕上方，互不重叠
            if (secondaryText.isNotEmpty()) {
                SubtitleLine(
                    text = secondaryText,
                    style = style,
                    fontSize = secondarySize,
                )
            }
            if (primaryText.isNotEmpty()) {
                SubtitleLine(
                    text = primaryText,
                    style = style,
                    fontSize = primarySize,
                )
            }
        }
    }
}

/** 当前时刻应显示的字幕文本（同一时刻多条 cue 按顺序叠成多行） */
private fun visibleText(
    cues: List<SubtitleCue>,
    positionMs: Long,
    delayMs: Long,
): String =
    cues.filter { it.isVisibleAt(positionMs, delayMs) }
        .joinToString(separator = "\n") { it.text }

/**
 * 一行字幕：先描边（黑）再填色，做出「任何画面都读得清」的字幕效果。
 *
 * Compose 的 Text 没有原生描边参数，标准做法是用同一个文本绘制两次：
 * 第一次用 [Stroke] 描边，第二次用填充色盖在上面。
 */
@Composable
private fun SubtitleLine(
    text: String,
    style: SubtitleStyle,
    fontSize: androidx.compose.ui.unit.TextUnit,
) {
    val density = LocalDensity.current
    val fillColor = Color(style.textColor)
    val backgroundColor = Color(style.backgroundColor)
    val strokeWidthPx = with(density) { style.edgeWidthDp.dp.toPx() }
    val textStyle =
        TextStyle(
            fontSize = fontSize,
            lineHeight = fontSize * 1.25f,
            textAlign = TextAlign.Center,
        )

    Box(
        modifier =
            Modifier.padding(vertical = 3.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(backgroundColor)
                .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        if (strokeWidthPx > 0f) {
            Text(
                text = text,
                style =
                    textStyle.copy(
                        color = Color.Black,
                        drawStyle = Stroke(width = strokeWidthPx),
                    ),
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = text,
            style = textStyle.copy(color = fillColor),
            textAlign = TextAlign.Center,
        )
    }
}
