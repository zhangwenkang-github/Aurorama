package com.zhangwenkang.cinefin.player.local.presentation

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.local.domain.SeekEngine
import com.zhangwenkang.cinefin.player.local.domain.SeekMediaIdentity
import com.zhangwenkang.cinefin.player.local.domain.SeekRequestCoordinator
import com.zhangwenkang.cinefin.player.local.mpv.MpvSeekEventListener

/**
 * The production translation boundary from Media3 / mpv seek callbacks to the coordinator.
 *
 * ExoPlayer supplies the new position on SEEK discontinuity. mpv supplies a fresh SEEK without a
 * position, followed by PLAYBACK_RESTART with the checked position. No callback is forwarded twice.
 */
class SeekPlayerEventListener(
    private val coordinator: SeekRequestCoordinator,
    private val engine: () -> SeekEngine,
    private val mediaIdentity: (MediaItem?) -> SeekMediaIdentity?,
    private val readyPositionMs: () -> Long?,
    private val nowElapsedMs: () -> Long,
    private val onStateChanged: () -> Unit = {},
) : Player.Listener, MpvSeekEventListener {
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (engine() != SeekEngine.EXOPLAYER || reason != Player.DISCONTINUITY_REASON_SEEK) {
            return
        }
        if (newPosition.positionMs < 0L) return
        val identity = mediaIdentity(newPosition.mediaItem) ?: return
        val changed =
            coordinator.onSeekAccepted(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = identity,
                positionMs = newPosition.positionMs,
            )
        if (!changed) return
        onStateChanged()
        readyPositionMs()?.let { positionMs ->
            complete(identity = identity, positionMs = positionMs)
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        if (engine() != SeekEngine.EXOPLAYER || state != Player.STATE_READY) return
        val identity = mediaIdentity(null) ?: return
        val positionMs = readyPositionMs() ?: return
        complete(identity = identity, positionMs = positionMs)
    }

    override fun onMpvSeekAccepted() {
        if (engine() != SeekEngine.MPV) return
        val identity = mediaIdentity(null) ?: return
        if (
            coordinator.onSeekAccepted(
                engine = SeekEngine.MPV,
                mediaIdentity = identity,
                positionMs = null,
            )
        ) {
            onStateChanged()
        }
    }

    override fun onMpvPlaybackRestarted(positionMs: Long) {
        if (engine() != SeekEngine.MPV) return
        val identity = mediaIdentity(null) ?: return
        complete(identity = identity, positionMs = positionMs)
    }

    private fun complete(identity: SeekMediaIdentity, positionMs: Long) {
        if (
            coordinator.onPlaybackReady(
                engine = engine(),
                mediaIdentity = identity,
                positionMs = positionMs,
                nowElapsedMs = nowElapsedMs(),
            )
        ) {
            onStateChanged()
        }
    }
}
