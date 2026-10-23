package com.zhangwenkang.cinefin.player.local.presentation

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_MEDIA_SOURCE_ID
import com.zhangwenkang.cinefin.player.local.domain.SeekEngine
import com.zhangwenkang.cinefin.player.local.domain.SeekFailureReason
import com.zhangwenkang.cinefin.player.local.domain.SeekMediaIdentity
import com.zhangwenkang.cinefin.player.local.domain.SeekRequestCoordinator
import com.zhangwenkang.cinefin.player.local.domain.SeekRequestLease
import com.zhangwenkang.cinefin.player.local.domain.SeekState
import com.zhangwenkang.cinefin.player.local.domain.isSeekRequestReady
import com.zhangwenkang.cinefin.player.local.domain.seekConfirmationPolicyForTarget
import com.zhangwenkang.cinefin.player.local.domain.seekDispatchTargetMs
import com.zhangwenkang.cinefin.player.local.domain.seekDisplayPositionMs
import com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor
import com.zhangwenkang.cinefin.player.local.domain.seekTargetFromFraction
import com.zhangwenkang.cinefin.player.local.domain.shouldRestartTranscodeSession
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer
import com.zhangwenkang.cinefin.player.local.mpv.MpvSeekEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

internal const val PLAYER_EXTRA_SEEK_SESSION_START_MS =
    "com.zhangwenkang.cinefin.player.extra.SEEK_SESSION_START_MS"
internal const val PLAYER_EXTRA_SEEK_SOURCE_URI =
    "com.zhangwenkang.cinefin.player.extra.SEEK_SOURCE_URI"

/**
 * MediaSession only sees this facade. Every position-changing command is translated into the same
 * intent vocabulary used by the in-app controls; no seek reaches the delegate before the sink's
 * reducer authorizes an engine dispatch.
 */
class MediaSessionSeekPlayer(
    player: Player,
    private val submitSeek: (PlayerViewModel.SeekIntent) -> Unit,
) : ForwardingPlayer(player) {
    override fun seekTo(positionMs: Long) {
        submitSeek(PlayerViewModel.SeekIntent.Absolute(positionMs))
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        submitSeek(PlayerViewModel.SeekIntent.MediaItem(mediaItemIndex, positionMs))
    }

    override fun seekToDefaultPosition() {
        submitSeek(PlayerViewModel.SeekIntent.Absolute(0L))
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        submitSeek(PlayerViewModel.SeekIntent.MediaItem(mediaItemIndex, C.TIME_UNSET))
    }

    override fun seekBack() {
        submitSeek(PlayerViewModel.SeekIntent.Relative(-wrappedPlayer.seekBackIncrement))
    }

    override fun seekForward() {
        submitSeek(PlayerViewModel.SeekIntent.Relative(wrappedPlayer.seekForwardIncrement))
    }

    override fun seekToPreviousMediaItem() {
        submitSeek(PlayerViewModel.SeekIntent.AdjacentMediaItem(forward = false))
    }

    override fun seekToPrevious() {
        submitSeek(PlayerViewModel.SeekIntent.AdjacentMediaItem(forward = false))
    }

    override fun seekToNextMediaItem() {
        submitSeek(PlayerViewModel.SeekIntent.AdjacentMediaItem(forward = true))
    }

    override fun seekToNext() {
        submitSeek(PlayerViewModel.SeekIntent.AdjacentMediaItem(forward = true))
    }
}

internal data class SeekSessionReplacement(
    val mediaItem: MediaItem,
    val mediaIdentity: SeekMediaIdentity,
)

internal interface SeekSessionBridge {
    suspend fun rebuildForSeek(itemId: String, targetMs: Long): SeekSessionReplacement?

    fun onSessionReplaced(replacement: SeekSessionReplacement)
}

/**
 * Process-scoped seek state machine shared by the playback ViewModel and MediaSession.
 *
 * The sink owns source observations, request generations, pending dispatch, deadlines and the Exo
 * HLS restart watchdog. The ViewModel only binds a session-specific rebuild bridge and observes
 * state; destroying or recreating the Activity cannot reset this object.
 */
class PlaybackSeekSink
internal constructor(
    private val scope: CoroutineScope,
    private val engineProvider: () -> SeekEngine,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val transcodeDebounceMs: Long = 150L,
    private val pendingRetryMs: Long = 200L,
    private val transcodeWatchdogDelayMs: Long = 2_500L,
    private val mediaIdentityProvider: (MediaItem) -> SeekMediaIdentity? =
        ::seekMediaIdentityFromItem,
    private val mediaSourceUriProvider: (MediaItem) -> String = ::seekSourceUriFromItem,
    private val sessionBridge: SeekSessionBridge? = null,
) {
    private companion object {
        const val TRANSCODE_RESTART_WINDOW_MS = 30_000L
        const val TRANSCODE_RESTART_WINDOW_MAX = 6
        const val TRANSCODE_SEEK_WATCHDOG_TOLERANCE_MS = 8_000L
    }

    private sealed interface PendingSeek {
        data class Absolute(val targetMs: Long) : PendingSeek

        data class Fraction(val fraction: Float) : PendingSeek

        data class Relative(val deltaMs: Long) : PendingSeek
    }

    private data class ActiveTarget(
        val requestGeneration: Long,
        val intent: PlayerViewModel.SeekIntent,
        val mediaItemIndex: Int,
        val mediaIdentity: SeekMediaIdentity,
        val targetSourceUri: String,
    )

    private val _state = MutableStateFlow<SeekState>(SeekState.Idle)
    val stateFlow: StateFlow<SeekState> = _state.asStateFlow()

    internal val coordinator = SeekRequestCoordinator()
    val state: SeekState
        get() = coordinator.state

    private var attachedPlayer: Player? = null
    private var activeTarget: ActiveTarget? = null
    private var pendingSeek: PendingSeek? = null
    private var pendingGeneration: Long? = null
    private var pendingSource: String = ""
    private var pendingFlushJob: Job? = null
    private var deadlineJob: Job? = null
    private var transcodeRestartDebounceJob: Job? = null
    private var transcodeRestartPendingLease: SeekRequestLease? = null
    private var transcodeRestartJob: Job? = null
    private var transcodeRestartLease: SeekRequestLease? = null
    private var transcodeRestartWindowStartMs = 0L
    private var transcodeRestartWindowCount = 0
    private var lastTranscodeRestartMediaId: String? = null
    private var transcodeWatchdogJob: Job? = null

    private val seekEventListener =
        SeekPlayerEventListener(
            coordinator = coordinator,
            engine = engineProvider,
            mediaIdentity = { mediaItem -> mediaIdentity(mediaItem ?: currentMediaItem()) },
            readyPositionMs = {
                attachedPlayer?.takeIf { it.playbackState == Player.STATE_READY }?.currentPosition
            },
            nowElapsedMs = clock,
            onStateChanged = ::publish,
        )

    private val playerListener =
        object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                seekEventListener.onPositionDiscontinuity(oldPosition, newPosition, reason)
                tryFlushPending()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                seekEventListener.onPlaybackStateChanged(playbackState)
                tryFlushPending()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem != null) onSourceTransition(mediaItem)
                tryFlushPending()
            }
        }

    private val mpvListener =
        object : MpvSeekEventListener {
            override fun onMpvSeekAccepted() {
                seekEventListener.onMpvSeekAccepted()
                tryFlushPending()
            }

            override fun onMpvPlaybackRestarted(positionMs: Long) {
                seekEventListener.onMpvPlaybackRestarted(positionMs)
            }
        }

    fun attach(player: Player) {
        if (attachedPlayer === player) {
            mediaIdentity(player.currentMediaItem)?.let(coordinator::ensureSource)
            publish()
            tryFlushPending()
            return
        }
        detachPlayer()
        attachedPlayer = player
        player.addListener(playerListener)
        (player as? MPVPlayer)?.addSeekEventListener(mpvListener)
        mediaIdentity(player.currentMediaItem)?.let(coordinator::ensureSource)
        publish()
        tryFlushPending()
    }

    fun onPlayerReleased() {
        detachPlayer()
        cancelPendingFlush()
        deadlineJob?.cancel()
        deadlineJob = null
        cancelTranscodeWork()
    }

    fun submit(intent: PlayerViewModel.SeekIntent, source: String = "media-session") {
        when (intent) {
            is PlayerViewModel.SeekIntent.Absolute -> requestSeek(intent.targetMs, source)
            is PlayerViewModel.SeekIntent.Fraction -> requestSeek(0L, source, intent.fraction)
            is PlayerViewModel.SeekIntent.Relative -> requestSeekRelative(intent.deltaMs, source)
            is PlayerViewModel.SeekIntent.MediaItem ->
                requestMediaItemSeek(intent.index, intent.positionMs, source)
            is PlayerViewModel.SeekIntent.AdjacentMediaItem ->
                requestAdjacentMediaItemSeek(intent.forward, source)
        }
    }

    fun requestSeek(targetMs: Long, source: String, fraction: Float? = null) {
        val player = attachedPlayer ?: return failUnsupported()
        val mediaItem = player.currentMediaItem ?: return failUnsupported()
        val identity = mediaIdentity(mediaItem) ?: return failUnsupported()
        coordinator.ensureSource(identity)
        val duration = player.duration
        val target =
            if (fraction != null && duration > 0L) {
                seekTargetFromFraction(fraction, duration)
            } else {
                targetMs
            }
        resetRequestWork()
        val generation =
            coordinator.beginRequestForTarget(
                targetMs = target,
                durationMs = duration,
                issuedAtMs = clock(),
                engine = engineProvider(),
                targetSourceUri = mediaSourceUri(mediaItem),
            )
        activeTarget =
            ActiveTarget(
                requestGeneration = generation,
                intent = PlayerViewModel.SeekIntent.Absolute(target),
                mediaItemIndex = player.currentMediaItemIndex,
                mediaIdentity = identity,
                targetSourceUri = mediaSourceUri(mediaItem),
            )
        publish()
        armDeadline()
        if (
            !isSeekRequestReady(
                playerAttached = true,
                durationMs = duration,
                playbackState = player.playbackState,
                currentPositionMs = player.currentPosition,
            )
        ) {
            pendingSeek =
                if (fraction != null) PendingSeek.Fraction(fraction)
                else PendingSeek.Absolute(targetMs.coerceAtLeast(0L))
            pendingGeneration = generation
            pendingSource = source
            schedulePendingFlush()
            return
        }
        executeCurrent(player, source)
    }

    fun requestSeekRelative(deltaMs: Long, source: String) {
        val player = attachedPlayer
        val base =
            seekDisplayPositionMs(
                coordinator.state,
                player?.currentPosition ?: 0L,
                clock(),
            )
        requestSeek(base + deltaMs, source)
    }

    fun retrySeek() {
        val generation = coordinator.retryRequest(clock()) ?: return
        val retry = coordinator.state as? SeekState.Seeking ?: return
        val player = attachedPlayer ?: return failUnsupported()
        val item = player.currentMediaItem ?: return failUnsupported()
        val identity = mediaIdentity(item) ?: return failUnsupported()
        clearPendingSeek()
        cancelTranscodeWork()
        activeTarget =
            ActiveTarget(
                requestGeneration = generation,
                intent = PlayerViewModel.SeekIntent.Absolute(retry.targetMs),
                mediaItemIndex = player.currentMediaItemIndex,
                mediaIdentity = identity,
                targetSourceUri = mediaSourceUri(item),
            )
        publish()
        armDeadline()
        if (
            isSeekRequestReady(
                playerAttached = true,
                durationMs = player.duration,
                playbackState = player.playbackState,
                currentPositionMs = player.currentPosition,
            )
        ) {
            executeCurrent(player, "seek-retry")
        } else {
            pendingSeek = PendingSeek.Absolute(retry.targetMs)
            pendingGeneration = generation
            pendingSource = "seek-retry"
            schedulePendingFlush()
        }
    }

    fun seekDisplayPositionMs(): Long =
        seekDisplayPositionMs(
            coordinator.state,
            attachedPlayer?.currentPosition ?: 0L,
            clock(),
        )

    fun onEngineError(
        mediaIdentity: SeekMediaIdentity? = mediaIdentity(currentMediaItem()),
        retryable: Boolean = true,
    ): Boolean {
        if (mediaIdentity == null) return false
        val failed =
            coordinator.onEngineError(
                engine = engineProvider(),
                mediaIdentity = mediaIdentity,
                retryable = retryable,
                nowElapsedMs = clock(),
            )
        if (failed) {
            deadlineJob?.cancel()
            clearPendingSeek()
            publish()
        }
        return failed
    }

    fun onSourceTransition(mediaItem: MediaItem) {
        val identity = mediaIdentity(mediaItem) ?: return failUnsupported()
        coordinator.onSourceTransition(identity, clock())
        if ((coordinator.state as? SeekState.Failed)?.reason == SeekFailureReason.SOURCE_CHANGED) {
            deadlineJob?.cancel()
            clearPendingSeek()
            cancelTranscodeWork()
        }
        publish()
    }

    fun observeSource(mediaItem: MediaItem?) {
        mediaIdentity(mediaItem)?.let(coordinator::observeSource)
        publish()
    }

    fun restartTranscodeForNetwork(lease: SeekRequestLease, source: String) {
        scheduleTranscodeRestart(lease, source)
    }

    private fun requestMediaItemSeek(index: Int, positionMs: Long, source: String) {
        val player = attachedPlayer ?: return failUnsupported()
        if (index !in 0 until player.mediaItemCount) return failUnsupported()
        val currentItem = player.currentMediaItem ?: return failUnsupported()
        val currentIdentity = mediaIdentity(currentItem) ?: return failUnsupported()
        val targetItem = player.getMediaItemAt(index)
        val targetIdentity = mediaIdentity(targetItem) ?: return failUnsupported()
        coordinator.ensureSource(currentIdentity)
        resetRequestWork()
        val targetSourceUri = mediaSourceUri(targetItem)
        val generation =
            coordinator.beginRequestForTarget(
                targetMs = 0L,
                durationMs = player.duration,
                issuedAtMs = clock(),
                engine = engineProvider(),
                targetSourceUri = targetSourceUri,
            )
        activeTarget =
            ActiveTarget(
                requestGeneration = generation,
                intent = PlayerViewModel.SeekIntent.MediaItem(index, positionMs),
                mediaItemIndex = index,
                mediaIdentity = targetIdentity,
                targetSourceUri = targetSourceUri,
            )
        publish()
        executeCurrent(player, source)
    }

    private fun requestAdjacentMediaItemSeek(forward: Boolean, source: String) {
        val player = attachedPlayer ?: return failUnsupported()
        val targetIndex = if (forward) player.nextMediaItemIndex else player.previousMediaItemIndex
        if (targetIndex == C.INDEX_UNSET) return failUnsupported()
        requestMediaItemSeek(targetIndex, 0L, source)
    }

    private fun executeCurrent(player: Player, source: String) {
        val target = activeTarget ?: return failUnsupported()
        val current = coordinator.state as? SeekState.Seeking
        if (current == null || current.requestGeneration != target.requestGeneration) return
        val targetMs =
            seekDispatchTargetMs(
                state = coordinator.state,
                requestGeneration = target.requestGeneration,
                durationMs = player.duration,
            ) ?: return
        val lease = markDispatched(target.requestGeneration, target.mediaIdentity) ?: return
        if (
            target.intent is PlayerViewModel.SeekIntent.Absolute &&
                shouldRestartTranscodeSession(
                    isTranscodeStream = target.targetSourceUri.contains(".m3u8", ignoreCase = true),
                    isExoPlayer = engineProvider() == SeekEngine.EXOPLAYER,
                    targetMs = targetMs,
                    currentPositionMs = player.currentPosition,
                    bufferedPositionMs = player.bufferedPosition,
                    sessionStartMs =
                        player.currentMediaItem
                            ?.mediaMetadata
                            ?.extras
                            ?.getLong(PLAYER_EXTRA_SEEK_SESSION_START_MS, 0L) ?: 0L,
                )
        ) {
            scheduleTranscodeRestart(lease, source)
            return
        }
        runCatching {
            when (val intent = target.intent) {
                is PlayerViewModel.SeekIntent.MediaItem ->
                    player.seekTo(intent.index, intent.positionMs)
                is PlayerViewModel.SeekIntent.AdjacentMediaItem ->
                    if (intent.forward) player.seekToNextMediaItem()
                    else player.seekToPreviousMediaItem()
                else -> player.seekTo(targetMs)
            }
        }
            .onFailure {
                Timber.w(it, "seek 同步拒绝：source=%s target=%d", source, targetMs)
                failActiveSeek(SeekFailureReason.REJECTED, retryable = false)
            }
            .onSuccess {
                armTranscodeWatchdog(
                    lease = lease,
                    source = source,
                    enabled =
                        target.intent is PlayerViewModel.SeekIntent.Absolute &&
                            target.targetSourceUri.contains(".m3u8", ignoreCase = true) &&
                            engineProvider() == SeekEngine.EXOPLAYER,
                )
            }
    }

    private fun markDispatched(
        generation: Long,
        expectedMediaIdentity: SeekMediaIdentity,
    ): SeekRequestLease? {
        coordinator.onDispatched(
            requestGeneration = generation,
            engine = engineProvider(),
            expectedMediaIdentity = expectedMediaIdentity,
            nowElapsedMs = clock(),
        )
        publish()
        armDeadline()
        return coordinator.captureRequestLease()
    }

    private fun tryFlushPending() {
        val generation = pendingGeneration ?: return
        val current = coordinator.state as? SeekState.Seeking ?: return
        if (current.requestGeneration != generation) return
        val player = attachedPlayer ?: return
        if (
            !isSeekRequestReady(
                playerAttached = true,
                durationMs = player.duration,
                playbackState = player.playbackState,
                currentPositionMs = player.currentPosition,
            )
        ) {
            return
        }
        val pending = pendingSeek ?: return
        val resolved =
            when (pending) {
                is PendingSeek.Absolute -> pending.targetMs
                is PendingSeek.Fraction -> seekTargetFromFraction(pending.fraction, player.duration)
                is PendingSeek.Relative -> player.currentPosition + pending.deltaMs
            }
        coordinator.onTargetResolved(
            requestGeneration = generation,
            targetMs = resolved,
            durationMs = player.duration,
        )
        activeTarget = activeTarget?.copy(intent = PlayerViewModel.SeekIntent.Absolute(resolved))
        publish()
        val source = pendingSource
        clearPendingSeek(cancelFlush = false)
        executeCurrent(player, source)
    }

    private fun schedulePendingFlush() {
        pendingFlushJob?.cancel()
        val generation = pendingGeneration ?: return
        pendingFlushJob = scope.launch {
            while (isActive) {
                delay(pendingRetryMs)
                if (pendingGeneration != generation) return@launch
                tryFlushPending()
                if (pendingGeneration == null) return@launch
            }
        }
    }

    private fun armDeadline() {
        deadlineJob?.cancel()
        val seeking = coordinator.state as? SeekState.Seeking ?: return
        val generation = seeking.requestGeneration
        val remaining =
            (seeking.requestedAtMs + seeking.policy.completionTimeoutMs - clock()).coerceAtLeast(1L)
        deadlineJob = scope.launch {
            delay(remaining)
            coordinator.onTick(clock())
            publish()
            val failed = coordinator.state as? SeekState.Failed
            if (
                failed?.requestGeneration == generation &&
                    failed.reason == SeekFailureReason.TIMEOUT
            ) {
                clearPendingSeek()
            }
        }
    }

    private fun failActiveSeek(
        reason: SeekFailureReason,
        retryable: Boolean,
        lease: SeekRequestLease? = null,
    ) {
        if (lease != null && !coordinator.isCurrent(lease)) return
        coordinator.failCurrent(
            reason = reason,
            retryable = retryable,
            nowElapsedMs = clock(),
            lease = lease,
        )
        deadlineJob?.cancel()
        clearPendingSeek()
        publish()
    }

    private fun failUnsupported() {
        cancelPendingFlush()
        deadlineJob?.cancel()
        cancelTranscodeWork()
        val sourceUri = currentMediaItem()?.let(::mediaSourceUri).orEmpty()
        coordinator.failUnsupported(
            seekConfirmationPolicyForTarget(
                engine = engineProvider(),
                mediaSourceUri = sourceUri,
            )
        )
        publish()
    }

    private fun scheduleTranscodeRestart(lease: SeekRequestLease, source: String) {
        if (!coordinator.isCurrent(lease)) return
        transcodeRestartPendingLease = lease
        transcodeRestartDebounceJob?.cancel()
        transcodeRestartDebounceJob = scope.launch {
            delay(transcodeDebounceMs)
            if (!coordinator.isCurrent(lease)) return@launch
            if (transcodeRestartJob?.isActive == true) return@launch
            val pending = transcodeRestartPendingLease ?: return@launch
            if (pending != lease || !coordinator.isCurrent(pending)) return@launch
            transcodeRestartPendingLease = null
            restartTranscodeSession(lease, source)
        }
    }

    private fun restartTranscodeSession(lease: SeekRequestLease, source: String) {
        if (!coordinator.isCurrent(lease)) return
        val player =
            attachedPlayer
                ?: return failActiveSeek(
                    SeekFailureReason.SESSION_REBUILD_FAILED,
                    retryable = true,
                    lease = lease,
                )
        if (engineProvider() != SeekEngine.EXOPLAYER) return
        val mediaItem =
            player.currentMediaItem
                ?: return failActiveSeek(
                    SeekFailureReason.SESSION_REBUILD_FAILED,
                    retryable = true,
                    lease = lease,
                )
        val bridge =
            sessionBridge
                ?: return failActiveSeek(
                    SeekFailureReason.SESSION_REBUILD_FAILED,
                    retryable = true,
                    lease = lease,
                )
        val mediaId = mediaItem.mediaId
        val index = player.currentMediaItemIndex
        val targetMs = lease.targetMs
        val now = clock()
        if (mediaId != lastTranscodeRestartMediaId) {
            lastTranscodeRestartMediaId = mediaId
            transcodeRestartWindowStartMs = now
            transcodeRestartWindowCount = 0
        } else if (now - transcodeRestartWindowStartMs > TRANSCODE_RESTART_WINDOW_MS) {
            transcodeRestartWindowStartMs = now
            transcodeRestartWindowCount = 0
        }
        if (transcodeRestartWindowCount >= TRANSCODE_RESTART_WINDOW_MAX) {
            runCatching { player.seekTo(targetMs) }
            return
        }
        val wasPlaying = player.playWhenReady
        transcodeRestartLease = lease
        transcodeRestartJob = scope.launch {
            try {
                val replacement = runCatching {
                    bridge.rebuildForSeek(mediaId, targetMs)
                }
                    .onFailure { Timber.e(it, "转码重开会话：重建播放信息异常") }
                    .getOrNull()
                if (!coordinator.isCurrent(lease)) return@launch
                val livePlayer = attachedPlayer
                if (livePlayer == null || livePlayer !== player) return@launch
                if (
                    livePlayer.currentMediaItem?.mediaId != mediaId ||
                        livePlayer.currentMediaItemIndex != index
                ) {
                    return@launch
                }
                if (
                    replacement == null ||
                        !coordinator.prepareSourceReplacement(
                            lease,
                            replacement.mediaIdentity,
                        )
                ) {
                    failActiveSeek(
                        SeekFailureReason.SESSION_REBUILD_FAILED,
                        retryable = true,
                        lease = lease,
                    )
                    return@launch
                }
                publish()
                bridge.onSessionReplaced(replacement)
                livePlayer.replaceMediaItem(index, replacement.mediaItem)
                livePlayer.seekTo(index, targetMs)
                livePlayer.prepare()
                if (wasPlaying) livePlayer.play() else livePlayer.pause()
                transcodeRestartWindowCount++
                Timber.d(
                    "转码 seek 重开会话：item=%s target=%d source=%s index=%d",
                    mediaId,
                    targetMs,
                    source,
                    index,
                )
            } finally {
                if (transcodeRestartLease == lease) {
                    transcodeRestartLease = null
                    val merged = transcodeRestartPendingLease
                    transcodeRestartPendingLease = null
                    if (
                        merged != null &&
                            merged != lease &&
                            coordinator.isCurrent(merged) &&
                            kotlin.math.abs(merged.targetMs - targetMs) > 2_000L
                    ) {
                        scheduleTranscodeRestart(merged, "merged")
                    }
                }
            }
        }
    }

    private fun armTranscodeWatchdog(
        lease: SeekRequestLease,
        source: String,
        enabled: Boolean,
    ) {
        transcodeWatchdogJob?.cancel()
        if (!enabled) return
        transcodeWatchdogJob = scope.launch {
            delay(transcodeWatchdogDelayMs)
            if (!coordinator.isCurrent(lease)) return@launch
            val player = attachedPlayer ?: return@launch
            val stalled =
                player.playbackState == Player.STATE_BUFFERING &&
                    kotlin.math.abs(player.currentPosition - lease.targetMs) >
                        TRANSCODE_SEEK_WATCHDOG_TOLERANCE_MS
            if (stalled) {
                Timber.w(
                    "转码 seek 看门狗：target=%d position=%d source=%s",
                    lease.targetMs,
                    player.currentPosition,
                    source,
                )
                scheduleTranscodeRestart(lease, "watchdog")
            }
        }
    }

    private fun resetRequestWork() {
        deadlineJob?.cancel()
        deadlineJob = null
        clearPendingSeek()
        cancelTranscodeWork()
    }

    private fun clearPendingSeek(cancelFlush: Boolean = true) {
        if (cancelFlush) cancelPendingFlush()
        pendingSeek = null
        pendingGeneration = null
        pendingSource = ""
    }

    private fun cancelPendingFlush() {
        pendingFlushJob?.cancel()
        pendingFlushJob = null
    }

    private fun cancelTranscodeWork() {
        transcodeWatchdogJob?.cancel()
        transcodeWatchdogJob = null
        transcodeRestartDebounceJob?.cancel()
        transcodeRestartDebounceJob = null
        transcodeRestartJob?.cancel()
        transcodeRestartJob = null
        transcodeRestartPendingLease = null
        transcodeRestartLease = null
    }

    private fun detachPlayer() {
        val player = attachedPlayer ?: return
        runCatching { player.removeListener(playerListener) }
        runCatching { (player as? MPVPlayer)?.removeSeekEventListener(mpvListener) }
        attachedPlayer = null
    }

    private fun currentMediaItem(): MediaItem? = attachedPlayer?.currentMediaItem

    private fun mediaIdentity(mediaItem: MediaItem?): SeekMediaIdentity? {
        val item = mediaItem ?: return null
        return mediaIdentityProvider(item)
    }

    private fun publish() {
        _state.value = coordinator.state
    }

    private fun mediaSourceUri(item: MediaItem): String = mediaSourceUriProvider(item)
}

private fun seekSourceUriFromItem(item: MediaItem): String =
    item.mediaMetadata.extras?.getString(PLAYER_EXTRA_SEEK_SOURCE_URI)?.takeIf(String::isNotBlank)
        ?: item.localConfiguration?.uri?.toString()
        ?: item.requestMetadata.mediaUri?.toString().orEmpty()

internal fun seekMediaIdentityFromItem(item: MediaItem): SeekMediaIdentity? {
    val itemId = item.mediaId.takeIf(String::isNotBlank) ?: return null
    val sourceUri = seekSourceUriFromItem(item)
    if (sourceUri.isBlank()) return null
    return seekMediaIdentityFor(
        itemId = itemId,
        mediaSourceId =
            item.mediaMetadata.extras?.getString(PLAYER_EXTRA_MEDIA_SOURCE_ID).orEmpty(),
        mediaSourceUri = sourceUri,
    )
}

/**
 * The production translation boundary from Media3 / mpv seek callbacks to the coordinator.
 *
 * ExoPlayer supplies the new position on SEEK discontinuity. mpv supplies a fresh SEEK without a
 * position, followed by PLAYBACK_RESTART with the checked position. No callback is forwarded twice.
 */
class SeekPlayerEventListener(
    private val coordinator: SeekRequestCoordinator,
    private val engine: () -> SeekEngine,
    private val mediaIdentity: (MediaItem?) -> SeekMediaIdentity?,
    private val readyPositionMs: () -> Long?,
    private val nowElapsedMs: () -> Long,
    private val onStateChanged: () -> Unit = {},
) : Player.Listener, MpvSeekEventListener {
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (engine() != SeekEngine.EXOPLAYER || reason != Player.DISCONTINUITY_REASON_SEEK) {
            return
        }
        if (newPosition.positionMs < 0L) return
        val identity = mediaIdentity(newPosition.mediaItem) ?: return
        val changed =
            coordinator.onSeekAccepted(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = identity,
                positionMs = newPosition.positionMs,
            )
        if (!changed) return
        onStateChanged()
        readyPositionMs()?.let { positionMs ->
            complete(identity = identity, positionMs = positionMs)
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        if (engine() != SeekEngine.EXOPLAYER || state != Player.STATE_READY) return
        val identity = mediaIdentity(null) ?: return
        val positionMs = readyPositionMs() ?: return
        complete(identity = identity, positionMs = positionMs)
    }

    override fun onMpvSeekAccepted() {
        if (engine() != SeekEngine.MPV) return
        val identity = mediaIdentity(null) ?: return
        if (
            coordinator.onSeekAccepted(
                engine = SeekEngine.MPV,
                mediaIdentity = identity,
                positionMs = null,
            )
        ) {
            onStateChanged()
        }
    }

    override fun onMpvPlaybackRestarted(positionMs: Long) {
        if (engine() != SeekEngine.MPV) return
        val identity = mediaIdentity(null) ?: return
        complete(identity = identity, positionMs = positionMs)
    }

    private fun complete(identity: SeekMediaIdentity, positionMs: Long) {
        if (
            coordinator.onPlaybackReady(
                engine = engine(),
                mediaIdentity = identity,
                positionMs = positionMs,
                nowElapsedMs = nowElapsedMs(),
            )
        ) {
            onStateChanged()
        }
    }
}
