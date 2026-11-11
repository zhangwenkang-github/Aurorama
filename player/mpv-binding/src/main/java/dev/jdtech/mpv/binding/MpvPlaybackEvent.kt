package dev.jdtech.mpv.binding

data class MpvPlaybackEvent(
    val eventId: Int,
    val playlistEntryId: Long,
    val nativeGeneration: Long,
    val actionGeneration: Long,
)

data class MpvSourceCommandResult(
    val accepted: Boolean,
    val playlistEntryId: Long? = null,
)
