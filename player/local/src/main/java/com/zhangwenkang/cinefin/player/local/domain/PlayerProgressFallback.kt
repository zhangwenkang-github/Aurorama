package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.player.core.domain.models.PlaybackMediaIdentity

internal enum class PlayerProgressFallbackSourceKind {
    NONE,
    REMOTE_HLS,
}

internal data class PlayerProgressFallbackInput(
    val sessionKey: String?,
    val mediaIdentity: PlaybackMediaIdentity?,
    val sourceKind: PlayerProgressFallbackSourceKind,
    val backend: String?,
    val autoFallbackEnabled: Boolean,
    val playWhenReady: Boolean,
    val terminalKind: String?,
    val terminalToken: String?,
    val positionMs: Long,
)

internal data class PlayerProgressFallbackRequest(
    val token: String,
    val sessionKey: String,
    val mediaIdentity: PlaybackMediaIdentity,
    val terminalToken: String,
    val positionMs: Long,
)

internal const val MAX_AUTO_PROGRESS_RECOVERY_ATTEMPTS = 2

internal data class PlayerProgressRecoveryInput(
    val sessionKey: String?,
    val mediaIdentity: PlaybackMediaIdentity?,
    val targetPositionMs: Long,
    val terminalKind: String?,
    val terminalToken: String?,
    val playWhenReady: Boolean,
    val hasPlayerError: Boolean,
    val hasSeekFailure: Boolean,
    val enginePositionMs: Long?,
    val bufferedPositionMs: Long?,
    val isReady: Boolean,
    val isPlaying: Boolean,
    val wasLoading: Boolean,
)

internal data class PlayerProgressRecoveryRequest(
    val token: String,
    val sessionKey: String,
    val mediaIdentity: PlaybackMediaIdentity,
    val targetPositionMs: Long,
    val terminalKind: String,
    val terminalToken: String,
    val generation: Long,
    val attempt: Int,
    val delayMs: Long,
)

internal enum class PlayerProgressRecoveryPhase {
    IDLE,
    RETRYING,
    EXHAUSTED,
    CONFIRMED,
    CANCELLED,
}

internal data class PlayerProgressRecoveryStatus(
    val phase: PlayerProgressRecoveryPhase,
    val terminalKind: String?,
    val attempt: Int,
    val maxAttempts: Int,
)

internal class PlayerProgressRecoveryController {
    private data class State(
        val sessionKey: String,
        val mediaIdentity: PlaybackMediaIdentity,
        val targetPositionMs: Long,
        val terminalKind: String,
        val terminalToken: String,
        val generation: Long,
        val baselineEnginePositionMs: Long?,
        val baselineWasLoading: Boolean,
        val attempts: Int = 0,
        val request: PlayerProgressRecoveryRequest? = null,
        val exhausted: Boolean = false,
        val confirmed: Boolean = false,
    )

    private var state: State? = null
    private var nextGeneration = 0L
    private var nextRequestId = 1L

    fun begin(input: PlayerProgressRecoveryInput): PlayerProgressRecoveryRequest? {
        val identity = input.identity() ?: return null
        val current = state
        if (current != null && !current.matches(identity, input.targetPositionMs)) {
            state = null
        }
        val next =
            state
                ?: State(
                    sessionKey = identity.first,
                    mediaIdentity = identity.second,
                    targetPositionMs = input.targetPositionMs.coerceAtLeast(0L),
                    terminalKind = requireNotNull(input.terminalKind),
                    terminalToken = requireNotNull(input.terminalToken),
                    generation = ++nextGeneration,
                    baselineEnginePositionMs = input.enginePositionMs,
                    baselineWasLoading = input.wasLoading,
                )
        if (
            state != null &&
                (next.terminalToken != input.terminalToken ||
                    next.terminalKind != input.terminalKind)
        ) {
            state = null
            return begin(input)
        }
        if (next.request != null || next.exhausted || next.confirmed) return null
        if (next.attempts >= MAX_AUTO_PROGRESS_RECOVERY_ATTEMPTS) {
            state = next.copy(exhausted = true)
            return null
        }
        val attempt = next.attempts + 1
        val request =
            PlayerProgressRecoveryRequest(
                token = "progress-recovery:${next.generation}:${attempt}:${nextRequestId++}",
                sessionKey = next.sessionKey,
                mediaIdentity = next.mediaIdentity,
                targetPositionMs = next.targetPositionMs,
                terminalKind = next.terminalKind,
                terminalToken = next.terminalToken,
                generation = next.generation,
                attempt = attempt,
                delayMs = RETRY_DELAYS_MS[attempt - 1],
            )
        state = next.copy(attempts = attempt, request = request)
        return request
    }

    fun observe(input: PlayerProgressRecoveryInput): PlayerProgressRecoveryStatus {
        val current = state ?: return idleStatus()
        val identity = input.identity()
        if (
            identity == null ||
                !current.matches(identity, input.targetPositionMs) ||
                input.terminalToken != current.terminalToken ||
                input.terminalKind != current.terminalKind ||
                !input.playWhenReady ||
                input.hasPlayerError ||
                input.hasSeekFailure
        ) {
            state = null
            return status(
                phase = PlayerProgressRecoveryPhase.CANCELLED,
                state = current,
            )
        }
        val engineAdvanced =
            input.enginePositionMs != null &&
                current.baselineEnginePositionMs != null &&
                input.enginePositionMs > current.baselineEnginePositionMs
        val readyRecovered = current.baselineWasLoading && input.isReady && input.isPlaying
        if (engineAdvanced || readyRecovered) {
            state = current.copy(request = null, confirmed = true, exhausted = false)
            return status(
                phase = PlayerProgressRecoveryPhase.CONFIRMED,
                state = current,
            )
        }
        return status(
            phase =
                if (current.exhausted) {
                    PlayerProgressRecoveryPhase.EXHAUSTED
                } else {
                    PlayerProgressRecoveryPhase.RETRYING
                },
            state = current,
        )
    }

    fun nextAttempt(previous: PlayerProgressRecoveryRequest): PlayerProgressRecoveryRequest? {
        val current = state ?: return null
        if (!isCurrent(previous) || current.confirmed) return null
        if (current.attempts >= MAX_AUTO_PROGRESS_RECOVERY_ATTEMPTS) {
            state =
                current.copy(
                    request = null,
                    exhausted = true,
                )
            return null
        }
        val attempt = current.attempts + 1
        val request =
            previous.copy(
                token = "progress-recovery:${current.generation}:${attempt}:${nextRequestId++}",
                attempt = attempt,
                delayMs = RETRY_DELAYS_MS[attempt - 1],
            )
        state =
            current.copy(
                attempts = attempt,
                request = request,
                exhausted = false,
            )
        return request
    }

    fun exhaust(request: PlayerProgressRecoveryRequest) {
        val current = state ?: return
        if (!isCurrent(request)) return
        state =
            current.copy(
                request = null,
                exhausted = current.attempts >= MAX_AUTO_PROGRESS_RECOVERY_ATTEMPTS,
            )
    }

    fun isCurrent(request: PlayerProgressRecoveryRequest): Boolean {
        val current = state ?: return false
        return current.generation == request.generation &&
            current.sessionKey == request.sessionKey &&
            current.mediaIdentity == request.mediaIdentity &&
            current.terminalToken == request.terminalToken &&
            current.targetPositionMs == request.targetPositionMs
    }

    fun status(): PlayerProgressRecoveryStatus? {
        val current = state ?: return null
        if (current.confirmed) return null
        return status(
            phase =
                if (current.exhausted) {
                    PlayerProgressRecoveryPhase.EXHAUSTED
                } else {
                    PlayerProgressRecoveryPhase.RETRYING
                },
            state = current,
        )
    }

    fun cancel() {
        state = null
    }

    private fun PlayerProgressRecoveryInput.identity(): Pair<String, PlaybackMediaIdentity>? {
        val sessionKey = sessionKey?.takeIf(String::isNotBlank) ?: return null
        val mediaIdentity = mediaIdentity ?: return null
        if (mediaIdentity.mediaSourceId.isBlank()) return null
        if (terminalKind !in PROGRESS_TERMINAL_KINDS) return null
        if (terminalToken.isNullOrBlank()) return null
        if (!playWhenReady || hasPlayerError || hasSeekFailure) return null
        return sessionKey to mediaIdentity
    }

    private fun State.matches(
        identity: Pair<String, PlaybackMediaIdentity>,
        targetPositionMs: Long,
    ): Boolean =
        sessionKey == identity.first &&
            mediaIdentity == identity.second &&
            this.targetPositionMs == targetPositionMs.coerceAtLeast(0L)

    private fun idleStatus() =
        PlayerProgressRecoveryStatus(
            phase = PlayerProgressRecoveryPhase.IDLE,
            terminalKind = null,
            attempt = 0,
            maxAttempts = MAX_AUTO_PROGRESS_RECOVERY_ATTEMPTS,
        )

    private fun status(
        phase: PlayerProgressRecoveryPhase,
        state: State,
    ) =
        PlayerProgressRecoveryStatus(
            phase = phase,
            terminalKind = state.terminalKind,
            attempt = state.attempts,
            maxAttempts = MAX_AUTO_PROGRESS_RECOVERY_ATTEMPTS,
        )

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(1_000L, 4_000L)
        val PROGRESS_TERMINAL_KINDS = setOf("PLAYBACK_PROGRESS_TIMEOUT", "PROGRESS_UNCONFIRMED")
    }
}

internal class PlayerProgressFallbackController {
    private data class State(
        val sessionKey: String? = null,
        val attempted: Boolean = false,
        val request: PlayerProgressFallbackRequest? = null,
        val consumed: Boolean = false,
    )

    private var state = State()
    private var nextRequestId = 1L

    fun evaluate(input: PlayerProgressFallbackInput): PlayerProgressFallbackRequest? {
        if (input.sessionKey != state.sessionKey) {
            state = State(sessionKey = input.sessionKey)
        }
        if (!input.playWhenReady || !input.autoFallbackEnabled) {
            if (state.request != null) {
                state = state.copy(attempted = true, request = null, consumed = false)
            }
            return null
        }
        if (state.attempted || !isEligible(input)) return null

        val sessionKey = input.sessionKey ?: return null
        val mediaIdentity = input.mediaIdentity ?: return null
        val terminalToken = input.terminalToken ?: return null
        val request =
            PlayerProgressFallbackRequest(
                token = "progress-fallback:${nextRequestId++}",
                sessionKey = sessionKey,
                mediaIdentity = mediaIdentity,
                terminalToken = terminalToken,
                positionMs = input.positionMs.coerceAtLeast(0L),
            )
        state =
            state.copy(
                attempted = true,
                request = request,
                consumed = false,
            )
        return request
    }

    fun cancelCurrentGeneration() {
        state =
            state.copy(
                attempted = true,
                request = null,
                consumed = false,
            )
    }

    fun consume(
        requestToken: String,
        current: PlayerProgressFallbackInput,
    ): PlayerProgressFallbackRequest? {
        val request = state.request ?: return null
        if (state.consumed || request.token != requestToken) return null
        val stillCurrent =
            request.sessionKey == current.sessionKey &&
                request.mediaIdentity == current.mediaIdentity &&
                request.terminalToken == current.terminalToken &&
                isEligible(current)
        if (!stillCurrent) {
            state = state.copy(attempted = true, request = null, consumed = false)
            return null
        }
        state = state.copy(consumed = true)
        return request
    }

    fun suppressesTerminal(sessionKey: String?): Boolean =
        sessionKey != null && state.request?.sessionKey == sessionKey

    private fun isEligible(input: PlayerProgressFallbackInput): Boolean =
        input.sessionKey != null &&
            input.mediaIdentity != null &&
            input.mediaIdentity.mediaSourceId.isNotBlank() &&
            input.sourceKind == PlayerProgressFallbackSourceKind.REMOTE_HLS &&
            input.backend == BACKEND_MPV &&
            input.autoFallbackEnabled &&
            input.playWhenReady &&
            input.terminalKind in PROGRESS_TERMINAL_KINDS &&
            !input.terminalToken.isNullOrBlank() &&
            input.positionMs >= 0L

    private companion object {
        const val BACKEND_MPV = "mpv"
        val PROGRESS_TERMINAL_KINDS = setOf("PLAYBACK_PROGRESS_TIMEOUT", "PROGRESS_UNCONFIRMED")
    }
}

internal fun playerProgressFallbackSourceKind(
    sourceUri: String?,
    container: String?,
    mimeType: String?,
): PlayerProgressFallbackSourceKind {
    val uri = sourceUri?.trim().orEmpty()
    val remote =
        uri.startsWith("https://", ignoreCase = true) ||
            uri.startsWith("http://", ignoreCase = true)
    if (!remote) return PlayerProgressFallbackSourceKind.NONE

    val path = uri.substringBefore('?').substringBefore('#')
    val hls =
        path.endsWith(".m3u8", ignoreCase = true) ||
            container.equals("hls", ignoreCase = true) ||
            mimeType?.contains("m3u8", ignoreCase = true) == true ||
            mimeType?.contains("mpegurl", ignoreCase = true) == true
    return if (hls) {
        PlayerProgressFallbackSourceKind.REMOTE_HLS
    } else {
        PlayerProgressFallbackSourceKind.NONE
    }
}
