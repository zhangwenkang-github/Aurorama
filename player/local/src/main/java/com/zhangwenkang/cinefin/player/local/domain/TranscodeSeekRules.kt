package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.C
import androidx.media3.common.Player
import kotlin.math.roundToLong

/**
 * W73（#7）：转码（HLS）流 seek 的判定规则（纯函数）。
 *
 * 背景（K60 取证）：服务器转码会话不带 `startTimeTicks` 时恒从 0 开始转码，客户端请求目标位置分片要等转码任务从 0 追赶到目标（实测 BUFFERING 15–30
 * s，表现为「无法跳转 / 回片头」）。修复分两层：
 * 1. [isTranscodeStreamUri] 识别转码流；[shouldRestartTranscodeSession] 判断目标是否落在当前转码会话的可用窗口外——是则带着目标位置重开
 *    转码会话（重新请求 PlaybackInfo，服务器从目标开始转码）；
 * 2. [isSeekRequestReady] 判定播放器是否「位置可信、可以立即落点」，未就绪的进度条 seek 排队等就绪后补投，不再被后续的恢复位置覆盖。
 */

/** 目标位置超过「已缓冲末尾」这个余量时认为超出可用窗口，重开转码会话。 */
internal const val TRANSCODE_SEEK_SAFE_AHEAD_MS = 15_000L

/** 目标位置早于本次转码会话起点超过该容差时重开会话（起点之前的分片不在服务器窗口里）。 */
internal const val TRANSCODE_SEEK_SESSION_BACK_TOLERANCE_MS = 5_000L

/** 进度条排队目标的比例饱和边界（0–1）。 */
internal const val PENDING_SEEK_FRACTION_MAX = 1f

/** HLS 转码流地址识别：Jellyfin 转码地址是 `…/master.m3u8?…`；本地 / 直连地址不含 `.m3u8`。 */
internal fun isTranscodeStreamUri(mediaSourceUri: String): Boolean =
    mediaSourceUri.contains(".m3u8", ignoreCase = true)

/**
 * 播放器是否已「可立即落点」（位置可信）。
 *
 * 与 `app/phone` 的 `isGestureSeekReady` 同一套规则：`durationMs >= 0` 用来区分未知时长（Media3 的 `C.TIME_UNSET`
 * 是负数）； `playbackState != STATE_IDLE` 排除「条目已设置但还没 prepare」的窗口；「时长已知但续播位还没生效」时 `currentPosition` 仍是
 * 0，因此要求 `currentPosition > 0` 或已 `STATE_READY`。
 */
internal fun isSeekRequestReady(
    playerAttached: Boolean,
    durationMs: Long,
    playbackState: Int,
    currentPositionMs: Long,
): Boolean =
    playerAttached &&
        durationMs >= 0L &&
        playbackState != Player.STATE_IDLE &&
        (currentPositionMs > 0L || playbackState == Player.STATE_READY)

/**
 * 是否需要带着目标位置重开转码会话。
 *
 * @param isTranscodeStream 当前条目是不是 HLS 转码流（直放 / 本地文件恒 false）
 * @param isExoPlayer 只有 ExoPlayer 内核走「替换媒体项重开会话」；mpv 自行处理 HLS 窗口
 * @param targetMs seek 目标位置（毫秒）
 * @param currentPositionMs 当前播放位置（毫秒），缓冲信息不可用时的兜底基准
 * @param bufferedPositionMs 已缓冲末尾（毫秒）；<= 0 表示未知
 * @param sessionStartMs 本次转码会话的起点（毫秒，= PlayerItem.playbackPosition）
 */
internal fun shouldRestartTranscodeSession(
    isTranscodeStream: Boolean,
    isExoPlayer: Boolean,
    targetMs: Long,
    currentPositionMs: Long,
    bufferedPositionMs: Long,
    sessionStartMs: Long,
    safeAheadMs: Long = TRANSCODE_SEEK_SAFE_AHEAD_MS,
): Boolean {
    if (!isTranscodeStream || !isExoPlayer) return false
    if (targetMs <= 0L) return false
    // 早于本次转码会话起点：起点之前的分片不在服务器已生成窗口里
    if (targetMs < sessionStartMs - TRANSCODE_SEEK_SESSION_BACK_TOLERANCE_MS) return true
    val aheadMs =
        when {
            bufferedPositionMs > 0L -> targetMs - bufferedPositionMs
            currentPositionMs > 0L -> targetMs - currentPositionMs
            else -> 0L
        }
    return aheadMs > safeAheadMs
}

/** 时长未知（进度条还没有有效 duration）时把拖动 / 点击的位置换算成比例，供就绪后按真实时长落点；时长已知返回 null。 */
internal fun pendingSeekFraction(rawFraction: Float, durationMs: Long): Float? =
    if (durationMs > 0L && durationMs != C.TIME_UNSET) {
        null
    } else {
        rawFraction.coerceIn(0f, PENDING_SEEK_FRACTION_MAX)
    }

/** 比例 → 就绪后的绝对目标（毫秒）。 */
internal fun seekTargetFromFraction(fraction: Float, durationMs: Long): Long =
    (fraction.coerceIn(0f, PENDING_SEEK_FRACTION_MAX) * durationMs.coerceAtLeast(0L)).roundToLong()
