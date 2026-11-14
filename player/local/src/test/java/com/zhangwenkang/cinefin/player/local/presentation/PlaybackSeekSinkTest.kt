package com.zhangwenkang.cinefin.player.local.presentation

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_MEDIA_SOURCE_ID
import com.zhangwenkang.cinefin.player.local.domain.SeekEngine
import com.zhangwenkang.cinefin.player.local.domain.SeekFailureReason
import com.zhangwenkang.cinefin.player.local.domain.SeekState
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSeekSinkTest {
    @Test
    fun `two media session facades share one coordinator generation and source cursor`() {
        var now = 1_000L
        val firstItem = directItem("item-a", "source-a")
        val firstPlayer = FakePlayer(mediaItem = firstItem)
        val sink =
            PlaybackSeekSink(
                scope = CoroutineScope(Dispatchers.Unconfined),
                engineProvider = { SeekEngine.EXOPLAYER },
                clock = { now },
                mediaIdentityProvider = ::testIdentity,
                mediaSourceUriProvider = ::testSourceUri,
            )
        sink.attach(firstPlayer.player)

        val firstFacade = MediaSessionSeekPlayer(firstPlayer.player, sink::submit)
        firstFacade.seekTo(60_000L)
        assertTrue("unexpected state: ${sink.state}", sink.state is SeekState.Seeking)
        val first = sink.state as SeekState.Seeking
        firstPlayer.emitSeekAccepted(positionMs = 60_000L)
        now = 1_100L
        firstPlayer.emitPlaybackReady(positionMs = 60_050L)
        assertEquals(first.requestGeneration, (sink.state as SeekState.Confirmed).requestGeneration)

        val recreatedFacade = MediaSessionSeekPlayer(firstPlayer.player, sink::submit)
        recreatedFacade.seekTo(70_000L)
        val second = sink.state as SeekState.Seeking
        assertEquals(first.requestGeneration + 1L, second.requestGeneration)
        assertEquals(1L, second.sourceEpoch)
        firstPlayer.emitSeekAccepted(positionMs = 70_000L)
        now = 1_200L
        firstPlayer.emitPlaybackReady(positionMs = 70_050L)
        assertEquals(
            second.requestGeneration,
            (sink.state as SeekState.Confirmed).requestGeneration,
        )

        val secondPlayer = FakePlayer(mediaItem = directItem("item-b", "source-b"))
        sink.attach(secondPlayer.player)
        assertEquals(2L, sink.coordinator.sourceEpoch)
        recreatedFacade.seekTo(5_000L)
        assertEquals(
            second.requestGeneration + 1L,
            (sink.state as SeekState.Seeking).requestGeneration,
        )
    }

    @Test
    fun `pending absolute request dispatches only after reducer owned readiness`() {
        var now = 2_000L
        val player =
            FakePlayer(
                mediaItem = directItem("item-a", "source-a"),
                playbackState = Player.STATE_IDLE,
                durationMs = C.TIME_UNSET,
            )
        val sink =
            PlaybackSeekSink(
                scope = CoroutineScope(Dispatchers.Unconfined),
                engineProvider = { SeekEngine.EXOPLAYER },
                clock = { now },
                mediaIdentityProvider = ::testIdentity,
                mediaSourceUriProvider = ::testSourceUri,
            )
        sink.attach(player.player)

        sink.submit(PlayerViewModel.SeekIntent.Absolute(48_000L))
        assertTrue("unexpected state: ${sink.state}", sink.state is SeekState.Seeking)
        assertFalse(player.calls.any { it == "seekTo" })

        player.playbackState = Player.STATE_READY
        player.durationMs = 120_000L
        now = 2_100L
        player.emitPlaybackState(Player.STATE_READY)

        assertTrue(player.calls.any { it == "seekTo" })
        assertTrue(sink.state is SeekState.Seeking)
    }

    @Test
    fun `missing identity fails unsupported and never reaches delegate`() {
        val player = FakePlayer(mediaItem = MediaItem.fromUri("https://media.example/no-id.mp4"))
        val sink =
            PlaybackSeekSink(
                scope = CoroutineScope(Dispatchers.Unconfined),
                engineProvider = { SeekEngine.EXOPLAYER },
                clock = { 1_000L },
                mediaIdentityProvider = { null },
                mediaSourceUriProvider = { "" },
            )
        sink.attach(player.player)

        sink.submit(PlayerViewModel.SeekIntent.Absolute(10_000L))

        val failed = sink.state as SeekState.Failed
        assertEquals(SeekFailureReason.UNSUPPORTED, failed.reason)
        assertEquals(null, failed.mediaIdentity)
        assertFalse(player.calls.any { it == "seekTo" })
        assertFalse(player.calls.any { it == "seekToIndex" })
    }

    @Test
    fun `hls outside window replaces source and confirms only after new source ready`() {
        var now = 3_000L
        val original =
            directItem(
                id = "item-a",
                sourceId = "source-a",
                uri = "https://media.example/video/master.m3u8?StartTimeTicks=0",
                sessionStartMs = 0L,
            )
        val replacement =
            directItem(
                id = "item-a",
                sourceId = "source-a",
                uri = "https://media.example/video/master.m3u8?StartTimeTicks=600000000",
                sessionStartMs = 60_000L,
            )
        val player =
            FakePlayer(
                mediaItem = original,
                currentPositionMs = 1_000L,
                bufferedPositionMs = 1_000L,
            )
        var currentUri = "https://media.example/video/master.m3u8?StartTimeTicks=0"
        var currentIdentity =
            com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                itemId = "item-a",
                mediaSourceId = "source-a",
                mediaSourceUri = currentUri,
            )
        var applied = 0
        val bridge =
            object : SeekSessionBridge {
                override suspend fun rebuildForSeek(
                    itemId: String,
                    mediaSourceId: String,
                    targetMs: Long,
                ): SeekSessionReplacement {
                    assertEquals("item-a", itemId)
                    assertEquals("source-a", mediaSourceId)
                    assertEquals(60_000L, targetMs)
                    currentUri = "https://media.example/video/master.m3u8?StartTimeTicks=600000000"
                    currentIdentity =
                        com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                            itemId = "item-a",
                            mediaSourceId = "source-a",
                            mediaSourceUri = currentUri,
                        )
                    return SeekSessionReplacement(replacement, currentIdentity)
                }

                override fun onSessionReplaced(replacement: SeekSessionReplacement) {
                    applied++
                }
            }
        val sink =
            PlaybackSeekSink(
                scope = CoroutineScope(Dispatchers.Unconfined),
                engineProvider = { SeekEngine.EXOPLAYER },
                clock = { now },
                transcodeDebounceMs = 0L,
                mediaIdentityProvider = { currentIdentity },
                mediaSourceUriProvider = { currentUri },
                sessionBridge = bridge,
            )
        sink.attach(player.player)

        sink.submit(PlayerViewModel.SeekIntent.Absolute(60_000L))
        val seeking = sink.state as SeekState.Seeking
        assertTrue(player.calls.contains("replaceMediaItem"))
        assertEquals(2L, seeking.sourceEpoch)
        assertEquals(1, applied)

        player.playbackState = Player.STATE_BUFFERING
        player.emitMediaItemTransition(replacement)
        player.emitSeekAccepted(positionMs = 60_000L, mediaItem = replacement)
        now = 3_100L
        player.emitPlaybackReady(positionMs = 60_100L)

        val confirmed = sink.state as SeekState.Confirmed
        assertEquals(seeking.requestGeneration, confirmed.requestGeneration)
        assertEquals(2L, confirmed.sourceEpoch)
        assertEquals(60_100L, confirmed.positionMs)
    }

    @Test
    fun `seek rebuild rejects a replacement from a different media source`() {
        var now = 6_000L
        val originalUri = "https://media.example/video/master.m3u8?StartTimeTicks=0"
        val player =
            FakePlayer(
                mediaItem =
                    directItem(
                        id = "item-source",
                        sourceId = "source-selected",
                        uri = originalUri,
                    ),
                currentPositionMs = 1_000L,
                bufferedPositionMs = 1_000L,
            )
        var currentUri = originalUri
        var currentIdentity =
            com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                itemId = "item-source",
                mediaSourceId = "source-selected",
                mediaSourceUri = currentUri,
            )
        val bridge =
            object : SeekSessionBridge {
                override suspend fun rebuildForSeek(
                    itemId: String,
                    mediaSourceId: String,
                    targetMs: Long,
                ): SeekSessionReplacement {
                    assertEquals("source-selected", mediaSourceId)
                    currentUri =
                        "https://media.example/video/master.m3u8" +
                            "?StartTimeTicks=${targetMs * 10_000L}&WrongSource=true"
                    currentIdentity =
                        com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                            itemId = itemId,
                            mediaSourceId = "source-other",
                            mediaSourceUri = currentUri,
                        )
                    return SeekSessionReplacement(
                        directItem(
                            id = itemId,
                            sourceId = "source-other",
                            uri = currentUri,
                            sessionStartMs = targetMs,
                        ),
                        currentIdentity,
                    )
                }

                override fun onSessionReplaced(replacement: SeekSessionReplacement) = Unit
            }
        val sink =
            PlaybackSeekSink(
                scope = CoroutineScope(Dispatchers.Unconfined),
                engineProvider = { SeekEngine.EXOPLAYER },
                clock = { now },
                transcodeDebounceMs = 0L,
                mediaIdentityProvider = { currentIdentity },
                mediaSourceUriProvider = { currentUri },
                sessionBridge = bridge,
            )
        sink.attach(player.player)

        sink.submit(PlayerViewModel.SeekIntent.Absolute(60_000L))

        assertTrue(
            "expected source mismatch failure, was ${sink.state}",
            sink.state is SeekState.Failed,
        )
        val failed = sink.state as SeekState.Failed
        assertEquals(SeekFailureReason.SESSION_REBUILD_FAILED, failed.reason)
        assertTrue(failed.retryable)
        assertEquals(0, player.calls.count { it == "replaceMediaItem" })
        assertEquals(0, player.calls.count { it == "seekTo" || it == "seekToIndex" })
    }

    @Test
    fun `retry after ordinary hls engine error seeks current source without rebuilding`() {
        var now = 8_000L
        val currentUri = "https://media.example/video/master.m3u8?StartTimeTicks=0"
        val player =
            FakePlayer(
                mediaItem =
                    directItem(
                        id = "item-engine-error",
                        sourceId = "source-engine-error",
                        uri = currentUri,
                    ),
                currentPositionMs = 50_000L,
                bufferedPositionMs = 70_000L,
            )
        val currentIdentity =
            com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                itemId = "item-engine-error",
                mediaSourceId = "source-engine-error",
                mediaSourceUri = currentUri,
            )
        var rebuilds = 0
        val bridge =
            object : SeekSessionBridge {
                override suspend fun rebuildForSeek(
                    itemId: String,
                    mediaSourceId: String,
                    targetMs: Long,
                ): SeekSessionReplacement? {
                    rebuilds++
                    error("ordinary HLS ENGINE_ERROR must not rebuild the source")
                }

                override fun onSessionReplaced(replacement: SeekSessionReplacement) = Unit
            }
        val sink =
            PlaybackSeekSink(
                scope = CoroutineScope(Dispatchers.Unconfined),
                engineProvider = { SeekEngine.EXOPLAYER },
                clock = { now },
                transcodeDebounceMs = 0L,
                mediaIdentityProvider = { currentIdentity },
                mediaSourceUriProvider = { currentUri },
                sessionBridge = bridge,
            )
        sink.attach(player.player)

        sink.submit(PlayerViewModel.SeekIntent.Absolute(60_000L))
        player.currentPositionMs = 60_000L
        player.bufferedPositionMs = 60_000L
        assertTrue(
            sink.coordinator.failCurrent(
                reason = SeekFailureReason.ENGINE_ERROR,
                retryable = true,
                nowElapsedMs = now,
            )
        )
        assertEquals(
            SeekFailureReason.ENGINE_ERROR,
            (sink.state as SeekState.Failed).reason,
        )
        val callsBeforeRetry = player.calls.size

        sink.retrySeek()

        assertEquals(0, rebuilds)
        assertTrue(
            "expected retry to remain seeking, was ${sink.state}",
            sink.state is SeekState.Seeking,
        )
        val retryCalls = player.calls.drop(callsBeforeRetry)
        assertEquals("seekTo", retryCalls.firstOrNull { it == "seekTo" || it == "seekToIndex" })
        assertFalse(retryCalls.contains("replaceMediaItem"))
    }

    @Test
    fun `failed replacement rebuild attempts count toward the restart budget`() {
        var now = 9_000L
        val originalUri = "https://media.example/video/master.m3u8?StartTimeTicks=0"
        val player =
            FakePlayer(
                mediaItem =
                    directItem(
                        id = "item-failed-rebuild",
                        sourceId = "source-failed-rebuild",
                        uri = originalUri,
                    ),
                currentPositionMs = 1_000L,
                bufferedPositionMs = 1_000L,
                durationMs = 120_000L,
            )
        var currentUri = originalUri
        var currentIdentity =
            com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                itemId = "item-failed-rebuild",
                mediaSourceId = "source-failed-rebuild",
                mediaSourceUri = currentUri,
            )
        var rebuildCalls = 0
        val bridge =
            object : SeekSessionBridge {
                override suspend fun rebuildForSeek(
                    itemId: String,
                    mediaSourceId: String,
                    targetMs: Long,
                ): SeekSessionReplacement {
                    assertEquals("source-failed-rebuild", mediaSourceId)
                    rebuildCalls++
                    if (rebuildCalls == 1) {
                        currentUri =
                            "https://media.example/video/master.m3u8" +
                                "?StartTimeTicks=${targetMs * 10_000L}&Refresh=1"
                        currentIdentity =
                            com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                                itemId = itemId,
                                mediaSourceId = mediaSourceId,
                                mediaSourceUri = currentUri,
                            )
                        return SeekSessionReplacement(
                            directItem(
                                id = itemId,
                                sourceId = mediaSourceId,
                                uri = currentUri,
                                sessionStartMs = targetMs,
                            ),
                            currentIdentity,
                        )
                    }
                    error("rebuild attempt $rebuildCalls failed")
                }

                override fun onSessionReplaced(replacement: SeekSessionReplacement) = Unit
            }
        val sink =
            PlaybackSeekSink(
                scope = CoroutineScope(Dispatchers.Unconfined),
                engineProvider = { SeekEngine.EXOPLAYER },
                clock = { now },
                transcodeDebounceMs = 0L,
                mediaIdentityProvider = { currentIdentity },
                mediaSourceUriProvider = { currentUri },
                sessionBridge = bridge,
            )
        sink.attach(player.player)

        sink.submit(PlayerViewModel.SeekIntent.Absolute(60_000L))
        player.playbackState = Player.STATE_BUFFERING
        player.emitMediaItemTransition(player.mediaItem)
        player.emitSeekAccepted(positionMs = 60_000L)
        player.currentPositionMs = 60_000L
        player.bufferedPositionMs = 60_000L
        sink.coordinator.failCurrent(
            reason = SeekFailureReason.TIMEOUT,
            retryable = true,
            nowElapsedMs = now,
        )
        assertEquals(SeekFailureReason.TIMEOUT, (sink.state as SeekState.Failed).reason)

        repeat(5) { sink.retrySeek() }
        assertEquals(6, rebuildCalls)
        assertEquals(
            SeekFailureReason.SESSION_REBUILD_FAILED,
            (sink.state as SeekState.Failed).reason,
        )
        val mutationsBeforeCapRetry =
            player.calls.count { it == "replaceMediaItem" || it == "seekTo" || it == "seekToIndex" }

        sink.retrySeek()

        assertEquals(6, rebuildCalls)
        assertEquals(
            mutationsBeforeCapRetry,
            player.calls.count {
                it == "replaceMediaItem" || it == "seekTo" || it == "seekToIndex"
            },
        )
        assertEquals(
            SeekFailureReason.SESSION_REBUILD_FAILED,
            (sink.state as SeekState.Failed).reason,
        )
    }

    @Test
    fun `retry after near-end replacement timeout refreshes hls source before dispatch`() {
        var now = 7_000L
        val originalUri = "https://media.example/video/master.m3u8?StartTimeTicks=0"
        val original =
            directItem(
                id = "item-retry",
                sourceId = "source-retry",
                uri = originalUri,
            )
        val player =
            FakePlayer(
                mediaItem = original,
                currentPositionMs = 1_000L,
                bufferedPositionMs = 1_000L,
            )
        var currentUri = originalUri
        var currentIdentity =
            com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                itemId = "item-retry",
                mediaSourceId = "source-retry",
                mediaSourceUri = currentUri,
            )
        var rebuilds = 0
        val bridge =
            object : SeekSessionBridge {
                override suspend fun rebuildForSeek(
                    itemId: String,
                    mediaSourceId: String,
                    targetMs: Long,
                ): SeekSessionReplacement {
                    assertEquals("item-retry", itemId)
                    assertEquals("source-retry", mediaSourceId)
                    assertEquals(60_000L, targetMs)
                    rebuilds++
                    currentUri =
                        "https://media.example/video/master.m3u8" +
                            "?StartTimeTicks=${targetMs * 10_000L}&Refresh=$rebuilds"
                    currentIdentity =
                        com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                            itemId = itemId,
                            mediaSourceId = "source-retry",
                            mediaSourceUri = currentUri,
                        )
                    return SeekSessionReplacement(
                        directItem(
                            id = itemId,
                            sourceId = "source-retry",
                            uri = currentUri,
                            sessionStartMs = targetMs,
                        ),
                        currentIdentity,
                    )
                }

                override fun onSessionReplaced(replacement: SeekSessionReplacement) = Unit
            }
        val sink =
            PlaybackSeekSink(
                scope = CoroutineScope(Dispatchers.Unconfined),
                engineProvider = { SeekEngine.EXOPLAYER },
                clock = { now },
                transcodeDebounceMs = 0L,
                mediaIdentityProvider = { currentIdentity },
                mediaSourceUriProvider = { currentUri },
                sessionBridge = bridge,
            )
        sink.attach(player.player)

        sink.submit(PlayerViewModel.SeekIntent.Absolute(60_000L))
        val first = sink.state as SeekState.Seeking
        player.playbackState = Player.STATE_BUFFERING
        player.emitMediaItemTransition(player.mediaItem)
        player.emitSeekAccepted(positionMs = 60_000L, mediaItem = player.mediaItem)
        player.currentPositionMs = 60_000L
        player.bufferedPositionMs = 60_000L
        now = 43_000L
        sink.coordinator.onTick(now)
        assertEquals(SeekFailureReason.TIMEOUT, (sink.coordinator.state as SeekState.Failed).reason)

        val callsBeforeRetry = player.calls.size
        sink.retrySeek()

        assertEquals(2, rebuilds)
        assertEquals(2, player.calls.count { it == "replaceMediaItem" })
        val retryCalls = player.calls.drop(callsBeforeRetry)
        assertEquals(
            "replaceMediaItem",
            retryCalls.firstOrNull {
                it == "replaceMediaItem" || it == "seekTo" || it == "seekToIndex"
            },
        )
        val retry = sink.state as SeekState.Seeking
        assertEquals(first.requestGeneration + 1L, retry.requestGeneration)
        assertEquals(3L, retry.sourceEpoch)
        assertTrue(currentUri.contains("Refresh=2"))
    }

    @Test
    fun `retry source refresh stops with retryable terminal at restart window cap`() {
        var now = 10_000L
        val originalUri = "https://media.example/video/master.m3u8?StartTimeTicks=0"
        val player =
            FakePlayer(
                mediaItem =
                    directItem(
                        id = "item-cap",
                        sourceId = "source-cap",
                        uri = originalUri,
                    ),
                currentPositionMs = 1_000L,
                bufferedPositionMs = 1_000L,
                durationMs = 120_000L,
            )
        var currentUri = originalUri
        var currentIdentity =
            com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                itemId = "item-cap",
                mediaSourceId = "source-cap",
                mediaSourceUri = currentUri,
            )
        var rebuilds = 0
        val bridge =
            object : SeekSessionBridge {
                override suspend fun rebuildForSeek(
                    itemId: String,
                    mediaSourceId: String,
                    targetMs: Long,
                ): SeekSessionReplacement {
                    assertEquals("source-cap", mediaSourceId)
                    rebuilds++
                    currentUri =
                        "https://media.example/video/master.m3u8" +
                            "?StartTimeTicks=${targetMs * 10_000L}&Refresh=$rebuilds"
                    currentIdentity =
                        com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                            itemId = itemId,
                            mediaSourceId = "source-cap",
                            mediaSourceUri = currentUri,
                        )
                    return SeekSessionReplacement(
                        directItem(
                            id = itemId,
                            sourceId = "source-cap",
                            uri = currentUri,
                            sessionStartMs = targetMs,
                        ),
                        currentIdentity,
                    )
                }

                override fun onSessionReplaced(replacement: SeekSessionReplacement) = Unit
            }
        val sink =
            PlaybackSeekSink(
                scope = CoroutineScope(Dispatchers.Unconfined),
                engineProvider = { SeekEngine.EXOPLAYER },
                clock = { now },
                transcodeDebounceMs = 0L,
                mediaIdentityProvider = { currentIdentity },
                mediaSourceUriProvider = { currentUri },
                sessionBridge = bridge,
            )
        sink.attach(player.player)

        sink.submit(PlayerViewModel.SeekIntent.Absolute(60_000L))
        repeat(5) {
            sink.coordinator.failCurrent(
                reason = SeekFailureReason.TIMEOUT,
                retryable = true,
                nowElapsedMs = now,
            )
            sink.retrySeek()
        }
        assertEquals(6, rebuilds)

        sink.coordinator.failCurrent(
            reason = SeekFailureReason.TIMEOUT,
            retryable = true,
            nowElapsedMs = now,
        )
        sink.retrySeek()

        assertEquals(6, rebuilds)
        assertTrue("expected bounded terminal, was ${sink.state}", sink.state is SeekState.Failed)
        val failed = sink.state as SeekState.Failed
        assertEquals(SeekFailureReason.SESSION_REBUILD_FAILED, failed.reason)
        assertTrue(failed.retryable)
    }

    @Test
    fun `a later seek does not inherit the exhausted restart budget`() {
        var now = 11_000L
        val originalUri = "https://media.example/video/master.m3u8?StartTimeTicks=0"
        val player =
            FakePlayer(
                mediaItem =
                    directItem(
                        id = "item-budget-scope",
                        sourceId = "source-budget-scope",
                        uri = originalUri,
                    ),
                currentPositionMs = 1_000L,
                bufferedPositionMs = 1_000L,
                durationMs = 120_000L,
            )
        var currentUri = originalUri
        var currentIdentity =
            com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                itemId = "item-budget-scope",
                mediaSourceId = "source-budget-scope",
                mediaSourceUri = currentUri,
            )
        var rebuilds = 0
        val bridge =
            object : SeekSessionBridge {
                override suspend fun rebuildForSeek(
                    itemId: String,
                    mediaSourceId: String,
                    targetMs: Long,
                ): SeekSessionReplacement {
                    rebuilds++
                    currentUri =
                        "https://media.example/video/master.m3u8" +
                            "?StartTimeTicks=${targetMs * 10_000L}&Refresh=$rebuilds"
                    currentIdentity =
                        com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                            itemId = itemId,
                            mediaSourceId = mediaSourceId,
                            mediaSourceUri = currentUri,
                        )
                    return SeekSessionReplacement(
                        directItem(
                            id = itemId,
                            sourceId = mediaSourceId,
                            uri = currentUri,
                            sessionStartMs = targetMs,
                        ),
                        currentIdentity,
                    )
                }

                override fun onSessionReplaced(replacement: SeekSessionReplacement) = Unit
            }
        val sink =
            PlaybackSeekSink(
                scope = CoroutineScope(Dispatchers.Unconfined),
                engineProvider = { SeekEngine.EXOPLAYER },
                clock = { now },
                transcodeDebounceMs = 0L,
                mediaIdentityProvider = { currentIdentity },
                mediaSourceUriProvider = { currentUri },
                sessionBridge = bridge,
            )
        sink.attach(player.player)

        sink.submit(PlayerViewModel.SeekIntent.Absolute(60_000L))
        repeat(5) {
            sink.coordinator.failCurrent(
                reason = SeekFailureReason.TIMEOUT,
                retryable = true,
                nowElapsedMs = now,
            )
            sink.retrySeek()
        }
        assertEquals(6, rebuilds)
        sink.coordinator.failCurrent(
            reason = SeekFailureReason.TIMEOUT,
            retryable = true,
            nowElapsedMs = now,
        )
        sink.retrySeek()
        assertTrue(sink.state is SeekState.Failed)

        sink.submit(PlayerViewModel.SeekIntent.Absolute(90_000L))

        assertEquals(7, rebuilds)
        assertTrue(
            "expected a fresh request budget, was ${sink.state}",
            sink.state is SeekState.Seeking,
        )
    }

    @Test
    fun `process bridge rebuilds HLS without a view model binding`() {
        var now = 5_000L
        val original =
            directItem(
                id = "item-process",
                sourceId = "source-process",
                uri = "https://media.example/video/master.m3u8?StartTimeTicks=0",
            )
        val replacement =
            directItem(
                id = "item-process",
                sourceId = "source-process",
                uri = "https://media.example/video/master.m3u8?StartTimeTicks=900000000",
                sessionStartMs = 90_000L,
            )
        val player =
            FakePlayer(
                mediaItem = original,
                currentPositionMs = 1_000L,
                bufferedPositionMs = 1_000L,
            )
        var currentUri = "https://media.example/video/master.m3u8?StartTimeTicks=0"
        var currentIdentity =
            com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                itemId = "item-process",
                mediaSourceId = "source-process",
                mediaSourceUri = currentUri,
            )
        val processBridge =
            object : SeekSessionBridge {
                override suspend fun rebuildForSeek(
                    itemId: String,
                    mediaSourceId: String,
                    targetMs: Long,
                ): SeekSessionReplacement {
                    assertEquals("item-process", itemId)
                    assertEquals("source-process", mediaSourceId)
                    assertEquals(90_000L, targetMs)
                    currentUri = "https://media.example/video/master.m3u8?StartTimeTicks=900000000"
                    currentIdentity =
                        com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                            itemId = "item-process",
                            mediaSourceId = "source-process",
                            mediaSourceUri = currentUri,
                        )
                    return SeekSessionReplacement(replacement, currentIdentity)
                }

                override fun onSessionReplaced(replacement: SeekSessionReplacement) = Unit
            }
        val sink =
            PlaybackSeekSink(
                scope = CoroutineScope(Dispatchers.Unconfined),
                engineProvider = { SeekEngine.EXOPLAYER },
                clock = { now },
                transcodeDebounceMs = 0L,
                mediaIdentityProvider = { currentIdentity },
                mediaSourceUriProvider = { currentUri },
                sessionBridge = processBridge,
            )
        sink.attach(player.player)

        sink.submit(PlayerViewModel.SeekIntent.Absolute(90_000L))

        val seeking = sink.state as SeekState.Seeking
        assertTrue(player.calls.contains("replaceMediaItem"))
        assertEquals(2L, seeking.sourceEpoch)
        player.playbackState = Player.STATE_BUFFERING
        player.emitMediaItemTransition(replacement)
        player.emitSeekAccepted(positionMs = 90_000L, mediaItem = replacement)
        now = 5_100L
        player.emitPlaybackReady(positionMs = 90_100L)

        val confirmed = sink.state as SeekState.Confirmed
        assertEquals(seeking.requestGeneration, confirmed.requestGeneration)
        assertEquals(2L, confirmed.sourceEpoch)
        assertEquals(90_100L, confirmed.positionMs)
    }

    @Test
    fun `process bridge and coordinator survive a recreated view session`() {
        var now = 6_000L
        val player =
            FakePlayer(
                mediaItem =
                    directItem(
                        id = "item-lifecycle",
                        sourceId = "source-lifecycle",
                        uri = "https://media.example/video/master.m3u8?StartTimeTicks=0",
                    ),
                currentPositionMs = 1_000L,
                bufferedPositionMs = 1_000L,
            )
        var currentUri = "https://media.example/video/master.m3u8?StartTimeTicks=0"
        var currentIdentity =
            com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                itemId = "item-lifecycle",
                mediaSourceId = "source-lifecycle",
                mediaSourceUri = currentUri,
            )
        var rebuilds = 0
        val processBridge =
            object : SeekSessionBridge {
                override suspend fun rebuildForSeek(
                    itemId: String,
                    mediaSourceId: String,
                    targetMs: Long,
                ): SeekSessionReplacement {
                    assertEquals("source-lifecycle", mediaSourceId)
                    rebuilds++
                    currentUri =
                        "https://media.example/video/master.m3u8?StartTimeTicks=" +
                            (targetMs * 10_000L)
                    currentIdentity =
                        com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                            itemId = itemId,
                            mediaSourceId = "source-lifecycle",
                            mediaSourceUri = currentUri,
                        )
                    return SeekSessionReplacement(
                        directItem(
                            id = itemId,
                            sourceId = "source-lifecycle",
                            uri = currentUri,
                            sessionStartMs = targetMs,
                        ),
                        currentIdentity,
                    )
                }

                override fun onSessionReplaced(replacement: SeekSessionReplacement) = Unit
            }
        val sink =
            PlaybackSeekSink(
                scope = CoroutineScope(Dispatchers.Unconfined),
                engineProvider = { SeekEngine.EXOPLAYER },
                clock = { now },
                transcodeDebounceMs = 0L,
                mediaIdentityProvider = { currentIdentity },
                mediaSourceUriProvider = { currentUri },
                sessionBridge = processBridge,
            )
        sink.attach(player.player)
        val coordinator = sink.coordinator

        val firstSession = MediaSessionSeekPlayer(player.player, sink::submit)
        firstSession.seekTo(60_000L)
        val firstSeeking = sink.state as SeekState.Seeking
        val firstReplacement = player.mediaItem
        player.playbackState = Player.STATE_BUFFERING
        player.emitMediaItemTransition(firstReplacement)
        player.emitSeekAccepted(positionMs = 60_000L, mediaItem = firstReplacement)
        now = 6_100L
        player.emitPlaybackReady(positionMs = 60_100L)
        assertEquals(2L, (sink.state as SeekState.Confirmed).sourceEpoch)

        val recreatedSession = MediaSessionSeekPlayer(player.player, sink::submit)
        recreatedSession.seekTo(120_000L)
        val secondSeeking = sink.state as SeekState.Seeking
        val secondReplacement = player.mediaItem
        player.playbackState = Player.STATE_BUFFERING
        player.emitMediaItemTransition(secondReplacement)
        player.emitSeekAccepted(positionMs = 120_000L, mediaItem = secondReplacement)
        now = 6_200L
        player.emitPlaybackReady(positionMs = 120_100L)

        assertTrue(coordinator === sink.coordinator)
        assertEquals(2, rebuilds)
        assertEquals(firstSeeking.requestGeneration + 1L, secondSeeking.requestGeneration)
        assertEquals(3L, (sink.state as SeekState.Confirmed).sourceEpoch)
    }

    @Test
    fun `late callback from superseded target cannot confirm current generation`() {
        var now = 4_000L
        val player = FakePlayer(mediaItem = directItem("item-a", "source-a"))
        val sink =
            PlaybackSeekSink(
                scope = CoroutineScope(Dispatchers.Unconfined),
                engineProvider = { SeekEngine.EXOPLAYER },
                clock = { now },
                mediaIdentityProvider = ::testIdentity,
                mediaSourceUriProvider = ::testSourceUri,
            )
        sink.attach(player.player)

        sink.submit(PlayerViewModel.SeekIntent.Absolute(20_000L))
        val first = sink.state as SeekState.Seeking
        now = 4_100L
        sink.submit(PlayerViewModel.SeekIntent.Absolute(70_000L))
        val second = sink.state as SeekState.Seeking

        player.emitSeekAccepted(positionMs = 20_000L)
        player.emitPlaybackReady(positionMs = 20_050L)
        assertEquals(second.requestGeneration, (sink.state as SeekState.Seeking).requestGeneration)
        assertTrue(first.requestGeneration < second.requestGeneration)

        now = 4_200L
        player.emitSeekAccepted(positionMs = 70_000L)
        player.emitPlaybackReady(positionMs = 70_050L)
        assertEquals(
            second.requestGeneration,
            (sink.state as SeekState.Confirmed).requestGeneration,
        )
    }

    private class FakePlayer(
        var mediaItem: MediaItem,
        var playbackState: Int = Player.STATE_READY,
        var durationMs: Long = 120_000L,
        var currentPositionMs: Long = 10_000L,
        var bufferedPositionMs: Long = 120_000L,
    ) {
        val calls = mutableListOf<String>()
        private val listeners = mutableListOf<Player.Listener>()
        private val mediaItems = mutableListOf(mediaItem)
        private var currentIndex = 0

        val player: Player =
            Proxy.newProxyInstance(
                Player::class.java.classLoader,
                arrayOf(Player::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "addListener" -> listeners += args!![0] as Player.Listener
                    "removeListener" -> listeners -= args!![0] as Player.Listener
                    "getCurrentMediaItem" -> mediaItem
                    "getMediaItemAt" -> mediaItems[args!![0] as Int]
                    "getMediaItemCount" -> mediaItems.size
                    "getCurrentMediaItemIndex" -> currentIndex
                    "getNextMediaItemIndex" ->
                        if (currentIndex + 1 < mediaItems.size) currentIndex + 1 else C.INDEX_UNSET
                    "getPreviousMediaItemIndex" ->
                        if (currentIndex > 0) currentIndex - 1 else C.INDEX_UNSET
                    "hasNextMediaItem" -> currentIndex + 1 < mediaItems.size
                    "hasPreviousMediaItem" -> currentIndex > 0
                    "getPlaybackState" -> playbackState
                    "getDuration" -> durationMs
                    "getCurrentPosition" -> currentPositionMs
                    "getBufferedPosition" -> bufferedPositionMs
                    "isPlaying" -> playbackState == Player.STATE_READY
                    "getPlayWhenReady" -> true
                    "getSeekBackIncrement" -> 10_000L
                    "getSeekForwardIncrement" -> 10_000L
                    "isCommandAvailable" -> true
                    "seekTo" -> {
                        calls += if (method.parameterCount == 2) "seekToIndex" else "seekTo"
                        currentPositionMs = args!!.last() as Long
                    }
                    "replaceMediaItem" -> {
                        calls += method.name
                        val index = args!![0] as Int
                        val item = args[1] as MediaItem
                        mediaItems[index] = item
                        mediaItem = item
                        currentIndex = index
                    }
                    else -> {
                        calls += method.name
                        defaultValue(method.returnType)
                    }
                }
            } as Player

        fun emitSeekAccepted(
            positionMs: Long,
            mediaItem: MediaItem = this.mediaItem,
        ) {
            val positionInfo = positionInfo(mediaItem, positionMs)
            listeners.toList().forEach {
                it.onPositionDiscontinuity(
                    positionInfo,
                    positionInfo,
                    Player.DISCONTINUITY_REASON_SEEK,
                )
            }
        }

        fun emitPlaybackReady(positionMs: Long) {
            currentPositionMs = positionMs
            playbackState = Player.STATE_READY
            listeners.toList().forEach { it.onPlaybackStateChanged(Player.STATE_READY) }
        }

        fun emitPlaybackState(state: Int) {
            playbackState = state
            listeners.toList().forEach { it.onPlaybackStateChanged(state) }
        }

        fun emitMediaItemTransition(next: MediaItem) {
            mediaItem = next
            listeners.toList().forEach {
                it.onMediaItemTransition(next, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            }
        }
    }

    private companion object {
        fun directItem(
            id: String,
            sourceId: String,
            uri: String = "https://media.example/video/$id.mp4",
            sessionStartMs: Long = 0L,
        ): MediaItem =
            MediaItem.Builder()
                .setMediaId(id)
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setExtras(
                            Bundle().apply {
                                putString(PLAYER_EXTRA_MEDIA_SOURCE_ID, sourceId)
                                putLong(PLAYER_EXTRA_SEEK_SESSION_START_MS, sessionStartMs)
                                putString(PLAYER_EXTRA_SEEK_SOURCE_URI, uri)
                            }
                        )
                        .build()
                )
                .build()

        fun testSourceUri(mediaItem: MediaItem): String =
            "https://media.example/video/${mediaItem.mediaId}.mp4"

        fun testIdentity(mediaItem: MediaItem) =
            mediaItem.mediaId.takeIf(String::isNotBlank)?.let { id ->
                com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor(
                    itemId = id,
                    mediaSourceId = "source-$id",
                    mediaSourceUri = testSourceUri(mediaItem),
                )
            }

        fun positionInfo(mediaItem: MediaItem, positionMs: Long): Player.PositionInfo =
            Player.PositionInfo(
                Any(),
                0,
                mediaItem,
                Any(),
                0,
                positionMs,
                positionMs,
                -1,
                -1,
            )

        fun defaultValue(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.lang.Character.TYPE -> '\u0000'
                else -> null
            }
    }
}
