package com.zhangwenkang.cinefin.player.local.domain

data class SeekMediaIdentity(
    val itemId: String,
    val mediaSourceId: String,
    val sourceFingerprint: String,
)

data class SeekConfirmationPolicy(
    val completionTimeoutMs: Long,
    val positionToleranceMs: Long,
    val minimumTargetDisplayMs: Long,
)

enum class SeekEngineEvidenceKind {
    SEEK_ACCEPTED,
    PLAYBACK_READY,
}

enum class SeekFailureReason {
    REJECTED,
    ENGINE_ERROR,
    SESSION_REBUILD_FAILED,
    TIMEOUT,
}

data class SeekEngineEvidence(
    val requestGeneration: Long,
    val sourceEpoch: Long,
    val mediaIdentity: SeekMediaIdentity,
    val positionMs: Long,
    val kind: SeekEngineEvidenceKind,
)

data class SeekEngineFailure(
    val requestGeneration: Long,
    val sourceEpoch: Long,
    val mediaIdentity: SeekMediaIdentity,
    val reason: SeekFailureReason,
    val retryable: Boolean,
)

sealed interface SeekState {
    data object Idle : SeekState

    data class Seeking(
        val targetMs: Long,
        val requestGeneration: Long,
        val sourceEpoch: Long,
        val mediaIdentity: SeekMediaIdentity,
        val requestedAtMs: Long,
        val policy: SeekConfirmationPolicy,
        val acceptedPositionMs: Long? = null,
    ) : SeekState

    data class Confirmed(
        val targetMs: Long,
        val positionMs: Long,
        val requestGeneration: Long,
        val sourceEpoch: Long,
        val requestedAtMs: Long,
        val confirmedAtMs: Long,
        val policy: SeekConfirmationPolicy,
    ) : SeekState

    data class Failed(
        val targetMs: Long,
        val requestGeneration: Long,
        val sourceEpoch: Long,
        val mediaIdentity: SeekMediaIdentity,
        val reason: SeekFailureReason,
        val retryable: Boolean,
        val policy: SeekConfirmationPolicy,
    ) : SeekState
}

class SeekStateReducer {
    var state: SeekState = SeekState.Idle
        private set

    private var lastRequestGeneration = 0L
    private var currentSourceEpoch = 0L
    private var currentSourceMediaIdentity: SeekMediaIdentity? = null

    val sourceEpoch: Long
        get() = currentSourceEpoch

    val sourceMediaIdentity: SeekMediaIdentity?
        get() = currentSourceMediaIdentity

    /**
     * Source epoch and identity have one owner. Repeated observations of the same source are
     * idempotent, so accepted-before-transition and transition-before-accepted both converge.
     */
    fun observeSource(mediaIdentity: SeekMediaIdentity): Boolean {
        if (currentSourceMediaIdentity == mediaIdentity) return false
        currentSourceEpoch += 1L
        currentSourceMediaIdentity = mediaIdentity
        val seeking = state as? SeekState.Seeking
        if (seeking != null) {
            state =
                seeking.copy(
                    sourceEpoch = currentSourceEpoch,
                    mediaIdentity = mediaIdentity,
                    acceptedPositionMs = null,
                )
        }
        return true
    }

    fun beginRequest(
        targetMs: Long,
        durationMs: Long,
        issuedAtMs: Long,
        policy: SeekConfirmationPolicy,
    ): Long {
        val mediaIdentity =
            requireNotNull(currentSourceMediaIdentity) {
                "A seek request requires an observed media source"
            }
        val generation = nextGeneration()
        state =
            SeekState.Seeking(
                targetMs = clampTarget(targetMs, durationMs),
                requestGeneration = generation,
                sourceEpoch = currentSourceEpoch,
                mediaIdentity = mediaIdentity,
                requestedAtMs = issuedAtMs,
                policy = policy,
            )
        return generation
    }

    fun retryRequest(nowElapsedMs: Long): Long? {
        val failed = state as? SeekState.Failed ?: return null
        if (!failed.retryable) return null
        val mediaIdentity =
            requireNotNull(currentSourceMediaIdentity) {
                "A seek retry requires an observed media source"
            }
        val generation = nextGeneration()
        state =
            SeekState.Seeking(
                targetMs = failed.targetMs,
                requestGeneration = generation,
                sourceEpoch = currentSourceEpoch,
                mediaIdentity = mediaIdentity,
                requestedAtMs = nowElapsedMs,
                policy = failed.policy,
            )
        return generation
    }

    private fun nextGeneration(): Long {
        val current = state
        val currentGeneration =
            when (current) {
                is SeekState.Seeking -> current.requestGeneration
                is SeekState.Confirmed -> current.requestGeneration
                is SeekState.Failed -> current.requestGeneration
                SeekState.Idle -> 0L
            }
        lastRequestGeneration = maxOf(lastRequestGeneration, currentGeneration) + 1L
        return lastRequestGeneration
    }

    fun onAccepted(evidence: SeekEngineEvidence) {
        val seeking = state as? SeekState.Seeking ?: return
        if (
            evidence.requestGeneration != seeking.requestGeneration ||
                evidence.sourceEpoch != seeking.sourceEpoch ||
                evidence.mediaIdentity != seeking.mediaIdentity ||
                evidence.kind != SeekEngineEvidenceKind.SEEK_ACCEPTED
        ) {
            return
        }
        state = seeking.copy(acceptedPositionMs = evidence.positionMs)
    }

    fun onCompletionEvidence(evidence: SeekEngineEvidence, nowElapsedMs: Long) {
        val seeking = state as? SeekState.Seeking ?: return
        if (nowElapsedMs >= seeking.requestedAtMs + seeking.policy.completionTimeoutMs) {
            onTick(nowElapsedMs)
            return
        }
        if (
            seeking.acceptedPositionMs == null ||
                !seeking.matches(evidence) ||
                evidence.kind != SeekEngineEvidenceKind.PLAYBACK_READY ||
                kotlin.math.abs(evidence.positionMs - seeking.targetMs) >
                    seeking.policy.positionToleranceMs
        ) {
            return
        }
        state =
            SeekState.Confirmed(
                targetMs = seeking.targetMs,
                positionMs = evidence.positionMs,
                requestGeneration = seeking.requestGeneration,
                sourceEpoch = seeking.sourceEpoch,
                requestedAtMs = seeking.requestedAtMs,
                confirmedAtMs = nowElapsedMs,
                policy = seeking.policy,
            )
    }

    fun onFailure(failure: SeekEngineFailure, nowElapsedMs: Long) {
        val seeking = state as? SeekState.Seeking ?: return
        if (!seeking.matches(failure)) return
        state =
            SeekState.Failed(
                targetMs = seeking.targetMs,
                requestGeneration = seeking.requestGeneration,
                sourceEpoch = seeking.sourceEpoch,
                mediaIdentity = seeking.mediaIdentity,
                reason = failure.reason,
                retryable = failure.retryable,
                policy = seeking.policy,
            )
    }

    fun onTick(nowElapsedMs: Long) {
        val seeking = state as? SeekState.Seeking ?: return
        if (nowElapsedMs < seeking.requestedAtMs + seeking.policy.completionTimeoutMs) {
            return
        }
        state =
            SeekState.Failed(
                targetMs = seeking.targetMs,
                requestGeneration = seeking.requestGeneration,
                sourceEpoch = seeking.sourceEpoch,
                mediaIdentity = seeking.mediaIdentity,
                reason = SeekFailureReason.TIMEOUT,
                retryable = true,
                policy = seeking.policy,
            )
    }

    fun onSourceReplaced(mediaIdentity: SeekMediaIdentity) {
        observeSource(mediaIdentity)
    }

    fun onTargetResolved(
        requestGeneration: Long,
        targetMs: Long,
        durationMs: Long? = null,
    ) {
        val seeking = state as? SeekState.Seeking ?: return
        if (seeking.requestGeneration != requestGeneration) return
        val target =
            if (durationMs != null && durationMs > 0L) {
                targetMs.coerceIn(0L, durationMs)
            } else {
                targetMs.coerceAtLeast(0L)
            }
        state = seeking.copy(targetMs = target, acceptedPositionMs = null)
    }

    fun onDispatched(requestGeneration: Long, nowElapsedMs: Long) {
        val seeking = state as? SeekState.Seeking ?: return
        if (seeking.requestGeneration != requestGeneration) return
        // Media3 can deliver SEEK_ACCEPTED synchronously before the engine call returns.
        state = seeking.copy(requestedAtMs = nowElapsedMs)
    }

    private fun SeekState.Seeking.matches(evidence: SeekEngineEvidence): Boolean =
        evidence.requestGeneration == requestGeneration &&
            evidence.sourceEpoch == sourceEpoch &&
            evidence.mediaIdentity == mediaIdentity

    private fun SeekState.Seeking.matches(failure: SeekEngineFailure): Boolean =
        failure.requestGeneration == requestGeneration &&
            failure.sourceEpoch == sourceEpoch &&
            failure.mediaIdentity == mediaIdentity

    private fun clampTarget(targetMs: Long, durationMs: Long): Long =
        if (durationMs > 0L) {
            targetMs.coerceIn(0L, durationMs)
        } else {
            targetMs.coerceAtLeast(0L)
        }
}

fun seekDisplayPositionMs(
    state: SeekState,
    enginePositionMs: Long,
    nowElapsedMs: Long,
): Long =
    when (state) {
        is SeekState.Seeking -> state.targetMs
        is SeekState.Failed -> state.targetMs
        is SeekState.Confirmed ->
            if (nowElapsedMs < state.requestedAtMs + state.policy.minimumTargetDisplayMs) {
                state.targetMs
            } else {
                enginePositionMs
            }
        else -> enginePositionMs
    }

internal fun seekDispatchTargetMs(
    state: SeekState,
    requestGeneration: Long,
    durationMs: Long,
): Long? {
    val seeking = state as? SeekState.Seeking ?: return null
    if (seeking.requestGeneration != requestGeneration) return null
    return if (durationMs > 0L) {
        seeking.targetMs.coerceIn(0L, durationMs)
    } else {
        seeking.targetMs.coerceAtLeast(0L)
    }
}
