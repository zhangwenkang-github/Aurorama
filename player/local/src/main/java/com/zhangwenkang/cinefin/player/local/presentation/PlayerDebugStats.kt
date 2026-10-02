package com.zhangwenkang.cinefin.player.local.presentation

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer
import timber.log.Timber

/**
 * PlayerDebugOverlay（W27）的一份实时快照。
 *
 * 字段全部可空：两个内核能拿到的信息不同，取不到的交给 UI 显示「—」（与 §1.8 信息面板同一约定）。 [decodeStage] 是
 * `PlayerDecodeMode.DecodeStage` 的枚举名，UI 侧映射成本地化文案。
 */
data class PlayerDebugStats(
    /** exoplayer / mpv */
    val backend: String = "",
    val decodeStage: String = "",
    /** mpv `hwdec-current`；ExoPlayer 没有对应字段（留 null，解码方式由 decodeStage 表达） */
    val decoder: String? = null,
    val videoCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    /** 视频码率（bit/s），内核优先 */
    val videoBitrate: Int? = null,
    /** 当前播放位置往前已缓冲的时长（毫秒），取不到时为 null */
    val bufferedMs: Long? = null,
    /** ExoPlayer 的缓冲百分比；mpv 取不到时为 null */
    val bufferedPercent: Int? = null,
    val droppedFrames: Int? = null,
    val renderedFrames: Int? = null,
    val speed: Float = 1f,
    val positionMs: Long = 0L,
    val durationMs: Long = C.TIME_UNSET,
)

/**
 * 读取当前内核的实时调试信息；mpv 分支会走到 native，必须在主线程之外调用。
 *
 * 全部 runCatching：调试面板本身绝不能把播放页带崩。
 */
fun readPlayerDebugStats(player: Player): PlayerDebugStats =
    when (player) {
        is MPVPlayer -> {
            val info = runCatching { player.queryMediaInfo() }.getOrNull()
            val extra = runCatching { player.queryDebugStats() }.getOrNull()
            PlayerDebugStats(
                backend = PlayerViewModel.PLAYER_BACKEND_MPV,
                decoder = extra?.hwdec,
                videoCodec = info?.videoCodec,
                width = info?.width,
                height = info?.height,
                frameRate = info?.frameRate,
                videoBitrate = extra?.videoBitrate ?: info?.videoBitrate,
                bufferedMs = extra?.cacheDurationMs,
                droppedFrames = extra?.droppedFrames,
                speed = player.playbackParameters.speed,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration,
            )
        }
        is ExoPlayer -> {
            val info = runCatching { exoPlayerMediaInfo(player.currentTracks) }.getOrNull()
            val counters = runCatching { player.videoDecoderCounters }.getOrNull()
            PlayerDebugStats(
                backend = PlayerViewModel.PLAYER_BACKEND_EXOPLAYER,
                videoCodec = info?.videoCodec,
                width = info?.width,
                height = info?.height,
                frameRate = info?.frameRate,
                videoBitrate = info?.videoBitrate,
                bufferedMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L),
                bufferedPercent = player.bufferedPercentage,
                droppedFrames = counters?.droppedBufferCount,
                renderedFrames = counters?.renderedOutputBufferCount,
                speed = player.playbackParameters.speed,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration,
            )
        }
        else -> {
            Timber.d("PlayerDebugOverlay: 未识别的内核 %s", player::class.java.simpleName)
            PlayerDebugStats(backend = player::class.java.simpleName)
        }
    }
