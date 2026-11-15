package com.zhangwenkang.cinefin.player.local.domain

internal enum class PlayerExitAction {
    PRESERVE_MUSIC,
    STOP_AND_RELEASE,
    PRESERVE_BACKGROUND_VIDEO,
    PAUSE_FOREGROUND_ONLY,
}

internal fun playerExitAction(
    musicSessionActive: Boolean,
    explicitVideoExit: Boolean,
    backgroundAudioEnabled: Boolean,
): PlayerExitAction =
    when {
        musicSessionActive -> PlayerExitAction.PRESERVE_MUSIC
        explicitVideoExit -> PlayerExitAction.STOP_AND_RELEASE
        backgroundAudioEnabled -> PlayerExitAction.PRESERVE_BACKGROUND_VIDEO
        else -> PlayerExitAction.PAUSE_FOREGROUND_ONLY
    }

internal fun shouldReleasePlayerOnExit(
    musicSessionActive: Boolean,
    explicitVideoExit: Boolean,
    backgroundAudioEnabled: Boolean,
): Boolean =
    playerExitAction(musicSessionActive, explicitVideoExit, backgroundAudioEnabled) in
        setOf(PlayerExitAction.STOP_AND_RELEASE, PlayerExitAction.PAUSE_FOREGROUND_ONLY)

internal fun shouldStopPlaybackServiceOnExit(
    musicSessionActive: Boolean,
    explicitVideoExit: Boolean,
    backgroundAudioEnabled: Boolean,
): Boolean =
    playerExitAction(musicSessionActive, explicitVideoExit, backgroundAudioEnabled) in
        setOf(PlayerExitAction.STOP_AND_RELEASE, PlayerExitAction.PAUSE_FOREGROUND_ONLY)

internal class PlayerExplicitExitController {
    private var currentGeneration = 0L
    private var exited = false

    val generation: Long
        get() = currentGeneration

    val isExited: Boolean
        get() = exited

    fun begin(): Long? {
        if (exited) return null
        exited = true
        currentGeneration += 1L
        return currentGeneration
    }

    fun isCurrent(generation: Long): Boolean = generation == currentGeneration
}

/**
 * W68：播放页真正结束（onCleared）时是否释放进程级共享播放器实例。
 *
 * 两个保护条件（任一满足即**保留**实例）：
 * - 音乐会话活跃：音乐后台播放独立于视频页「后台播放」开关，经锁屏通知误入视频页后退出不能断音乐；
 * - 视频「后台播放」开启：离开播放页后声音继续，实例交给前台服务与通知栏控制。
 *
 * 抽成纯函数便于单测回归：条件写反会直接导致「退出后播放按钮失效」（P1）。
 */
internal fun shouldReleasePlayerOnExit(
    musicSessionActive: Boolean,
    backgroundAudioEnabled: Boolean,
): Boolean =
    shouldReleasePlayerOnExit(
        musicSessionActive = musicSessionActive,
        explicitVideoExit = false,
        backgroundAudioEnabled = backgroundAudioEnabled,
    )
