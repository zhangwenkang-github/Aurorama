package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion

/** W5-T3 动效 composable 小件（与纯数学分离，[MusicMotionMath] 负责可单测的参数与几何）。 */

/**
 * 全屏播放页下拉收起进度（0–1）的惰性读取口。
 *
 * 下拉拖动与回弹逐帧改值，直接走 CompositionLocal 会整页重组；这里只往下传一个读取函数，调用点放在 `graphicsLayer` / `drawWithContent`
 * 的绘制块里，逐帧变化只触发绘制、不触发重组。
 */
internal val LocalMusicPullProgress = staticCompositionLocalOf<() -> Float> { { 0f } }

/**
 * 播放 / 暂停脉冲（MUSIC-40）：容器 scale 1 → [MusicPlayPulseScale] → 1，一次 `SpringOvershoot` 往返。
 *
 * 只响应「播放状态变化」，首帧不脉冲；全屏大播放键与迷你条播放键共用本函数。动画跑在 [Animatable] 上，连续点按会从当前值重新起步（可中断、无跳变）。「减少动态」开启时不做弹性缩放：播
 * / 停由图标切换与迷你条进度线 alpha 表达。
 */
@Composable
internal fun rememberPlayPulseScale(isPlaying: Boolean): Animatable<Float, AnimationVector1D> {
    val reduceMotion = CinefinMotion.reduceMotion()
    val scale = remember { Animatable(1f) }
    var seenFirstState by remember { mutableStateOf(false) }
    LaunchedEffect(isPlaying) {
        if (!seenFirstState) {
            seenFirstState = true
            return@LaunchedEffect
        }
        if (reduceMotion) return@LaunchedEffect
        scale.animateTo(MusicPlayPulseScale, CinefinMotion.SpringOvershoot)
        scale.animateTo(1f, CinefinMotion.SpringOvershoot)
    }
    // 返回 Animatable：调用方在 graphicsLayer 绘制块里读 `.value`，逐帧脉冲不触发重组
    return scale
}
