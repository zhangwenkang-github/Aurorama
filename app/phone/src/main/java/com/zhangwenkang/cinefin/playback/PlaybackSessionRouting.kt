package com.zhangwenkang.cinefin.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.zhangwenkang.cinefin.MainActivity
import com.zhangwenkang.cinefin.PlayerActivity

/** MainActivity extra：锁屏 / 通知点击音乐条目时，打开应用内音乐播放界面 （与点迷你条一致——主界面覆盖层，而不是视频播放页）。 */
const val EXTRA_OPEN_MUSIC_NOW_PLAYING = "com.zhangwenkang.cinefin.extra.OPEN_MUSIC_NOW_PLAYING"

/** 会话点击落点（W68）：音频条目 → 音乐播放界面；视频条目 → 视频播放页。 */
internal enum class SessionContentTarget {
    Music,
    Video,
}

/** 按当前媒体类型决定点击落点（纯函数，单测覆盖）。 */
internal fun sessionContentTarget(isMusicItem: Boolean): SessionContentTarget =
    if (isMusicItem) SessionContentTarget.Music else SessionContentTarget.Video

/** PendingIntent 请求码：音乐与视频分开，避免同码复用导致路由错乱（视频 0 与旧路由保持一致）。 */
private const val REQUEST_CODE_MUSIC = 1
private const val REQUEST_CODE_VIDEO = 0

/**
 * 通知 / 锁屏 / 蓝牙 / 车机点击会话控件时的跳转：
 * - 音乐条目 → [MainActivity] + [EXTRA_OPEN_MUSIC_NOW_PLAYING]（导航层展开音乐全屏播放覆盖层）；
 * - 视频条目 → [PlayerActivity]（不带 itemId，页面接管正在跑的会话，不重新拉流）。
 */
internal fun buildSessionActivityPendingIntent(
    context: Context,
    isMusicItem: Boolean,
): PendingIntent {
    val requestCode: Int
    val intent: Intent
    when (sessionContentTarget(isMusicItem)) {
        SessionContentTarget.Music -> {
            requestCode = REQUEST_CODE_MUSIC
            intent =
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_OPEN_MUSIC_NOW_PLAYING, true)
                }
        }
        SessionContentTarget.Video -> {
            requestCode = REQUEST_CODE_VIDEO
            intent =
                Intent(context, PlayerActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
        }
    }
    return PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
