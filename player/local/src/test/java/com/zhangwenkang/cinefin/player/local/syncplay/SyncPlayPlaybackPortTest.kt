package com.zhangwenkang.cinefin.player.local.syncplay

import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlaySeekConfirmation
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T8d：播放口的动词转发、读侧取值与 seek 一律经 seek sink 的约束；T8c 修复后补落点确认配对。 */
class SyncPlayPlaybackPortTest {
    private val engine = FakePlaybackEngine()
    private val submitted = mutableListOf<Pair<Long, String>>()

    private fun port(
        engineProvider: () -> com.zhangwenkang.cinefin.player.core.playback.PlaybackEngine? = {
            engine
        },
        position: () -> Long? = { 42_000L },
        playing: () -> Boolean = { true },
        seekGeneration: Long = 0L,
        confirmations: (Long) -> Long? = { null },
    ) =
        PlayerSyncPlayPlaybackPort(
            engineProvider = engineProvider,
            seekDispatcher =
                SyncPlaySeekDispatcher { ms, source ->
                    submitted += ms to source
                    seekGeneration
                },
            positionProvider = position,
            isPlayingProvider = playing,
            confirmationProvider = confirmations,
        )

    @Test
    fun `play pause setRate 直接委托当前内核`() {
        val port = port()

        port.play()
        port.pause()
        port.setRate(1.05f)

        assertEquals(listOf("play", "pause", "setRate:1.05"), engine.calls)
        assertEquals(1.05f, engine.appliedRate, 0.0001f)
    }

    @Test
    fun `读侧取播放态而不是内核`() {
        var position: Long? = 1_000L
        var playing = false
        val port =
            PlayerSyncPlayPlaybackPort(
                engineProvider = { engine },
                seekDispatcher = SyncPlaySeekDispatcher { _, _ -> 0L },
                positionProvider = { position },
                isPlayingProvider = { playing },
            )

        assertEquals(1_000L, port.currentPositionMs)
        assertFalse(port.isPlaying)

        position = 9_500L
        playing = true

        assertEquals(9_500L, port.currentPositionMs)
        assertTrue(port.isPlaying)
    }

    @Test
    fun `读不到确认位置时回 null 由状态机跳过本轮校正`() {
        val port = port(position = { null })

        assertNull(port.currentPositionMs)
    }

    @Test
    fun `seekTo 走 seek sink 且带 syncplay-command 标签`() {
        val port = port()

        port.seekTo(61_000L, requestId = 1L)

        assertEquals(listOf(61_000L to SYNC_PLAY_SEEK_SOURCE), submitted)
        assertEquals("syncplay-command", submitted.single().second)
        // 关键约束：port 不能直接调 engine.seekTo（否则绕开确认 / 排队 / 转码重开会话）
        assertTrue("engine 不应收到 seekTo：${engine.calls}", engine.calls.isEmpty())
    }

    @Test
    fun `没有活动内核时动词退化为空操作`() {
        val submittedBefore = submitted.size
        val port = port(engineProvider = { null })

        port.play()
        port.pause()
        port.setRate(0.95f)
        port.seekTo(1L, requestId = 1L)

        // 定位仍然进 seek sink：由 sink 自己判定「没有实例」并给出失败态，端口不替它决定
        assertEquals(submittedBefore + 1, submitted.size)
    }

    @Test
    fun `落点确认按 seek sink 代次配对并带回状态机请求号`() {
        var confirmedGeneration: Long? = null
        val port =
            port(
                seekGeneration = 7L,
                confirmations = { generation ->
                    confirmedGeneration = generation
                    60_800L
                },
            )

        port.seekTo(61_000L, requestId = 42L)
        val confirmation = port.seekConfirmation

        assertEquals(7L, confirmedGeneration)
        assertEquals(SyncPlaySeekConfirmation(requestId = 42L, positionMs = 60_800L), confirmation)
    }

    @Test
    fun `sink 未确认前不产出落点确认`() {
        val port = port(seekGeneration = 3L, confirmations = { null })

        port.seekTo(9_000L, requestId = 1L)

        assertNull(port.seekConfirmation)
    }

    @Test
    fun `sink 拿不到请求代次时不产出落点确认`() {
        val sawGeneration = mutableListOf<Long>()
        val port =
            port(
                seekGeneration = 0L,
                confirmations = {
                    sawGeneration += it
                    9_000L
                },
            )

        port.seekTo(9_000L, requestId = 1L)

        assertNull("代次 0 = 无法确认，不该去问 sink", port.seekConfirmation)
        assertTrue(sawGeneration.isEmpty())
    }

    @Test
    fun `seekSink 接线把绝对位置包成 SeekIntent Absolute`() {
        val intents = mutableListOf<Pair<PlayerViewModel.SeekIntent, String>>()
        val dispatcher = playbackSeekSinkDispatcher { intent, source ->
            intents += intent to source
            5L
        }

        val generation = dispatcher.seekTo(123_456L, SYNC_PLAY_SEEK_SOURCE)

        assertEquals(5L, generation)
        assertEquals(
            PlayerViewModel.SeekIntent.Absolute(123_456L) to "syncplay-command",
            intents.single(),
        )
    }

    @Test
    fun `port 与真实 seek 缝组合后标签不变`() {
        val sinks = mutableListOf<Pair<Long, String>>()
        val port =
            PlayerSyncPlayPlaybackPort(
                engineProvider = { engine },
                seekDispatcher =
                    playbackSeekSinkDispatcher { intent, source ->
                        val absolute = intent as PlayerViewModel.SeekIntent.Absolute
                        sinks += absolute.targetMs to source
                        0L
                    },
                positionProvider = { 0L },
                isPlayingProvider = { false },
            )

        port.seekTo(7_500L, requestId = 1L)

        assertEquals(listOf(7_500L to "syncplay-command"), sinks)
        assertTrue(engine.calls.isEmpty())
    }
}
