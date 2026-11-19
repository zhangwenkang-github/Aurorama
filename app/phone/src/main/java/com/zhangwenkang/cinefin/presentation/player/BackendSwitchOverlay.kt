package com.zhangwenkang.cinefin.presentation.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors

/**
 * 引擎原地切换的淡入遮罩 + 非阻断提示条（W4-T3a-2，VIDEO-07/34）。
 *
 * 两条独立轨道：遮罩回答「画面正在换内核」（`scrim @ 90%`，200ms 淡入 / 280ms 淡出，无文案无 spinner）； 提示条回答「换到哪 / 成功 / 失败」，样式复用
 * `PlaybackRecoveryBar` 的玻璃底 + 描边 + `CinefinShapes.Xs`。
 *
 * 与控制层可见性解耦：控制层隐藏时遮罩与提示条照常出现；全程无对话框。
 */
@Composable
internal fun BackendSwitchOverlay(
    switching: Boolean,
    notice: BackendSwitchNotice?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val scrimAlpha by
        animateFloatAsState(
            targetValue = if (switching) SWITCH_SCRIM_ALPHA else 0f,
            animationSpec =
                tween(
                    durationMillis = if (switching) CinefinMotion.Fast else CinefinMotion.Player,
                    easing = CinefinMotion.Standard,
                ),
            label = "backend-switch-scrim",
        )
    Box(modifier = modifier.fillMaxSize()) {
        if (scrimAlpha > 0f) {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .background(colors.scrim.copy(alpha = colors.scrim.alpha * scrimAlpha))
            )
        }
        AnimatedVisibility(
            visible = notice != null,
            enter = fadeIn(tween(CinefinMotion.Fast, easing = CinefinMotion.Standard)),
            exit = fadeOut(tween(CinefinMotion.Fast, easing = CinefinMotion.Accelerate)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = NOTICE_BOTTOM_DP.dp),
        ) {
            notice?.let { BackendSwitchNoticeBar(text = it.text) }
        }
    }
}

/** 提示条内容：文本由宿主按当前语言解析好再传进来（Compose 只负责画）。 */
internal data class BackendSwitchNotice(val text: String)

@Composable
private fun BackendSwitchNoticeBar(text: String) {
    val colors = LocalCinefinColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.widthIn(max = 420.dp)
                .clip(CinefinShapes.Xs)
                .background(colors.glass)
                .border(1.dp, colors.glassBorder, CinefinShapes.Xs)
                .padding(
                    horizontal = CinefinSpacing.Space4,
                    vertical = CinefinSpacing.Space3,
                ),
    ) {
        Text(
            text = text,
            color = colors.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 遮罩不透明度（设计定稿 §2：`scrim @ 90%`）。 */
private const val SWITCH_SCRIM_ALPHA = 0.9f

/** 提示条离屏幕底边的距离：避开底栏传输键与进度条，又不进手势命中带。 */
private const val NOTICE_BOTTOM_DP = 96
