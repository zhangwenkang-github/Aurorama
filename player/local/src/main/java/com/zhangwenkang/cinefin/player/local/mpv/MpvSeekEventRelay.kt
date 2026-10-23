package com.zhangwenkang.cinefin.player.local.mpv

sealed interface MpvSeekEvent {
    data object SeekAccepted : MpvSeekEvent

    data class PlaybackRestarted(val positionMs: Long) : MpvSeekEvent
}

/**
 * Normalizes mpv's single native event stream before it reaches seek consumers.
 *
 * A physical event must cross this boundary exactly once; otherwise callback-only correlation
 * cannot distinguish one engine action from multiple seek requests.
 */
class MpvSeekEventRelay(private val emit: (MpvSeekEvent) -> Unit) {
    fun onSeekEvent() {
        emit(MpvSeekEvent.SeekAccepted)
    }

    fun onPlaybackRestartedEvent(positionMs: Long) {
        emit(MpvSeekEvent.PlaybackRestarted(positionMs))
    }
}
