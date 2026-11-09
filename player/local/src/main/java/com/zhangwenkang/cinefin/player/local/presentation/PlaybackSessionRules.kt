package com.zhangwenkang.cinefin.player.local.presentation

data class PlaybackSessionKey(
    val itemId: String,
    val sourceGeneration: Long,
    val sessionGeneration: Long,
)

data class PlaybackSessionSnapshot(
    val key: PlaybackSessionKey?,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val isReady: Boolean,
    val enginePositionMs: Long?,
    val bufferedPositionMs: Long?,
    val hasPlayerError: Boolean,
    val hasSeekFailure: Boolean,
    val explicitSeekTargetMs: Long?,
    val persistedResumeMs: Long?,
)

data class PlaybackRetryPosition(
    val positionMs: Long,
    val source: PlaybackRetryPositionSource,
)

data class PlaybackSessionTerminal(
    val id: String,
    val key: PlaybackSessionKey,
    val classification: PlaybackSessionClassification,
    val retryPosition: PlaybackRetryPosition,
    val cause: String? = null,
)

data class PlaybackSessionState(
    val key: PlaybackSessionKey? = null,
    val lastEnginePositionMs: Long? = null,
    val lastBufferedPositionMs: Long? = null,
    val lastProgressAtMs: Long? = null,
    val lastWasLoading: Boolean = false,
    val terminal: PlaybackSessionTerminal? = null,
    val retryConsumed: Boolean = false,
)

data class PlaybackSessionProjection(
    val classification: PlaybackSessionClassification,
    val playPauseAction: PlaybackAction,
    val loading: Boolean,
    val terminal: PlaybackSessionTerminal? = null,
)

data class PlaybackSessionUpdate(
    val state: PlaybackSessionState,
    val projection: PlaybackSessionProjection,
)

data class PlaybackRetryIntent(
    val positionMs: Long,
    val source: PlaybackRetryPositionSource,
    val terminalId: String,
)

data class PlaybackRetryUpdate(
    val state: PlaybackSessionState,
    val intent: PlaybackRetryIntent?,
)

enum class PlaybackAction {
    PLAY,
    PAUSE,
}

enum class PlaybackSessionClassification {
    EXPLICIT_PAUSE,
    REAL_PLAYER_ERROR,
    SEEK_FAILED,
    PLAYBACK_PROGRESS_TIMEOUT,
    PROGRESS_UNCONFIRMED,
    BUFFERING_OR_LOADING,
    READY,
}

enum class PlaybackRetryPositionSource {
    ENGINE_POSITION,
    USER_SEEK_TARGET_NEW_REQUEST,
    PERSISTED_RESUME,
    START_ZERO,
}

enum class PlaybackEndSignal {
    END_OF_MEDIA_ITEM,
    OTHER,
}

object PlaybackSessionRules {
    const val STALL_TIMEOUT_MS = 20_000L

    fun advance(
        state: PlaybackSessionState,
        snapshot: PlaybackSessionSnapshot,
        nowElapsedMs: Long,
    ): PlaybackSessionUpdate {
        val currentKey = snapshot.key
        if (currentKey == null) {
            val reset = PlaybackSessionState()
            return PlaybackSessionUpdate(reset, projectionFor(reset, snapshot))
        }

        val keyChanged = state.key != currentKey
        var next =
            if (keyChanged) {
                PlaybackSessionState(
                    key = currentKey,
                    lastEnginePositionMs = snapshot.enginePositionMs,
                    lastBufferedPositionMs = snapshot.bufferedPositionMs,
                    lastProgressAtMs = nowElapsedMs,
                    lastWasLoading = isLoading(snapshot),
                )
            } else {
                state
            }

        if (!snapshot.playWhenReady || snapshot.hasPlayerError || snapshot.hasSeekFailure) {
            next =
                next.copy(
                    lastEnginePositionMs = snapshot.enginePositionMs ?: next.lastEnginePositionMs,
                    lastBufferedPositionMs =
                        snapshot.bufferedPositionMs ?: next.lastBufferedPositionMs,
                    lastProgressAtMs = null,
                    lastWasLoading = isLoading(snapshot),
                    terminal = null,
                    retryConsumed = false,
                )
            return PlaybackSessionUpdate(next, projectionFor(next, snapshot))
        }

        val loading = isLoading(snapshot)
        val engineProgressed =
            snapshot.enginePositionMs != null &&
                next.lastEnginePositionMs != null &&
                snapshot.enginePositionMs != next.lastEnginePositionMs
        val bufferedProgressed =
            snapshot.bufferedPositionMs != null &&
                next.lastBufferedPositionMs != null &&
                snapshot.bufferedPositionMs != next.lastBufferedPositionMs
        val stateProgressed =
            next.lastWasLoading && !loading && (snapshot.isPlaying || snapshot.isReady)
        val progressed = engineProgressed || bufferedProgressed || stateProgressed
        val progressAtMs = if (progressed) nowElapsedMs else next.lastProgressAtMs ?: nowElapsedMs

        next =
            next.copy(
                lastEnginePositionMs = snapshot.enginePositionMs ?: next.lastEnginePositionMs,
                lastBufferedPositionMs = snapshot.bufferedPositionMs ?: next.lastBufferedPositionMs,
                lastProgressAtMs = progressAtMs,
                lastWasLoading = loading,
                terminal = if (progressed) null else next.terminal,
                retryConsumed = if (progressed) false else next.retryConsumed,
            )

        if (next.terminal == null && nowElapsedMs - progressAtMs >= STALL_TIMEOUT_MS) {
            val classification =
                if (snapshot.isBuffering) {
                    PlaybackSessionClassification.PLAYBACK_PROGRESS_TIMEOUT
                } else {
                    PlaybackSessionClassification.PROGRESS_UNCONFIRMED
                }
            next =
                next.copy(
                    terminal =
                        PlaybackSessionTerminal(
                            id =
                                "${currentKey.itemId}:${currentKey.sourceGeneration}:" +
                                    "${currentKey.sessionGeneration}:${classification.name}",
                            key = currentKey,
                            classification = classification,
                            retryPosition = chooseRetryPosition(snapshot),
                            cause = null,
                        ),
                    retryConsumed = false,
                )
        }
        return PlaybackSessionUpdate(next, projectionFor(next, snapshot))
    }

    fun consumeRetry(state: PlaybackSessionState): PlaybackRetryUpdate {
        val terminal = state.terminal ?: return PlaybackRetryUpdate(state, null)
        if (state.retryConsumed) return PlaybackRetryUpdate(state, null)
        return PlaybackRetryUpdate(
            state = state.copy(retryConsumed = true),
            intent =
                PlaybackRetryIntent(
                    positionMs = terminal.retryPosition.positionMs,
                    source = terminal.retryPosition.source,
                    terminalId = terminal.id,
                ),
        )
    }

    fun advancesToNextItem(signal: PlaybackEndSignal): Boolean =
        signal == PlaybackEndSignal.END_OF_MEDIA_ITEM

    private fun projectionFor(
        state: PlaybackSessionState,
        snapshot: PlaybackSessionSnapshot,
    ): PlaybackSessionProjection {
        val action = if (snapshot.playWhenReady) PlaybackAction.PAUSE else PlaybackAction.PLAY
        val visibleTerminal = state.terminal?.takeUnless { state.retryConsumed }
        val classification =
            when {
                !snapshot.playWhenReady -> PlaybackSessionClassification.EXPLICIT_PAUSE
                snapshot.hasPlayerError -> PlaybackSessionClassification.REAL_PLAYER_ERROR
                snapshot.hasSeekFailure -> PlaybackSessionClassification.SEEK_FAILED
                visibleTerminal != null -> visibleTerminal.classification
                isLoading(snapshot) -> PlaybackSessionClassification.BUFFERING_OR_LOADING
                else -> PlaybackSessionClassification.READY
            }
        return PlaybackSessionProjection(
            classification = classification,
            playPauseAction = action,
            loading = classification == PlaybackSessionClassification.BUFFERING_OR_LOADING,
            terminal = visibleTerminal,
        )
    }

    private fun isLoading(snapshot: PlaybackSessionSnapshot): Boolean =
        snapshot.playWhenReady &&
            !snapshot.hasPlayerError &&
            !snapshot.hasSeekFailure &&
            (snapshot.isBuffering || !snapshot.isPlaying)

    private fun chooseRetryPosition(snapshot: PlaybackSessionSnapshot): PlaybackRetryPosition =
        when {
            snapshot.enginePositionMs != null ->
                PlaybackRetryPosition(
                    positionMs = snapshot.enginePositionMs.coerceAtLeast(0L),
                    source = PlaybackRetryPositionSource.ENGINE_POSITION,
                )
            snapshot.explicitSeekTargetMs != null ->
                PlaybackRetryPosition(
                    positionMs = snapshot.explicitSeekTargetMs.coerceAtLeast(0L),
                    source = PlaybackRetryPositionSource.USER_SEEK_TARGET_NEW_REQUEST,
                )
            snapshot.persistedResumeMs != null && snapshot.persistedResumeMs > 0L ->
                PlaybackRetryPosition(
                    positionMs = snapshot.persistedResumeMs,
                    source = PlaybackRetryPositionSource.PERSISTED_RESUME,
                )
            else ->
                PlaybackRetryPosition(
                    positionMs = 0L,
                    source = PlaybackRetryPositionSource.START_ZERO,
                )
        }
}
