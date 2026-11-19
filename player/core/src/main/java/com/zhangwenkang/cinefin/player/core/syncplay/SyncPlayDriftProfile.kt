package com.zhangwenkang.cinefin.player.core.syncplay

import com.zhangwenkang.cinefin.player.core.playback.PlaybackEngine
import kotlin.math.abs

/**
 * 漂移校正分档参数（W4-T8c，设计定稿写死）。
 *
 * 漂移 = 本地位置 − 组期望位置（[SyncPlayController] 用 `positionTicks + (nowServer − whenAt) × rate` 算期望位置）。
 *
 * - Exo：[ignoreBelowMs] 60 / [seekAboveMs] 400；
 * - mpv：[ignoreBelowMs] 150 / [seekAboveMs] 600（R2 放宽）；
 * - 两档共用：60–400（mpv 150–600）之间用 [slowDownRate]（领先）/ [speedUpRate]（落后）追帧，回到 [restoreRateBelowMs]
 *   以内恢复 [nominalRate]（滞回，防止在阈值上来回抖）。
 */
data class SyncPlayDriftProfile(
    /** 小于该值直接忽略。 */
    val ignoreBelowMs: Long,
    /** 追帧中、漂移回到该值以内就恢复标称倍速。 */
    val restoreRateBelowMs: Long,
    /** 大于该值直接 seek 到期望位置。 */
    val seekAboveMs: Long,
    /** 本地领先（位置超出组）时的追帧倍速。 */
    val slowDownRate: Float = 0.95f,
    /** 本地落后（位置不足组）时的追帧倍速。 */
    val speedUpRate: Float = 1.05f,
    /** 标称倍速。 */
    val nominalRate: Float = 1.0f,
) {
    companion object {
        /** ExoPlayer 档：60 / 30 / 400。 */
        val Exo =
            SyncPlayDriftProfile(ignoreBelowMs = 60, restoreRateBelowMs = 30, seekAboveMs = 400)

        /** mpv 档：150 / 30 / 600。 */
        val Mpv =
            SyncPlayDriftProfile(ignoreBelowMs = 150, restoreRateBelowMs = 30, seekAboveMs = 600)

        /** 按内核标识挑档（[PlaybackEngine.BACKEND_MPV] 走 mpv 档，其余一律 Exo 档）。 */
        fun forBackend(backendName: String): SyncPlayDriftProfile =
            if (backendName == PlaybackEngine.BACKEND_MPV) Mpv else Exo
    }
}

/** 一次漂移判定的结论（[SyncPlayDrift.decide] 的输出，状态机据此驱动播放口）。 */
internal sealed interface SyncPlayDriftAction {
    /** 落在忽略带：不动。 */
    data object Hold : SyncPlayDriftAction

    /** 落在追帧带：设成该倍速。 */
    data class AdjustRate(val rate: Float) : SyncPlayDriftAction

    /** 追帧中已回到滞回带内：恢复标称倍速。 */
    data object RestoreRate : SyncPlayDriftAction

    /** 超出 seek 档：直接定位到该位置。 */
    data class Seek(val positionMs: Long) : SyncPlayDriftAction
}

/** 纯函数版漂移判定：与协程、时钟、播放口都无关，边界值可直接单测。 */
internal object SyncPlayDrift {

    /**
     * @param driftMs 本地位置 − 组期望位置（正 = 领先）。
     * @param appliedRate 当前生效的倍速（用于滞回判断）。
     * @param expectedPositionMs 组期望位置，seek 时直接落在这里。
     */
    fun decide(
        driftMs: Long,
        appliedRate: Float,
        profile: SyncPlayDriftProfile,
        expectedPositionMs: Long,
    ): SyncPlayDriftAction {
        val magnitude = abs(driftMs)
        if (appliedRate != profile.nominalRate && magnitude < profile.restoreRateBelowMs) {
            return SyncPlayDriftAction.RestoreRate
        }
        if (magnitude < profile.ignoreBelowMs) {
            return SyncPlayDriftAction.Hold
        }
        if (magnitude <= profile.seekAboveMs) {
            return SyncPlayDriftAction.AdjustRate(
                if (driftMs > 0) profile.slowDownRate else profile.speedUpRate
            )
        }
        return SyncPlayDriftAction.Seek(expectedPositionMs)
    }
}
