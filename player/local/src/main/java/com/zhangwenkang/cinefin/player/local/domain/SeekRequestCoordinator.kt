package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.player.local.diag.PlaybackDiag
import kotlin.math.abs

enum class SeekEngine {
    EXOPLAYER,
    MPV,
}

/**
 * Seek confirmation windows. On real networks a seek that lands immediately still needs seconds of
 * rebuffering before STATE_READY; cross-item switches additionally load a whole new source (~6–10s
 * observed). Short windows published a false persistent "定位超时" over healthy playback, so all
 * engines share a generous window; genuine engine failures surface earlier via the error path
 * instead of the deadline.
 */
private const val EXO_SEEK_TIMEOUT_MS = 30_000L
private const val MPV_SEEK_TIMEOUT_MS = 30_000L
private const val TRANSCODE_SEEK_TIMEOUT_MS = 35_000L
private const val EXO_SEEK_TOLERANCE_MS = 1_000L
private const val MPV_SEEK_TOLERANCE_MS = 2_000L
private const val TRANSCODE_SEEK_TOLERANCE_MS = 2_000L
private const val SEEK_TARGET_DISPLAY_MIN_MS = 300L

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
        var acceptedPositionMs: Long? = null,
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

    fun ensureSource(mediaIdentity: SeekMediaIdentity): Boolean =
        when {
            reducer.sourceMediaIdentity == null -> reducer.observeSource(mediaIdentity)
            reducer.sourceMediaIdentity == mediaIdentity -> false
            else -> onSourceTransition(mediaIdentity)
        }

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

    fun beginRequestForTargetFromSource(
        currentMediaIdentity: SeekMediaIdentity,
        targetMs: Long,
        durationMs: Long,
        issuedAtMs: Long,
        engine: SeekEngine,
        targetSourceUri: String,
    ): Long {
        ensureSource(currentMediaIdentity)
        return beginRequestForTarget(
            targetMs = targetMs,
            durationMs = durationMs,
            issuedAtMs = issuedAtMs,
            engine = engine,
            targetSourceUri = targetSourceUri,
        )
    }

    /** Cross-item episode switch: same policy as same-item seeks (uniform load-aware window). */
    fun beginRequestForCrossItemTarget(
        targetMs: Long,
        durationMs: Long,
        issuedAtMs: Long,
        engine: SeekEngine,
        targetSourceUri: String,
    ): Long =
        beginRequestForTarget(
            targetMs = targetMs,
            durationMs = durationMs,
            issuedAtMs = issuedAtMs,
            engine = engine,
            targetSourceUri = targetSourceUri,
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
        PlaybackDiag.log(
            "dispatch g$requestGeneration e${seeking.sourceEpoch} " +
                "expected=${expectedMediaIdentity.itemId}/${expectedMediaIdentity.sourceFingerprint} " +
                "target=${seeking.targetMs} tol=${seeking.policy.positionToleranceMs} timeout=${seeking.policy.completionTimeoutMs}"
        )
    }

    fun onSeekAccepted(
        engine: SeekEngine,
        mediaIdentity: SeekMediaIdentity,
        positionMs: Long?,
    ): Boolean {
        val matchingDispatches = dispatches.filter {
            !it.accepted &&
                !it.completed &&
                it.engine == engine &&
                it.expectedMediaIdentity == mediaIdentity &&
                (positionMs == null || abs(positionMs - it.targetMs) <= it.positionToleranceMs)
        }
        if (matchingDispatches.isEmpty()) {
            PlaybackDiag.log(
                "accept MISS engine=$engine pos=$positionMs id=${mediaIdentity.itemId}/${mediaIdentity.sourceFingerprint} " +
                    "dispatches=${dispatches.map { "g${it.requestGeneration}:e${it.sourceEpoch}:${it.expectedMediaIdentity.itemId}/${it.expectedMediaIdentity.sourceFingerprint}:t${it.targetMs}:acc${it.accepted}:cmp${it.completed}" }} " +
                    "state=${reducer.state}"
            )
            return false
        }
        matchingDispatches.forEach {
            it.accepted = true
            it.acceptedPositionMs = positionMs ?: it.targetMs
        }
        val current = reducer.state as? SeekState.Seeking
        if (current == null) {
            PlaybackDiag.log("accept matched but state not Seeking: ${reducer.state}")
            return false
        }
        val dispatch = matchingDispatches.firstOrNull {
            it.requestGeneration == current.requestGeneration
        }
        if (dispatch == null) {
            PlaybackDiag.log(
                "accept matched older gen: current=g${current.requestGeneration} matched=${matchingDispatches.map { it.requestGeneration }}"
            )
            return false
        }
        val before = reducer.state
        reducer.onAccepted(
            SeekEngineEvidence(
                requestGeneration = dispatch.requestGeneration,
                sourceEpoch = current.sourceEpoch,
                mediaIdentity = mediaIdentity,
                positionMs = positionMs ?: current.targetMs,
                kind = SeekEngineEvidenceKind.SEEK_ACCEPTED,
            )
        )
        val changed = reducer.state != before
        PlaybackDiag.log(
            "accept HIT g${dispatch.requestGeneration} e${current.sourceEpoch} pos=$positionMs " +
                "reducerAccepted=$changed seeking=${reducer.state}"
        )
        return changed
    }

    fun onPlaybackReady(
        engine: SeekEngine,
        mediaIdentity: SeekMediaIdentity,
        positionMs: Long,
        nowElapsedMs: Long,
    ): Boolean {
        val matchingDispatches = dispatches.filter {
            it.accepted &&
                !it.completed &&
                it.engine == engine &&
                it.expectedMediaIdentity == mediaIdentity &&
                abs(positionMs - it.targetMs) <= it.positionToleranceMs
        }
        if (matchingDispatches.isEmpty()) {
            val failed = reducer.state as? SeekState.Failed
            if (
                failed != null &&
                    failed.reason == SeekFailureReason.TIMEOUT &&
                    failed.mediaIdentity == mediaIdentity &&
                    abs(positionMs - failed.targetMs) <= failed.policy.positionToleranceMs &&
                    reducer.onLateReadyAfterTimeout(positionMs, nowElapsedMs)
            ) {
                // The engine finished loading after the deadline; the media is really playing,
                // so the timeout card must clear instead of persisting as a false failure.
                PlaybackDiag.log(
                    "ready LATE-CONFIRM g${failed.requestGeneration} pos=$positionMs id=${mediaIdentity.itemId}"
                )
                return true
            }
            PlaybackDiag.log(
                "ready MISS engine=$engine pos=$positionMs id=${mediaIdentity.itemId}/${mediaIdentity.sourceFingerprint} " +
                    "dispatches=${dispatches.map { "g${it.requestGeneration}:e${it.sourceEpoch}:${it.expectedMediaIdentity.itemId}/${it.expectedMediaIdentity.sourceFingerprint}:t${it.targetMs}:acc${it.accepted}:cmp${it.completed}" }} " +
                    "state=${reducer.state}"
            )
            return false
        }
        val current = reducer.state as? SeekState.Seeking
        if (current == null) {
            PlaybackDiag.log("ready matched but state not Seeking: ${reducer.state}")
            return false
        }
        val dispatch = matchingDispatches.firstOrNull {
            it.requestGeneration == current.requestGeneration
        }
        if (dispatch == null) {
            PlaybackDiag.log(
                "ready matched older gen: current=g${current.requestGeneration} matched=${matchingDispatches.map { it.requestGeneration }}"
            )
            matchingDispatches.forEach { it.completed = true }
            return false
        }
        matchingDispatches.forEach { it.completed = true }
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
        val confirmed = reducer.state is SeekState.Confirmed
        PlaybackDiag.log(
            "ready HIT g${dispatch.requestGeneration} e${current.sourceEpoch} pos=$positionMs confirmed=$confirmed state=${reducer.state}"
        )
        return confirmed
    }

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

    fun onSourceTransition(
        mediaIdentity: SeekMediaIdentity,
        nowElapsedMs: Long = 0L,
    ): Boolean {
        val seeking = reducer.state as? SeekState.Seeking
        val currentDispatch = seeking?.let { state ->
            dispatches.firstOrNull { it.requestGeneration == state.requestGeneration }
        }
        val expectedIdentity = currentDispatch?.expectedMediaIdentity ?: seeking?.mediaIdentity
        val expectedItemMatches = expectedIdentity?.itemId == mediaIdentity.itemId
        val currentItemMatches = reducer.sourceMediaIdentity?.itemId == mediaIdentity.itemId
        PlaybackDiag.log(
            "sourceTransition id=${mediaIdentity.itemId}/${mediaIdentity.sourceFingerprint} " +
                "expected=${expectedIdentity?.itemId}/${expectedIdentity?.sourceFingerprint} " +
                "seeking=$seeking dispatch=${currentDispatch?.let { "g${it.requestGeneration}:acc${it.accepted}:pos${it.acceptedPositionMs}" }}"
        )
        if (seeking != null && !expectedItemMatches && !currentItemMatches) {
            PlaybackDiag.log("sourceTransition DROP (item mismatch both)")
            dispatches.clear()
            reducer.onSourceReplaced(mediaIdentity)
            return true
        }
        if (seeking != null && expectedIdentity != mediaIdentity && expectedItemMatches) {
            PlaybackDiag.log("sourceTransition REBASE (same item, new identity)")
            dispatches.removeAll {
                it.completed ||
                    (it.requestGeneration < seeking.requestGeneration &&
                        it.expectedMediaIdentity != mediaIdentity)
            }
            reducer.onSourceReplaced(mediaIdentity)
            val transitioned = reducer.state as? SeekState.Seeking
            if (transitioned != null) {
                val index = dispatches.indexOfLast {
                    it.requestGeneration == transitioned.requestGeneration
                }
                if (index >= 0) {
                    dispatches[index] =
                        dispatches[index].copy(
                            sourceEpoch = transitioned.sourceEpoch,
                            expectedMediaIdentity = mediaIdentity,
                            accepted = false,
                            acceptedPositionMs = null,
                        )
                }
            }
            return true
        }
        if (seeking != null && expectedIdentity != mediaIdentity) {
            PlaybackDiag.log("sourceTransition FAIL SOURCE_CHANGED g${seeking.requestGeneration}")
            dispatches.removeAll { it.requestGeneration <= seeking.requestGeneration }
            reducer.onSourceReplaced(mediaIdentity)
            reducer.onFailure(
                SeekEngineFailure(
                    requestGeneration = seeking.requestGeneration,
                    sourceEpoch = reducer.sourceEpoch,
                    mediaIdentity = mediaIdentity,
                    reason = SeekFailureReason.SOURCE_CHANGED,
                    retryable = true,
                ),
                nowElapsedMs = nowElapsedMs,
            )
            return true
        }
        val acceptedPositionMs =
            currentDispatch
                ?.takeIf { it.expectedMediaIdentity == mediaIdentity && it.accepted }
                ?.acceptedPositionMs
        val changed =
            reducer.onSourceReplaced(
                mediaIdentity = mediaIdentity,
                acceptedPositionMs = acceptedPositionMs,
                preserveRequestAcrossItemChange =
                    seeking != null && expectedIdentity == mediaIdentity,
            )
        PlaybackDiag.log(
            "sourceTransition REPLACE changed=$changed preserve=${seeking != null && expectedIdentity == mediaIdentity} " +
                "carryAcceptedPos=$acceptedPositionMs newState=${reducer.state}"
        )
        dispatches.removeAll { it.completed || it.expectedMediaIdentity != mediaIdentity }
        return changed
    }

    fun captureRequestLease(): SeekRequestLease? {
        val seeking = reducer.state as? SeekState.Seeking ?: return null
        return SeekRequestLease(
            requestGeneration = seeking.requestGeneration,
            sourceEpoch = seeking.sourceEpoch,
            mediaIdentity = seeking.mediaIdentity,
            targetMs = seeking.targetMs,
        )
    }

    fun isCurrent(lease: SeekRequestLease): Boolean {
        val seeking = reducer.state as? SeekState.Seeking ?: return false
        return seeking.requestGeneration == lease.requestGeneration &&
            seeking.sourceEpoch == lease.sourceEpoch &&
            seeking.mediaIdentity == lease.mediaIdentity &&
            seeking.targetMs == lease.targetMs
    }

    fun prepareSourceReplacement(
        lease: SeekRequestLease,
        mediaIdentity: SeekMediaIdentity,
    ): Boolean {
        if (!isCurrent(lease)) return false
        if (lease.mediaIdentity.itemId != mediaIdentity.itemId) return false
        val changed = reducer.onSourceReplaced(mediaIdentity)
        val seeking = reducer.state as? SeekState.Seeking ?: return changed
        val index = dispatches.indexOfLast {
            it.requestGeneration == lease.requestGeneration && !it.completed
        }
        if (index >= 0) {
            dispatches[index] =
                dispatches[index].copy(
                    sourceEpoch = seeking.sourceEpoch,
                    expectedMediaIdentity = mediaIdentity,
                    accepted = false,
                    acceptedPositionMs = null,
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
        val dispatching = reducer.state as? SeekState.Seeking
        val dispatchingGeneration = dispatching?.requestGeneration
        reducer.onTick(nowElapsedMs)
        val timedOut =
            reducer.state.let { state ->
                state is SeekState.Failed &&
                    state.requestGeneration == dispatchingGeneration &&
                    state.reason == SeekFailureReason.TIMEOUT
            }
        if (timedOut && dispatchingGeneration != null) {
            PlaybackDiag.log(
                "TIMEOUT g$dispatchingGeneration state=${reducer.state} " +
                    "dispatches=${dispatches.map { "g${it.requestGeneration}:${it.expectedMediaIdentity.itemId}:acc${it.accepted}:pos${it.acceptedPositionMs}:cmp${it.completed}" }}"
            )
            // Retire timed-out and older dispatches before a same-target retry.
            dispatches.removeAll { it.requestGeneration <= dispatchingGeneration }
        }
    }

    fun failCurrent(
        reason: SeekFailureReason,
        retryable: Boolean,
        nowElapsedMs: Long,
        lease: SeekRequestLease? = null,
    ): Boolean {
        if (lease != null && !isCurrent(lease)) return false
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

    fun failUnsupported(policy: SeekConfirmationPolicy): Boolean {
        dispatches.clear()
        reducer.onUnsupported(policy)
        return reducer.state is SeekState.Failed
    }
}
