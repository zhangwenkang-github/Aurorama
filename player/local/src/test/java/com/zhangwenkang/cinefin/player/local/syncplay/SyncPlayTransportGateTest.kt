package com.zhangwenkang.cinefin.player.local.syncplay

import com.zhangwenkang.cinefin.syncplay.SyncPlayError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T8d：入组期间的传输闸门——本地手势只发组命令，出组后恢复直驱。 */
class SyncPlayTransportGateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val commands = RecordingCommands()

    private var playing = false
    private var positionMs = 30_000L

    private val gate =
        SyncPlayTransportGate(
            scope = scope,
            isPlaying = { playing },
            currentPositionMs = { positionMs },
            commands = commands,
        )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `闸门关闭时全部手势直驱本地 不产生组命令`() {
        runBlocking {
            gate.togglePlayback()
            gate.requestPlay(true)
            gate.requestSeek(10_000L)
            gate.requestSeekRelative(-5_000L)
            yield()
        }

        assertFalse(gate.ownsTransport.value)
        assertTrue(commands.calls.isEmpty())
    }

    @Test
    fun `闸门开启时播放暂停换组命令`() = runBlocking {
        gate.updateOwned(true)

        playing = false
        gate.togglePlayback()
        yield()
        playing = true
        gate.togglePlayback()
        yield()

        assertEquals(listOf("unpause", "pause"), commands.calls)
    }

    @Test
    fun `面板播放键按期望动作发命令而不是切换`() = runBlocking {
        gate.updateOwned(true)

        gate.requestPlay(true)
        yield()
        gate.requestPlay(false)
        yield()

        assertEquals(listOf("unpause", "pause"), commands.calls)
    }

    @Test
    fun `绝对定位与相对定位都换算成组 SEEK`() = runBlocking {
        gate.updateOwned(true)

        gate.requestSeek(61_000L)
        yield()
        positionMs = 61_000L
        gate.requestSeekRelative(-1_000L)
        yield()
        gate.requestSeek(-500L)
        yield()

        assertEquals(listOf("seek:61000", "seek:60000", "seek:0"), commands.calls)
    }

    @Test
    fun `组命令失败只记日志不影响闸门状态`() = runBlocking {
        gate.updateOwned(true)
        commands.failWith = SyncPlayError.Network("boom")

        gate.requestPlay(true)
        yield()

        assertEquals(listOf("unpause"), commands.calls)
        assertTrue(gate.ownsTransport.value)
    }

    @Test
    fun `出组后闸门立即停止拦截`() = runBlocking {
        gate.updateOwned(true)
        gate.updateOwned(false)

        gate.requestSeek(1_000L)
        yield()

        assertTrue(commands.calls.isEmpty())
    }

    private class RecordingCommands : SyncPlayTransportCommands {
        val calls = mutableListOf<String>()
        var failWith: SyncPlayError? = null

        override suspend fun pause(): SyncPlayError? {
            calls += "pause"
            return failWith
        }

        override suspend fun unpause(): SyncPlayError? {
            calls += "unpause"
            return failWith
        }

        override suspend fun seek(positionMs: Long): SyncPlayError? {
            calls += "seek:$positionMs"
            return failWith
        }
    }
}
