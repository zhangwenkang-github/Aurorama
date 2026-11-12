package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.Player

/**
 * Transport action exposed by every music surface (in-app controls and MediaSession notification).
 */
enum class MusicTransportAction {
    PLAY,
    PAUSE,
}

/**
 * Single projection used by the music ViewModel state and system notification.
 *
 * The player remains the source of truth; this type only prevents different surfaces from deriving
 * different play/pause semantics from the same player snapshot. Activity follows the user's play
 * intent, not [Player.isPlaying], because Media3 reports the latter as false while a selected track
 * is still preparing or buffering.
 */
data class MusicPlaybackSurfaceState(
    val isPlaybackActive: Boolean,
    val transportAction: MusicTransportAction,
) {
    companion object {
        fun from(player: Player): MusicPlaybackSurfaceState {
            val active =
                player.playWhenReady &&
                    player.mediaItemCount > 0 &&
                    player.playbackState != Player.STATE_ENDED &&
                    player.playerError == null
            return MusicPlaybackSurfaceState(
                isPlaybackActive = active,
                transportAction =
                    if (active) MusicTransportAction.PAUSE else MusicTransportAction.PLAY,
            )
        }
    }
}

/**
 * Dispatches the transport action shared by the in-app play/pause button and system surfaces.
 *
 * The returned snapshot is taken after dispatch so callers can publish one consistent command
 * result.
 */
fun Player.toggleMusicPlaybackTransport(): MusicPlaybackSurfaceState {
    when (MusicPlaybackSurfaceState.from(this).transportAction) {
        MusicTransportAction.PLAY -> play()
        MusicTransportAction.PAUSE -> pause()
    }
    return MusicPlaybackSurfaceState.from(this)
}
