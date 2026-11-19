package com.zhangwenkang.cinefin.player.core.syncplay

import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * W4-T8b：SyncPlay 时钟同步（VIDEO-38）。
 *
 * 全部用虚拟时钟 + 虚拟 ticker 驱动：不睡真实时间、不触网；公式数值与调度序列均是手算 / 上游常量对照。
 */
class SyncPlayClockTest {

    private val t1 = Instant.parse("2026-11-19T12:00:00Z")

    // —— 公式 ——

    @Test
    fun `offset 与 delay 按 jellyfin-web 公式计算（手算用例）`() {
        // t2 − t1 = +170ms，t3 − t4 = −120ms → offset = (170 − 120) / 2 = 25ms
        // (t4 − t1) = 300ms，(t3 − t2) = 10ms → delay = 290ms，ping = 145ms
        val sample =
            SyncPlayClockSample(
                requestSentTime = t1,
                serverRequestReceptionTime = t1.plus(Duration.ofMillis(170)),
                serverResponseTransmissionTime = t1.plus(Duration.ofMillis(180)),
                requestReceivedTime = t1.plus(Duration.ofMillis(300)),
            )

        assertEquals(Duration.ofMillis(25), sample.offset)
        assertEquals(Duration.ofMillis(290), sample.roundTripDelay)
        assertEquals(Duration.ofMillis(145), sample.ping)
    }

    @Test
    fun `负偏移同样按公式计算`() {
        // 服务器比本地慢 450ms：t2 = -400ms，t3 = -380ms，t4 = +120ms
        // offset = (-400 + (t3 − t4 = -500)) / 2 = -450ms；delay = 120 − 20 = 100ms
        val sample =
            SyncPlayClockSample(
                requestSentTime = t1,
                serverRequestReceptionTime = t1.minus(Duration.ofMillis(400)),
                serverResponseTransmissionTime = t1.minus(Duration.ofMillis(380)),
                requestReceivedTime = t1.plus(Duration.ofMillis(120)),
            )

        assertEquals(Duration.ofMillis(-450), sample.offset)
        assertEquals(Duration.ofMillis(100), sample.roundTripDelay)
    }

    // —— 采样与观测面 ——

    @Test
    fun `sampleOnce 更新状态且 nowServer 等于本地时刻加偏移`() = runBlocking {
        val localClock = FakeLocalClock(t1)
        val clock =
            SyncPlayClock(
                timeSource =
                    ScriptedTimeSource.of(localClock, serverOffsetMs = 5_000, oneWayMs = 30),
                localClock = localClock,
            )

        assertFalse(clock.state.isSynchronized)
        assertEquals(0, clock.state.sampleCount)

        clock.sampleOnce()

        assertEquals(5_000L, clock.state.offset.toMillis())
        assertEquals(60L, clock.state.roundTripDelay.toMillis())
        assertEquals(1, clock.state.sampleCount)
        assertTrue(clock.state.isSynchronized)
        assertEquals(localClock.current.plus(Duration.ofMillis(5_000)), clock.nowServer())
    }

    @Test
    fun `未收敛时 nowServer 等于本地时刻、offset 为零`() = runBlocking {
        val localClock = FakeLocalClock(t1)
        val clock = SyncPlayClock(ScriptedTimeSource.of(localClock), localClock)

        assertEquals(Duration.ZERO, clock.state.offset)
        assertEquals(Duration.ZERO, clock.state.roundTripDelay)
        assertEquals(t1, clock.nowServer())
        assertFalse(clock.state.isSynchronized)
    }

    @Test
    fun `采样失败不改变已收敛状态`() = runBlocking {
        val localClock = FakeLocalClock(t1)
        val source =
            ScriptedTimeSource(
                localClock,
                listOf(
                    FetchScript.Success(serverOffsetMs = 2_000, oneWayMs = 20),
                    FetchScript.Failure,
                ),
            )
        val clock = SyncPlayClock(source, localClock)

        clock.sampleOnce()
        val synced = clock.state

        try {
            clock.sampleOnce()
            fail("采样失败应抛出原异常")
        } catch (expected: IOException) {
            // 预期
        }

        assertEquals(synced, clock.state)
        assertEquals(2_000L, clock.state.offset.toMillis())
    }

    // —— 8 窗最小 RTT ——

    @Test
    fun `窗口保留最近 8 个样本并取最小 RTT 的 offset`() = runBlocking {
        val localClock = FakeLocalClock(t1)
        // 每条样本的服务器偏移互不相同（100ms × 序号），便于判断最终选中的是哪条。
        val source =
            ScriptedTimeSource(
                localClock,
                listOf(
                    FetchScript.Success(serverOffsetMs = 100, oneWayMs = 5),
                    FetchScript.Success(serverOffsetMs = 200, oneWayMs = 30),
                    FetchScript.Success(serverOffsetMs = 300, oneWayMs = 25),
                    FetchScript.Success(serverOffsetMs = 400, oneWayMs = 40),
                    FetchScript.Success(serverOffsetMs = 500, oneWayMs = 35),
                    FetchScript.Success(serverOffsetMs = 600, oneWayMs = 45),
                    FetchScript.Success(serverOffsetMs = 700, oneWayMs = 50),
                    FetchScript.Success(serverOffsetMs = 800, oneWayMs = 55),
                    FetchScript.Success(serverOffsetMs = 900, oneWayMs = 20),
                ),
            )
        val clock = SyncPlayClock(source, localClock)

        repeat(8) { clock.sampleOnce() }
        // 前 8 条里 #1 的 10ms RTT 最小
        assertEquals(8, clock.state.sampleCount)
        assertEquals(100L, clock.state.offset.toMillis())
        assertEquals(10L, clock.state.roundTripDelay.toMillis())

        clock.sampleOnce()
        // 第 9 条入窗，#1 被滑出：窗口 = #2..#9，最小 RTT 变为 #9 的 40ms
        assertEquals(8, clock.state.sampleCount)
        assertEquals(900L, clock.state.offset.toMillis())
        assertEquals(40L, clock.state.roundTripDelay.toMillis())
    }

    @Test
    fun `reset 清空窗口回到未收敛`() = runBlocking {
        val localClock = FakeLocalClock(t1)
        val clock = SyncPlayClock(ScriptedTimeSource.of(localClock), localClock)
        clock.sampleOnce()
        assertTrue(clock.state.isSynchronized)

        clock.reset()

        assertEquals(SyncPlayClockState(), clock.state)
    }

    // —— 调度 ——

    @Test
    fun `调度序列为 1s 1s 1s 60s 60s`() = runBlocking {
        val localClock = FakeLocalClock(t1)
        val source = ScriptedTimeSource.of(localClock, samples = 5)
        val intervals = mutableListOf<Duration>()
        val pause = SyncPlayDelay { interval ->
            intervals += interval
            if (intervals.size == 5) throw CancellationException("测试结束")
        }
        val clock = SyncPlayClock(source, localClock, pause)

        try {
            clock.run()
        } catch (expected: CancellationException) {
            // 预期：虚拟 ticker 在记录满 5 个间隔后取消
        }

        assertEquals(
            listOf(1L, 1L, 1L, 60L, 60L).map { Duration.ofSeconds(it) },
            intervals,
        )
        // 第 5 次等待时协程被取消，因此只完成 4 次采样
        assertEquals(4, source.calls)
        assertEquals(4, clock.state.sampleCount)
    }

    @Test
    fun `单次采样失败不终止轮询且保持贪婪间隔`() = runBlocking {
        val localClock = FakeLocalClock(t1)
        val source =
            ScriptedTimeSource(
                localClock,
                listOf(
                    FetchScript.Failure,
                    FetchScript.Success(serverOffsetMs = 1_000, oneWayMs = 10),
                    FetchScript.Success(serverOffsetMs = 1_000, oneWayMs = 10),
                    FetchScript.Success(serverOffsetMs = 1_000, oneWayMs = 10),
                    FetchScript.Success(serverOffsetMs = 1_000, oneWayMs = 10),
                ),
            )
        val intervals = mutableListOf<Duration>()
        val pause = SyncPlayDelay { interval ->
            intervals += interval
            if (intervals.size == 5) throw CancellationException("测试结束")
        }
        val clock = SyncPlayClock(source, localClock, pause)

        try {
            clock.run()
        } catch (expected: CancellationException) {
            // 预期
        }

        // 失败那轮没攒到样本，仍走 1s；攒满 3 个样本后切 60s
        assertEquals(listOf(1L, 1L, 1L, 1L, 60L).map { Duration.ofSeconds(it) }, intervals)
        assertEquals(3, clock.state.sampleCount)
        assertTrue(clock.state.isSynchronized)
    }
}

/** 可手动推进的本地时钟。 */
private class FakeLocalClock(start: Instant) : SyncPlayLocalClock {
    var current: Instant = start
        private set

    override fun now(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}

/** 每次 `fetchServerTime()` 的动作脚本。 */
private sealed interface FetchScript {
    /** 服务器时刻 = 本地时刻 + [serverOffsetMs]；单程网络 [oneWayMs]，服务器处理 10ms。 */
    data class Success(
        val serverOffsetMs: Long,
        val oneWayMs: Long,
        val serverProcessingMs: Long = 10,
    ) : FetchScript

    /** 模拟网络 / 401 / 端点缺失。 */
    data object Failure : FetchScript
}

/**
 * 按脚本推进本地时钟并返回服务器时间戳的 fake 时间源。
 *
 * 请求发出后本地时钟先走一个单程，服务器收到并在 10ms 后回包，本地再走一个单程 —— 与真实链路的四时间戳一致。
 */
private class ScriptedTimeSource(
    private val localClock: FakeLocalClock,
    private val scripts: List<FetchScript>,
) : SyncPlayTimeSource {
    var calls: Int = 0
        private set

    override suspend fun fetchServerTime(): SyncPlayServerTimeSample {
        val script = scripts[calls]
        calls++
        when (script) {
            FetchScript.Failure -> throw IOException("fake 网络失败")
            is FetchScript.Success -> {
                val oneWay = Duration.ofMillis(script.oneWayMs)
                val processing = Duration.ofMillis(script.serverProcessingMs)
                val reception =
                    localClock.now().plus(oneWay).plus(Duration.ofMillis(script.serverOffsetMs))
                localClock.advance(oneWay.multipliedBy(2).plus(processing))
                return SyncPlayServerTimeSample(
                    requestReceptionTime = reception,
                    responseTransmissionTime = reception.plus(processing),
                )
            }
        }
    }

    companion object {
        /** [samples] 条相同的成功脚本：服务器快 5s、单程 30ms、处理 10ms。 */
        fun of(
            localClock: FakeLocalClock,
            samples: Int = 5,
            serverOffsetMs: Long = 0,
            oneWayMs: Long = 10,
        ): ScriptedTimeSource =
            ScriptedTimeSource(
                localClock,
                List(samples) { FetchScript.Success(serverOffsetMs, oneWayMs) },
            )
    }
}
