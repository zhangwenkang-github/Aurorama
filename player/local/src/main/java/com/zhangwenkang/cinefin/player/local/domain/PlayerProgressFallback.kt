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
