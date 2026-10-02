package com.zhangwenkang.cinefin.player.local.audio

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 交叉淡化近似方案（W30-MUSIC-FX）的增益曲线（纯函数，可 JVM 单测）。
 *
 * 实现形态 = **曲尾淡出 + 曲首淡入**（单实例、无重叠的"淡接"）：
 * - 真实双实例交叉淡化（旧曲渐弱的同时新曲渐强、两音轨重叠）会破坏单 ExoPlayer 实例 + 单 MediaSession + gapless 的既有链路，
 *   本波不采用（文档已写明分阶段结论）；
 * - 本曲线与 gapless 不冲突：内核仍无缝衔接下一首，音量包络由 `Player.volume` 驱动；
 * - 等功率曲线（sin）避免线性曲线在淡出中途的心理响度塌陷。
 */
object MusicCrossfadeMath {

    /** 面板档位：关闭 / 2 / 4 / 6 秒。 */
    val CROSSFADE_OPTIONS = listOf(0, 2, 4, 6)

    /** 只接受面板档位，其余输入回落为关闭。 */
    fun sanitizeCrossfadeSeconds(seconds: Int): Int =
        CROSSFADE_OPTIONS.firstOrNull { it == seconds } ?: 0

    /** 淡入增益：position=0 → 0，position≥fadeMs → 1；等功率曲线。 */
    fun fadeInGain(positionMs: Long, fadeMs: Long): Float {
        if (fadeMs <= 0L) return 1f
        if (positionMs <= 0L) return 0f
        if (positionMs >= fadeMs) return 1f
        return sin(PI / 2.0 * positionMs.toDouble() / fadeMs).toFloat()
    }

    /** 淡出增益：remaining≥fadeMs → 1，remaining=0 → 0；与淡入同曲线（对称）。 */
    fun fadeOutGain(remainingMs: Long, fadeMs: Long): Float = fadeInGain(remainingMs, fadeMs)

    /**
     * 当前位置的目标音量：取"曲首淡入"与"曲尾淡出"的较小值。
     *
     * 时长未知（≤0）或未开启（fadeMs≤0）时恒为 1；短曲（两侧窗口重叠）取两者较小值，避免中途回弹。
     */
    fun crossfadeVolume(
        positionMs: Long,
        durationMs: Long,
        fadeMs: Long,
    ): Float {
        if (fadeMs <= 0L || durationMs <= 0L) return 1f
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        return min(fadeInGain(positionMs, fadeMs), fadeOutGain(remainingMs, fadeMs))
    }
}
