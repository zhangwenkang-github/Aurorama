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

data class SeekRequest(
    val targetMs: Long,
    val requestGeneration: Long,
    val sourceEpoch: Long,
    val mediaIdentity: SeekMediaIdentity,
    val durationMs: Long,
    val issuedAtMs: Long,
    val policy: SeekConfirmationPolicy,
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

    fun request(request: SeekRequest) {
        val current = state
        val currentGeneration =
            when (current) {
                is SeekState.Seeking -> current.requestGeneration
                is SeekState.Confirmed -> current.requestGeneration
                is SeekState.Failed -> current.requestGeneration
                SeekState.Idle -> 0L
            }
        if (request.requestGeneration <= currentGeneration) {
            return
        }
        val targetMs =
            if (request.durationMs > 0L) {
                request.targetMs.coerceIn(0L, request.durationMs)
            } else {
                request.targetMs.coerceAtLeast(0L)
            }
        state =
            SeekState.Seeking(
                targetMs = targetMs,
                requestGeneration = request.requestGeneration,
                sourceEpoch = request.sourceEpoch,
                mediaIdentity = request.mediaIdentity,
                requestedAtMs = request.issuedAtMs,
                policy = request.policy,
            )
    }

    fun onAccepted(evidence: SeekEngineEvidence) {
        val seeking = state as? SeekState.Seeking ?: return
        if (!seeking.matches(evidence) || evidence.kind != SeekEngineEvidenceKind.SEEK_ACCEPTED) {
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

    fun retryRequest(nowElapsedMs: Long): SeekRequest? {
        val failed = state as? SeekState.Failed ?: return null
        if (!failed.retryable) return null
        return SeekRequest(
            targetMs = failed.targetMs,
            requestGeneration = failed.requestGeneration + 1L,
            sourceEpoch = failed.sourceEpoch,
            mediaIdentity = failed.mediaIdentity,
            durationMs = 0L,
            issuedAtMs = nowElapsedMs,
            policy = failed.policy,
        )
    }

    fun onSourceReplaced(sourceEpoch: Long, mediaIdentity: SeekMediaIdentity) {
        val seeking = state as? SeekState.Seeking ?: return
        if (sourceEpoch <= seeking.sourceEpoch) return
        state =
            seeking.copy(
                sourceEpoch = sourceEpoch,
                mediaIdentity = mediaIdentity,
                acceptedPositionMs = null,
            )
    }

    fun onTargetResolved(requestGeneration: Long, targetMs: Long) {
        val seeking = state as? SeekState.Seeking ?: return
        if (seeking.requestGeneration != requestGeneration) return
        state = seeking.copy(targetMs = targetMs.coerceAtLeast(0L), acceptedPositionMs = null)
    }

    fun onDispatched(requestGeneration: Long, nowElapsedMs: Long) {
        val seeking = state as? SeekState.Seeking ?: return
        if (seeking.requestGeneration != requestGeneration) return
        state = seeking.copy(requestedAtMs = nowElapsedMs, acceptedPositionMs = null)
    }

    private fun SeekState.Seeking.matches(evidence: SeekEngineEvidence): Boolean =
        evidence.requestGeneration == requestGeneration &&
            evidence.sourceEpoch == sourceEpoch &&
            evidence.mediaIdentity == mediaIdentity

    private fun SeekState.Seeking.matches(failure: SeekEngineFailure): Boolean =
        failure.requestGeneration == requestGeneration &&
            failure.sourceEpoch == sourceEpoch &&
            failure.mediaIdentity == mediaIdentity
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
