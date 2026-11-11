package dev.jdtech.mpv.binding

import java.util.concurrent.atomic.AtomicLong

class MpvSourceEventGate {
    private var expectedPlaylistEntryId = 0L
    private var expectedActionGeneration = 0L
    private var nativeGenerationFloor = 0L
    private var sourceEventGenerationFloor = 0L
    private var expectedArmGeneration = 0L
    private val armGeneration = AtomicLong()
    private var activeNativeGeneration = 0L
    private var startObserved = false
    private var loadedObserved = false
    private var closed = false
    private var armed = false

    @Synchronized
    fun arm(
        playlistEntryId: Long,
        actionGeneration: Long,
        afterNativeGeneration: Long,
        afterSourceEventGeneration: Long = 0L,
    ) {
        expectedPlaylistEntryId = playlistEntryId
        expectedActionGeneration = actionGeneration
        nativeGenerationFloor = afterNativeGeneration
        sourceEventGenerationFloor = afterSourceEventGeneration
        expectedArmGeneration = armGeneration.incrementAndGet()
        activeNativeGeneration = 0L
        startObserved = false
        loadedObserved = false
        closed = false
        armed = playlistEntryId > 0L && actionGeneration > 0L
    }

    @Synchronized
    fun disarm() {
        armed = false
        closed = true
    }

    @Synchronized
    fun accept(
        eventId: Int,
        playlistEntryId: Long,
        nativeGeneration: Long,
        sourceEventGeneration: Long = Long.MAX_VALUE,
        armGeneration: Long = Long.MIN_VALUE,
    ): MpvPlaybackEvent? {
        if (
            !armed ||
                closed ||
                playlistEntryId != expectedPlaylistEntryId ||
                nativeGeneration <= nativeGenerationFloor ||
                sourceEventGeneration <= sourceEventGenerationFloor ||
                (armGeneration != Long.MIN_VALUE && armGeneration != expectedArmGeneration)
        ) {
            return null
        }
        when (eventId) {
            MPV_EVENT_START_FILE -> {
                if (startObserved || loadedObserved) return null
                startObserved = true
                activeNativeGeneration = nativeGeneration
            }
            MPV_EVENT_FILE_LOADED -> {
                if (loadedObserved || (startObserved && nativeGeneration != activeNativeGeneration)) {
                    return null
                }
                if (!startObserved) activeNativeGeneration = nativeGeneration
                loadedObserved = true
            }
            MPV_EVENT_END_FILE -> {
                if (
                    (!startObserved && !loadedObserved) ||
                        nativeGeneration != activeNativeGeneration
                ) {
                    return null
                }
                closed = true
            }
            else -> return null
        }
        return MpvPlaybackEvent(
            eventId = eventId,
            playlistEntryId = playlistEntryId,
            nativeGeneration = nativeGeneration,
            actionGeneration = expectedActionGeneration,
        )
    }

    fun currentArmGeneration(): Long = armGeneration.get()

    companion object {
        const val MPV_EVENT_START_FILE = 6
        const val MPV_EVENT_END_FILE = 7
        const val MPV_EVENT_FILE_LOADED = 8
    }
}
