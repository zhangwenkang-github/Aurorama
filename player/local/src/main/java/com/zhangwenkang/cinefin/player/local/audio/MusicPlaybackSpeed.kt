package com.zhangwenkang.cinefin.player.local.audio

import kotlin.math.abs

/**
 * 音乐倍速档位（W5-T6 · MUSIC-32）。
 *
 * 进程级状态，不落偏好：音乐会话起播强制 1.0（与视频倍速互斥，见 `MusicPlaybackControllerImpl.setQueue`）。倍速走 ExoPlayer 的 Sonic
 * 处理器，音高保持不变。
 */
val MUSIC_PLAYBACK_SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

const val MUSIC_DEFAULT_PLAYBACK_SPEED = 1.0f

/** 只接受面板档位，其余输入回落 1.0（避免脏值把音乐放成 0.3× / 4×）。 */
fun sanitizeMusicPlaybackSpeed(speed: Float): Float =
    MUSIC_PLAYBACK_SPEEDS.firstOrNull { abs(it - speed) < 0.001f } ?: MUSIC_DEFAULT_PLAYBACK_SPEED
