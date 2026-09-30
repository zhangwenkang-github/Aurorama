package com.zhangwenkang.cinefin.player.local.domain

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.RepeatMode

/**
 * MediaMetadata extras：把音乐条目与视频条目区分开。
 *
 * 音乐与视频复用同一个播放器实例，视频路径直接 `setMediaItems` 会把实例"抢走"； 判断当前媒体项是不是音乐，比只信任
 * [com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder.musicSessionActive]
 * 标志更可靠——标志来不及清零时也不会误暂停 / 误上报视频。
 */
internal const val MUSIC_MEDIA_EXTRA = "cinefin.music"

/**
 * 音乐队列条目 → ExoPlayer 媒体项（纯映射）。
 *
 * 与视频不同：音乐没有字幕 / 章节 / trickplay，只带标题与封面（通知栏 / 锁屏要显示专辑图）。
 */
internal fun PlayerItem.toMusicMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(itemId.toString())
        .setUri(mediaSourceUri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtworkUri(thumbnailUri?.let(Uri::parse))
                .setExtras(Bundle().apply { putBoolean(MUSIC_MEDIA_EXTRA, true) })
                .build()
        )
        .build()

/** 当前媒体项是否是音乐条目（而不是视频共用实例时的视频条目）。 */
internal fun Player.isPlayingMusicItem(): Boolean =
    currentMediaItem?.mediaMetadata?.extras?.getBoolean(MUSIC_MEDIA_EXTRA) == true

/** 音乐重复模式 → ExoPlayer 的 repeatMode（`MusicQueue` 与播放器之间唯一的转换点）。 */
internal fun RepeatMode.toPlayerRepeatMode(): Int =
    when (this) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }
