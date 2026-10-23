package com.zhangwenkang.cinefin.player.local.domain

import kotlin.math.abs

enum class SeekEngine {
    EXOPLAYER,
    MPV,
}

private const val EXO_SEEK_TIMEOUT_MS = 4_000L
private const val MPV_SEEK_TIMEOUT_MS = 6_000L
private const val TRANSCODE_SEEK_TIMEOUT_MS = 35_000L
private const val EXO_SEEK_TOLERANCE_MS = 1_000L
private const val MPV_SEEK_TOLERANCE_MS = 2_000L
private const val TRANSCODE_SEEK_TOLERANCE_MS = 2_000L
private const val SEEK_TARGET_DISPLAY_MIN_MS = 300L

/**
 * Policy belongs to the target item's source, not whichever item happens to be current when a
 * cross-item command is queued.
 */
fun seekConfirmationPolicyForTarget(
    engine: SeekEngine,
    mediaSourceUri: String,
): SeekConfirmationPolicy {
    val isTranscode = isTranscodeStreamUri(mediaSourceUri)
    return when {
        engine == SeekEngine.MPV ->
            SeekConfirmationPolicy(
                completionTimeoutMs = MPV_SEEK_TIMEOUT_MS,
                positionToleranceMs = MPV_SEEK_TOLERANCE_MS,
                minimumTargetDisplayMs = SEEK_TARGET_DISPLAY_MIN_MS,
            )
        isTranscode ->
            SeekConfirmationPolicy(
                completionTimeoutMs = TRANSCODE_SEEK_TIMEOUT_MS,
                positionToleranceMs = TRANSCODE_SEEK_TOLERANCE_MS,
                minimumTargetDisplayMs = SEEK_TARGET_DISPLAY_MIN_MS,
            )
        else ->
            SeekConfirmationPolicy(
                completionTimeoutMs = EXO_SEEK_TIMEOUT_MS,
                positionToleranceMs = EXO_SEEK_TOLERANCE_MS,
                minimumTargetDisplayMs = SEEK_TARGET_DISPLAY_MIN_MS,
            )
    }
}

/**
 * Stable source identity for dispatch correlation; mutable playback position is intentionally
 * absent.
 */
fun seekMediaIdentityFor(
    itemId: String,
    mediaSourceId: String,
    mediaSourceUri: String,
): SeekMediaIdentity =
    SeekMediaIdentity(
        itemId = itemId,
        mediaSourceId = mediaSourceId,
        sourceFingerprint = Integer.toHexString("$mediaSourceUri|$mediaSourceId".hashCode()),
    )

/**
 * Correlates callback-only engine events with the seek dispatch that caused them.
 *
 * Media3 and mpv do not attach a seek request id to their callbacks. Dispatches are therefore
 * consumed in call order, and an observation is accepted only when its engine, media identity,
 * target position and policy tolerance match. Superseded dispatches stay in the queue long enough
 * to absorb their late callbacks; only the current reducer generation may change visible state.
 */
class SeekRequestCoordinator {
    private val reducer = SeekStateReducer()

    private data class PendingDispatch(
        val requestGeneration: Long,
        val engine: SeekEngine,
        val sourceEpoch: Long,
        val expectedMediaIdentity: SeekMediaIdentity,
        val targetMs: Long,
        val positionToleranceMs: Long,
        var accepted: Boolean = false,
        var completed: Boolean = false,
    )

    private val dispatches = mutableListOf<PendingDispatch>()

    val state: SeekState
        get() = reducer.state

    val sourceEpoch: Long
        get() = reducer.sourceEpoch

    val sourceMediaIdentity: SeekMediaIdentity?
        get() = reducer.sourceMediaIdentity

    fun observeSource(mediaIdentity: SeekMediaIdentity): Boolean =
        reducer.observeSource(mediaIdentity)

    fun beginRequest(
        targetMs: Long,
        durationMs: Long,
        issuedAtMs: Long,
        policy: SeekConfirmationPolicy,
    ): Long =
        reducer.beginRequest(
            targetMs = targetMs,
            durationMs = durationMs,
            issuedAtMs = issuedAtMs,
            policy = policy,
        )

    fun beginRequestForTarget(
        targetMs: Long,
        durationMs: Long,
        issuedAtMs: Long,
        engine: SeekEngine,
        targetSourceUri: String,
    ): Long =
        beginRequest(
            targetMs = targetMs,
            durationMs = durationMs,
            issuedAtMs = issuedAtMs,
            policy = seekConfirmationPolicyForTarget(engine, targetSourceUri),
        )

    fun onDispatched(
        requestGeneration: Long,
        engine: SeekEngine,
        expectedMediaIdentity: SeekMediaIdentity,
        nowElapsedMs: Long,
    ) {
        val seeking = reducer.state as? SeekState.Seeking ?: return
        if (seeking.requestGeneration != requestGeneration) return
        reducer.onDispatched(requestGeneration, nowElapsedMs)
        dispatches.removeAll { it.completed }
        dispatches +=
            PendingDispatch(
                requestGeneration = requestGeneration,
                engine = engine,
                sourceEpoch = seeking.sourceEpoch,
                expectedMediaIdentity = expectedMediaIdentity,
                targetMs = seeking.targetMs,
                positionToleranceMs = seeking.policy.positionToleranceMs,
            )
    }

    fun onSeekAccepted(
        engine: SeekEngine,
        mediaIdentity: SeekMediaIdentity,
        positionMs: Long?,
    ): Boolean {
        val dispatch =
            dispatches.firstOrNull {
                !it.accepted &&
                    !it.completed &&
                    it.engine == engine &&
                    it.expectedMediaIdentity == mediaIdentity &&
                    (positionMs == null || abs(positionMs - it.targetMs) <= it.positionToleranceMs)
            } ?: return false
        dispatch.accepted = true
        val current = reducer.state as? SeekState.Seeking ?: return false
        if (current.requestGeneration != dispatch.requestGeneration) return false
        reducer.observeSource(mediaIdentity)
        val observed = reducer.state as? SeekState.Seeking ?: return false
        reducer.onAccepted(
            SeekEngineEvidence(
                requestGeneration = dispatch.requestGeneration,
                sourceEpoch = observed.sourceEpoch,
                mediaIdentity = mediaIdentity,
                positionMs = positionMs ?: current.targetMs,
                kind = SeekEngineEvidenceKind.SEEK_ACCEPTED,
            )
        )
        return reducer.state != current
    }

    fun onPlaybackReady(
        engine: SeekEngine,
        mediaIdentity: SeekMediaIdentity,
        positionMs: Long,
        nowElapsedMs: Long,
    ): Boolean {
        val dispatch =
            dispatches.firstOrNull {
                it.accepted &&
                    !it.completed &&
                    it.engine == engine &&
                    it.expectedMediaIdentity == mediaIdentity &&
                    abs(positionMs - it.targetMs) <= it.positionToleranceMs
            } ?: return false
        val current = reducer.state as? SeekState.Seeking ?: return false
        if (current.requestGeneration != dispatch.requestGeneration) {
            dispatch.completed = true
            return false
        }
        dispatch.completed = true
        reducer.onCompletionEvidence(
            SeekEngineEvidence(
                requestGeneration = dispatch.requestGeneration,
                sourceEpoch = current.sourceEpoch,
                mediaIdentity = mediaIdentity,
                positionMs = positionMs,
                kind = SeekEngineEvidenceKind.PLAYBACK_READY,
            ),
            nowElapsedMs = nowElapsedMs,
        )
        return reducer.state is SeekState.Confirmed
    }

    /**
     * Engine errors have no request id. They are only eligible after an accepted anchor from the
     * same dispatch; an older accepted dispatch consumes the error first, so it cannot be
     * re-labelled as the current request. Errors before any anchor are left to the playback error
     * path and ultimately the deadline.
     */
    fun onEngineError(
        engine: SeekEngine,
        mediaIdentity: SeekMediaIdentity,
        retryable: Boolean,
        nowElapsedMs: Long,
    ): Boolean {
        val dispatch =
            dispatches.firstOrNull {
                it.accepted &&
                    !it.completed &&
                    it.engine == engine &&
                    it.expectedMediaIdentity == mediaIdentity
            } ?: return false
        dispatch.completed = true
        val current = reducer.state as? SeekState.Seeking ?: return false
        if (current.requestGeneration != dispatch.requestGeneration) return false
        reducer.onFailure(
            SeekEngineFailure(
                requestGeneration = dispatch.requestGeneration,
                sourceEpoch = current.sourceEpoch,
                mediaIdentity = mediaIdentity,
                reason = SeekFailureReason.ENGINE_ERROR,
                retryable = retryable,
            ),
            nowElapsedMs = nowElapsedMs,
        )
        return reducer.state is SeekState.Failed
    }

    fun onSourceTransition(mediaIdentity: SeekMediaIdentity): Boolean {
        val previousEpoch = reducer.sourceEpoch
        reducer.onSourceReplaced(mediaIdentity)
        return reducer.sourceEpoch != previousEpoch
    }

    fun prepareSourceReplacement(mediaIdentity: SeekMediaIdentity): Boolean {
        val changed = reducer.observeSource(mediaIdentity)
        val seeking = reducer.state as? SeekState.Seeking ?: return changed
        val index = dispatches.indexOfLast {
            it.requestGeneration == seeking.requestGeneration && !it.completed
        }
        if (index >= 0) {
            dispatches[index] =
                dispatches[index].copy(
                    sourceEpoch = seeking.sourceEpoch,
                    expectedMediaIdentity = mediaIdentity,
                    accepted = false,
                )
        }
        return changed
    }

    fun retryRequest(nowElapsedMs: Long): Long? = reducer.retryRequest(nowElapsedMs)

    fun onTargetResolved(
        requestGeneration: Long,
        targetMs: Long,
        durationMs: Long? = null,
    ) {
        reducer.onTargetResolved(requestGeneration, targetMs, durationMs)
        dispatches
            .firstOrNull { it.requestGeneration == requestGeneration }
            ?.let { dispatch ->
                val clampedTarget =
                    if (durationMs != null && durationMs > 0L) {
                        targetMs.coerceIn(0L, durationMs)
                    } else {
                        targetMs.coerceAtLeast(0L)
                    }
                dispatches[dispatches.indexOf(dispatch)] = dispatch.copy(targetMs = clampedTarget)
            }
    }

    fun onTick(nowElapsedMs: Long) {
        reducer.onTick(nowElapsedMs)
    }

    fun failCurrent(
        reason: SeekFailureReason,
        retryable: Boolean,
        nowElapsedMs: Long,
    ): Boolean {
        val seeking = reducer.state as? SeekState.Seeking ?: return false
        reducer.onFailure(
            SeekEngineFailure(
                requestGeneration = seeking.requestGeneration,
                sourceEpoch = seeking.sourceEpoch,
                mediaIdentity = seeking.mediaIdentity,
                reason = reason,
                retryable = retryable,
            ),
            nowElapsedMs = nowElapsedMs,
        )
        dispatches.firstOrNull { it.requestGeneration == seeking.requestGeneration }?.completed =
            true
        return reducer.state is SeekState.Failed
    }
}
