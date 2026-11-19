package com.zhangwenkang.cinefin.player.core.syncplay

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W4-T8c：SyncPlayController 状态机（VIDEO-37/39/40/41/43）。
 *
 * 全部用虚拟本地时钟 + 手动 ticker 驱动：不睡真实时间、不触网、播放口全 fake。命令 / 事件落态是同步的， [SyncPlayController.run]
 * 只负责到点触发、时钟收敛轮询与漂移 tick。
 */
class SyncPlayControllerTest {

    // —— 七态迁移 ——

    @Test
    fun `初始态为 NotInGroup`() {
        val fixture = Fixture()

        assertEquals(SyncPlayControllerState.NotInGroup, fixture.controller.state.value)
        assertNull(fixture.controller.activeGroupId)
    }

    @Test
    fun `onJoinRequested 进入 Joining`() {
        val fixture = Fixture()

        fixture.controller.onJoinRequested(fixture.groupId)

        assertEquals(SyncPlayControllerState.Joining, fixture.controller.state.value)
        assertEquals(fixture.groupId, fixture.controller.activeGroupId)
    }

    @Test
    fun `加入完成且时钟未收敛进入 ClockWarming`() {
        val fixture = Fixture()
        fixture.controller.onJoinRequested(fixture.groupId)

        fixture.controller.onGroupUpdate(fixture.joined())

        assertEquals(SyncPlayControllerState.ClockWarming, fixture.controller.state.value)
    }

    @Test
    fun `加入完成且时钟已收敛直接进入 Idle`() {
        val fixture = Fixture()

        fixture.join(synced = true)

        assertEquals(SyncPlayControllerState.Idle, fixture.controller.state.value)
    }

    @Test
    fun `加入失败回到 NotInGroup`() {
        val fixture = Fixture()
        fixture.controller.onJoinRequested(fixture.groupId)

        fixture.controller.onJoinFailed()

        assertEquals(SyncPlayControllerState.NotInGroup, fixture.controller.state.value)
        assertNull(fixture.controller.activeGroupId)
    }

    @Test
    fun `未入组时命令被丢弃`() {
        val fixture = Fixture()

        fixture.controller.onCommand(fixture.play(whenAt = fixture.now(), atSeconds = 10))

        assertEquals(SyncPlayControllerState.NotInGroup, fixture.controller.state.value)
        assertTrue(fixture.port.calls.isEmpty())
    }

    @Test
    fun `其他组的命令与踢出事件被忽略`() {
        val fixture = Fixture()
        fixture.join(synced = true)
        val other = UUID.randomUUID()

        fixture.controller.onCommand(
            fixture.play(whenAt = fixture.now(), atSeconds = 10, group = other)
        )
        fixture.controller.onGroupUpdate(SyncPlayGroupUpdate.GroupLeft(other))

        assertEquals(SyncPlayControllerState.Idle, fixture.controller.state.value)
        assertEquals(fixture.groupId, fixture.controller.activeGroupId)
        assertTrue(fixture.port.calls.isEmpty())
    }

    @Test
    fun `UserJoined UserLeft StateChanged 不改状态`() {
        val fixture = Fixture()
        fixture.join(synced = true)

        fixture.controller.onGroupUpdate(SyncPlayGroupUpdate.UserJoined(fixture.groupId, "bob"))
        fixture.controller.onGroupUpdate(SyncPlayGroupUpdate.UserLeft(fixture.groupId, "bob"))
        fixture.controller.onGroupUpdate(
            SyncPlayGroupUpdate.StateChanged(
                fixture.groupId,
                SyncPlayGroupStateUpdate(
                    SyncPlayGroupState.PLAYING,
                    SyncPlayPlaybackRequest.PLAY,
                ),
            )
        )
        fixture.controller.onGroupUpdate(SyncPlayGroupUpdate.Unknown(fixture.groupId))

        assertEquals(SyncPlayControllerState.Idle, fixture.controller.state.value)
        assertTrue(fixture.port.calls.isEmpty())
    }

    @Test
    fun `GroupLeft 回 NotInGroup 并重置时钟`() = loop { fixture ->
        fixture.join(synced = true)
        assertTrue(fixture.clock.state.isSynchronized)

        fixture.controller.onGroupUpdate(SyncPlayGroupUpdate.GroupLeft(fixture.groupId))

        assertEquals(SyncPlayControllerState.NotInGroup, fixture.controller.state.value)
        assertNull(fixture.controller.activeGroupId)
        assertFalse(fixture.clock.state.isSynchronized)
    }

    @Test
    fun `三种服务器踢出事件都回 NotInGroup`() {
        val leaveUpdates =
            listOf<(UUID) -> SyncPlayGroupUpdate>(
                { SyncPlayGroupUpdate.NotInGroup(it) },
                { SyncPlayGroupUpdate.GroupDoesNotExist(it) },
                { SyncPlayGroupUpdate.LibraryAccessDenied(it) },
            )

        for (leave in leaveUpdates) {
            val fixture = Fixture()
            fixture.join(synced = true)

            fixture.controller.onGroupUpdate(leave(fixture.groupId))

            assertEquals(SyncPlayControllerState.NotInGroup, fixture.controller.state.value)
            assertFalse(fixture.clock.state.isSynchronized)
        }
    }

    @Test
    fun `主动退组回 NotInGroup 并把倍速恢复 1x`() = loop { fixture ->
        fixture.join(synced = true)
        fixture.controller.onCommand(fixture.play(whenAt = fixture.now(), atSeconds = 10))
        fixture.port.positionMs = 10_400
        fixture.tick()
        assertEquals(0.95f, fixture.port.rateValue, 0.0001f)

        fixture.controller.leaveGroup()

        assertEquals(SyncPlayControllerState.NotInGroup, fixture.controller.state.value)
        assertEquals(1.0f, fixture.port.rateValue, 0.0001f)
    }

    // —— Armed 调度 ——

    @Test
    fun `未来的 Play 命令进入 Armed 且不碰播放口`() {
        val fixture = Fixture()
        fixture.join(synced = true)

        fixture.controller.onCommand(
            fixture.play(whenAt = fixture.now().plusMillis(2_000), atSeconds = 10)
        )

        assertEquals(SyncPlayControllerState.Armed, fixture.controller.state.value)
        assertTrue(fixture.port.calls.isEmpty())
    }

    @Test
    fun `Armed 到点触发播放并落在 positionTicks`() = loop { fixture ->
        fixture.join(synced = true)
        val whenAt = fixture.now().plusMillis(1_000)
        fixture.controller.onCommand(fixture.play(whenAt = whenAt, atSeconds = 100))
        assertEquals(SyncPlayControllerState.Armed, fixture.controller.state.value)

        // 未到点：空转一轮也不该动播放口
        fixture.tick()
        assertTrue(fixture.port.calls.isEmpty())
        assertEquals(SyncPlayControllerState.Armed, fixture.controller.state.value)

        fixture.advance(1_000)
        fixture.tick()

        assertEquals(SyncPlayControllerState.Playing, fixture.controller.state.value)
        assertEquals(listOf("seek(100000)", "play"), fixture.port.calls)
        assertTrue(fixture.port.isPlaying)
    }

    @Test
    fun `未来的 Pause 命令到点暂停在 positionTicks`() = loop { fixture ->
        fixture.join(synced = true)
        fixture.controller.onCommand(fixture.play(whenAt = fixture.now(), atSeconds = 10))
        fixture.port.calls.clear()

        fixture.advance(500)
        fixture.controller.onCommand(
            fixture.pause(whenAt = fixture.now().plusMillis(500), atSeconds = 20)
        )
        assertEquals(SyncPlayControllerState.Armed, fixture.controller.state.value)

        fixture.advance(500)
        fixture.tick()

        assertEquals(SyncPlayControllerState.Paused, fixture.controller.state.value)
        assertEquals(listOf("seek(20000)", "pause"), fixture.port.calls)
        assertEquals(20_000L, fixture.port.positionMs)
    }

    @Test
    fun `未来的 Seek 到点后回到 Armed 之前的播放态`() = loop { fixture ->
        fixture.join(synced = true)
        fixture.controller.onCommand(fixture.play(whenAt = fixture.now(), atSeconds = 10))
        fixture.port.calls.clear()

        fixture.controller.onCommand(
            fixture.seek(whenAt = fixture.now().plusMillis(400), atSeconds = 30)
        )
        assertEquals(SyncPlayControllerState.Armed, fixture.controller.state.value)

        fixture.advance(400)
        fixture.tick()

        assertEquals(SyncPlayControllerState.Playing, fixture.controller.state.value)
        assertEquals(listOf("seek(30000)"), fixture.port.calls)
    }

    // —— 晚到命令 ——

    @Test
    fun `晚到的 Play 命令按 positionTicks 加 elapsed 直跳不补播`() {
        val fixture = Fixture()
        fixture.join(synced = true)

        fixture.advance(1_500)
        fixture.controller.onCommand(
            fixture.play(whenAt = fixture.now().minusMillis(1_500), atSeconds = 10)
        )

        // 10_000ms + 1_500ms × 1.0 = 11_500ms
        assertEquals(SyncPlayControllerState.Playing, fixture.controller.state.value)
        assertEquals(listOf("seek(11500)", "play"), fixture.port.calls)
    }

    @Test
    fun `晚到的 Pause 命令不做 elapsed 补偿`() {
        val fixture = Fixture()
        fixture.join(synced = true)

        fixture.advance(2_000)
        fixture.controller.onCommand(
            fixture.pause(whenAt = fixture.now().minusMillis(2_000), atSeconds = 10)
        )

        // 该时刻起组已冻结：期望位置就是 positionTicks，不按 elapsed 外推
        assertEquals(SyncPlayControllerState.Paused, fixture.controller.state.value)
        assertEquals(listOf("seek(10000)", "pause"), fixture.port.calls)
    }

    @Test
    fun `晚到的 Seek 命令按 positionTicks 加 elapsed 直跳`() {
        val fixture = Fixture()
        fixture.join(synced = true)

        fixture.advance(500)
        fixture.controller.onCommand(
            fixture.seek(whenAt = fixture.now().minusMillis(500), atSeconds = 10)
        )

        assertEquals(SyncPlayControllerState.Idle, fixture.controller.state.value)
        assertEquals(listOf("seek(10500)"), fixture.port.calls)
    }

    @Test
    fun `立即执行的 Pause 与 STOP 迁移`() {
        val fixture = Fixture()
        fixture.join(synced = true)
        fixture.controller.onCommand(fixture.play(whenAt = fixture.now(), atSeconds = 10))

        fixture.controller.onCommand(fixture.pause(whenAt = fixture.now(), atSeconds = 12))
        assertEquals(SyncPlayControllerState.Paused, fixture.controller.state.value)
        assertFalse(fixture.port.isPlaying)

        fixture.controller.onCommand(fixture.stop())
        assertEquals(SyncPlayControllerState.Idle, fixture.controller.state.value)
    }

    // —— 时钟未收敛时的挂起 ——

    @Test
    fun `ClockWarming 下命令挂起直到时钟收敛`() = loop { fixture ->
        fixture.join(synced = false)
        assertEquals(SyncPlayControllerState.ClockWarming, fixture.controller.state.value)

        fixture.controller.onCommand(fixture.play(whenAt = fixture.now(), atSeconds = 5))
        assertEquals(SyncPlayControllerState.ClockWarming, fixture.controller.state.value)
        assertTrue(fixture.port.calls.isEmpty())

        fixture.syncClock()
        fixture.tick()

        assertEquals(SyncPlayControllerState.Playing, fixture.controller.state.value)
        assertEquals(listOf("seek(5000)", "play"), fixture.port.calls)
    }

    @Test
    fun `ClockWarming 下挂起的未来命令在收敛后进入 Armed`() = loop { fixture ->
        fixture.join(synced = false)
        fixture.advance(1_000)
        fixture.controller.onCommand(
            fixture.play(whenAt = fixture.now().plusMillis(1_000), atSeconds = 5)
        )

        fixture.syncClock()
        fixture.tick()
        assertEquals(SyncPlayControllerState.Armed, fixture.controller.state.value)

        fixture.advance(1_000)
        fixture.tick()
        assertEquals(SyncPlayControllerState.Playing, fixture.controller.state.value)
        assertEquals(listOf("seek(5000)", "play"), fixture.port.calls)
    }

    @Test
    fun `后到的命令覆盖先挂起的命令`() = loop { fixture ->
        fixture.join(synced = false)
        fixture.controller.onCommand(fixture.play(whenAt = fixture.now(), atSeconds = 5))
        fixture.controller.onCommand(fixture.pause(whenAt = fixture.now(), atSeconds = 9))

        fixture.syncClock()
        fixture.tick()

        assertEquals(SyncPlayControllerState.Paused, fixture.controller.state.value)
        assertEquals(listOf("seek(9000)", "pause"), fixture.port.calls)
    }

    // —— 漂移校正：Exo 档 60 / 400 ——

    @Test
    fun `Exo 档 50ms 忽略 100ms 变速 20ms 恢复 500ms 跳`() = loop { fixture ->
        fixture.join(synced = true)
        fixture.playAtTenSeconds()

        // 50ms < 60ms：忽略
        fixture.port.positionMs = 10_050
        fixture.tick()
        assertEquals(1.0f, fixture.port.rateValue, 0.0001f)
        assertTrue(fixture.port.calls.isEmpty())

        // +100ms：领先 → 0.95x
        fixture.port.positionMs = 10_100
        fixture.tick()
        assertEquals(0.95f, fixture.port.rateValue, 0.0001f)

        // 追帧中回到 20ms < 30ms：恢复 1.0x（滞回）
        fixture.port.positionMs = 10_020
        fixture.tick()
        assertEquals(1.0f, fixture.port.rateValue, 0.0001f)

        // 落后 100ms → 1.05x
        fixture.port.positionMs = 9_900
        fixture.tick()
        assertEquals(1.05f, fixture.port.rateValue, 0.0001f)

        // +500ms > 400ms：直接 seek 到组期望位置（10_000ms）
        fixture.port.positionMs = 10_500
        fixture.tick()
        assertEquals(1.0f, fixture.port.rateValue, 0.0001f)
        assertEquals(10_000L, fixture.port.positionMs)
        assertEquals(
            listOf("rate(0.95)", "rate(1.0)", "rate(1.05)", "seek(10000)", "rate(1.0)"),
            fixture.port.calls,
        )
    }

    // —— 漂移校正：mpv 档 150 / 600 ——

    @Test
    fun `mpv 档 100ms 忽略 200ms 变速 700ms 跳`() =
        loop(SyncPlayDriftProfile.Mpv) { fixture ->
            fixture.join(synced = true)
            fixture.playAtTenSeconds()

            // 100ms < 150ms：忽略
            fixture.port.positionMs = 10_100
            fixture.tick()
            assertEquals(1.0f, fixture.port.rateValue, 0.0001f)
            assertTrue(fixture.port.calls.isEmpty())

            // +200ms：领先 → 0.95x
            fixture.port.positionMs = 10_200
            fixture.tick()
            assertEquals(0.95f, fixture.port.rateValue, 0.0001f)

            // +700ms > 600ms：seek 回组期望位置，倍速回 1.0
            fixture.port.positionMs = 10_700
            fixture.tick()
            assertEquals(1.0f, fixture.port.rateValue, 0.0001f)
            assertEquals(10_000L, fixture.port.positionMs)
        }

    @Test
    fun `引擎报告不在播时不做漂移校正`() = loop { fixture ->
        fixture.join(synced = true)
        fixture.playAtTenSeconds()

        fixture.port.positionMs = 11_000
        fixture.port.playing = false
        fixture.tick()

        assertTrue(fixture.port.calls.isEmpty())
        assertEquals(1.0f, fixture.port.rateValue, 0.0001f)
    }

    // —— 纯判定函数边界 ——

    @Test
    fun `Exo 档判定边界 60 与 400`() {
        val profile = SyncPlayDriftProfile.Exo

        assertEquals(SyncPlayDriftAction.Hold, decide(59, profile))
        assertEquals(SyncPlayDriftAction.AdjustRate(0.95f), decide(60, profile))
        assertEquals(SyncPlayDriftAction.AdjustRate(1.05f), decide(-60, profile))
        assertEquals(SyncPlayDriftAction.AdjustRate(0.95f), decide(400, profile))
        assertEquals(SyncPlayDriftAction.Seek(7_777L), decide(401, profile))
    }

    @Test
    fun `mpv 档判定边界 150 与 600`() {
        val profile = SyncPlayDriftProfile.Mpv

        assertEquals(SyncPlayDriftAction.Hold, decide(149, profile))
        assertEquals(SyncPlayDriftAction.AdjustRate(0.95f), decide(150, profile))
        assertEquals(SyncPlayDriftAction.AdjustRate(0.95f), decide(600, profile))
        assertEquals(SyncPlayDriftAction.Seek(7_777L), decide(601, profile))
    }

    @Test
    fun `追帧滞回带 30ms 才恢复标称倍速`() {
        val profile = SyncPlayDriftProfile.Exo

        assertEquals(SyncPlayDriftAction.RestoreRate, decide(29, profile, appliedRate = 0.95f))
        assertEquals(SyncPlayDriftAction.Hold, decide(30, profile, appliedRate = 0.95f))
        assertEquals(SyncPlayDriftAction.Hold, decide(30, profile, appliedRate = 1.0f))
    }

    @Test
    fun `分档按内核标识选择`() {
        assertEquals(SyncPlayDriftProfile.Exo, SyncPlayDriftProfile.forBackend("exoplayer"))
        assertEquals(SyncPlayDriftProfile.Mpv, SyncPlayDriftProfile.forBackend("mpv"))
    }

    private fun decide(
        driftMs: Long,
        profile: SyncPlayDriftProfile,
        appliedRate: Float = profile.nominalRate,
    ): SyncPlayDriftAction = SyncPlayDrift.decide(driftMs, appliedRate, profile, 7_777L)

    // —— 测试夹具 ——

    /** 起一个驱动循环跑 [body]；退出时取消循环。 */
    private fun loop(
        profile: SyncPlayDriftProfile = SyncPlayDriftProfile.Exo,
        body: suspend (Fixture) -> Unit,
    ) = runBlocking {
        val fixture = Fixture(profile)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { fixture.controller.run() }
        try {
            body(fixture)
        } finally {
            job.cancel()
        }
    }
}

private class Fixture(profile: SyncPlayDriftProfile = SyncPlayDriftProfile.Exo) {
    val start: Instant = Instant.parse("2026-11-19T12:00:00Z")
    val groupId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val playlistItemId: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private val localClock = VirtualLocalClock(start)
    val clock = SyncPlayClock(FixedTimeSource(localClock), localClock)
    val port = FakePlaybackPort()
    val ticker = ManualTicker()
    val controller = SyncPlayController(port, clock, profile, ticker)

    fun now(): Instant = localClock.now()

    fun advance(ms: Long) {
        localClock.advance(Duration.ofMillis(ms))
    }

    suspend fun syncClock() {
        clock.sampleOnce()
    }

    fun joined(): SyncPlayGroupUpdate.GroupJoined =
        SyncPlayGroupUpdate.GroupJoined(
            groupId,
            SyncPlayGroup(groupId, "组", SyncPlayGroupState.IDLE, emptyList(), now()),
        )

    fun join(synced: Boolean) {
        if (synced) {
            runBlocking { clock.sampleOnce() }
        }
        controller.onJoinRequested(groupId)
        controller.onGroupUpdate(joined())
    }

    fun play(whenAt: Instant, atSeconds: Long, group: UUID = groupId): SyncPlayCommand =
        command(SyncPlayCommandType.UNPAUSE, whenAt, atSeconds, group)

    fun pause(whenAt: Instant, atSeconds: Long): SyncPlayCommand =
        command(SyncPlayCommandType.PAUSE, whenAt, atSeconds, groupId)

    fun seek(whenAt: Instant, atSeconds: Long): SyncPlayCommand =
        command(SyncPlayCommandType.SEEK, whenAt, atSeconds, groupId)

    fun stop(): SyncPlayCommand =
        SyncPlayCommand(groupId, playlistItemId, SyncPlayCommandType.STOP, now(), null, now())

    /** 起播在 10s：锚点 = 当前时刻 / 10_000ms，之后每一步只动 [FakePlaybackPort.positionMs]。 */
    fun playAtTenSeconds() {
        controller.onCommand(play(whenAt = now(), atSeconds = 10))
        port.calls.clear()
    }

    /** 触发一次驱动循环：先放开 ticker，再让循环协程推进到下一次挂起。 */
    suspend fun tick() {
        ticker.fire()
        repeat(6) { yield() }
    }

    private fun command(
        type: SyncPlayCommandType,
        whenAt: Instant,
        atSeconds: Long,
        group: UUID,
    ): SyncPlayCommand =
        SyncPlayCommand(
            groupId = group,
            playlistItemId = playlistItemId,
            type = type,
            whenAt = whenAt,
            positionTicks = atSeconds * 1_000L * TICKS_PER_MILLISECOND,
            emittedAt = whenAt,
        )
}

private class FakePlaybackPort : SyncPlayPlaybackPort {
    var positionMs: Long = 0
    var playing: Boolean = false
    var rateValue: Float = 1.0f
    val calls: MutableList<String> = mutableListOf()

    override fun play() {
        playing = true
        calls += "play"
    }

    override fun pause() {
        playing = false
        calls += "pause"
    }

    override fun seekTo(positionMs: Long) {
        this.positionMs = positionMs
        calls += "seek($positionMs)"
    }

    override fun setRate(rate: Float) {
        this.rateValue = rate
        calls += "rate($rate)"
    }

    override val currentPositionMs: Long
        get() = positionMs

    override val isPlaying: Boolean
        get() = playing
}

/** 可手动推进的本地时钟（与 T8b 测试同款）。 */
private class VirtualLocalClock(start: Instant) : SyncPlayLocalClock {
    var current: Instant = start
        private set

    override fun now(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}

/** 一次采样即收敛的服务器时间源：offset 0、RTT 0。 */
private class FixedTimeSource(private val localClock: VirtualLocalClock) : SyncPlayTimeSource {
    override suspend fun fetchServerTime(): SyncPlayServerTimeSample {
        val now = localClock.now()
        return SyncPlayServerTimeSample(now, now)
    }
}

/** 手动 ticker：测试调用 [fire] 才返回；未挂上等待时把触发留到下一次 [await]。 */
private class ManualTicker : SyncPlayTicker {
    private var waiter: CompletableDeferred<Unit>? = null
    private var firePending = false

    override suspend fun await(duration: Duration) {
        if (firePending) {
            firePending = false
            return
        }
        val deferred = CompletableDeferred<Unit>()
        waiter = deferred
        try {
            deferred.await()
        } finally {
            waiter = null
        }
    }

    fun fire() {
        val current = waiter
        if (current == null) {
            firePending = true
        } else {
            current.complete(Unit)
        }
    }
}
