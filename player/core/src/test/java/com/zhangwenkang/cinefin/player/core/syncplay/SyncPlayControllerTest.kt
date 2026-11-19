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
        fixture.settleSeek()
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

    // —— 定位 in-flight 抑制（W4-T8c 缺陷修复：seek 风暴） ——

    @Test
    fun `起播定位后读侧滞后不会连环发校正 seek`() = loop { fixture ->
        fixture.join(synced = true)
        // 读侧在 seek 后 600ms 才更新（mpv 解码器重建期的表现）
        fixture.port.seekLatencyMs = 600
        fixture.controller.onCommand(fixture.play(whenAt = fixture.now(), atSeconds = 10))
        assertEquals(listOf(10_000L), fixture.port.seekTargets)

        // in-flight 未落定：每轮评估都挂起，一轮都不该再发 seek
        repeat(2) {
            fixture.advance(250)
            fixture.tick()
        }
        assertEquals(
            "读侧滞后期间不应重复定位（旧实现这里会每轮发一次）",
            listOf(10_000L),
            fixture.port.seekTargets,
        )

        // 落定后只允许「一次」带提前量的补偿 seek（瞄准 seek 延迟）
        repeat(4) {
            fixture.advance(250)
            fixture.tick()
        }
        assertEquals("落定后应只补一次提前量 seek", 2, fixture.port.seekTargets.size)
        assertTrue(
            "补偿 seek 应带提前量：${fixture.port.seekTargets}",
            fixture.port.seekTargets[1] > 10_000L,
        )

        // 补偿 seek 落定后护栏内不再新增（旧实现这里是每 2s 一次的无尽循环）
        repeat(3) {
            fixture.advance(250)
            fixture.tick()
        }
        assertEquals(2, fixture.port.seekTargets.size)
    }

    @Test
    fun `校正 seek 在落点确认前不发新 seek`() = loop { fixture ->
        fixture.join(synced = true)
        fixture.playAtTenSeconds()
        fixture.port.seekLatencyMs = 800

        fixture.advance(250)
        fixture.port.positionMs = 11_000
        fixture.tick()
        assertEquals(listOf(10_250L), fixture.port.seekTargets)

        // 确认要 800ms 才来：窗口内读侧仍停在 11_000，但不得再发校正 seek
        repeat(3) {
            fixture.advance(250)
            fixture.tick()
        }
        assertEquals(listOf(10_250L), fixture.port.seekTargets)
    }

    @Test
    fun `确认后锚点取确认落点而不是 intended target`() = loop { fixture ->
        fixture.join(synced = true)
        fixture.playAtTenSeconds()
        // 引擎实际落在请求目标之前 200ms
        fixture.port.landingOffsetMs = -200

        fixture.advance(250)
        // 期望 10_250（锚点 10_000 + 250ms），读侧 10_700 → 领先 450ms > 400ms 触发校正 seek
        fixture.port.positionMs = 10_700
        fixture.tick()
        assertEquals(listOf(10_250L), fixture.port.seekTargets)

        // 确认落点 10_050：锚点 (t0+500, 10_050)
        fixture.advance(250)
        fixture.tick()

        // 读侧 = 确认落点 + 250ms。按确认落点外推漂移 0（Hold）；
        // 若误按 intended target(10_250) 外推，期望 10_500 → 漂移 -200 → 1.05x。
        fixture.port.positionMs = 10_300
        fixture.advance(250)
        fixture.tick()

        assertEquals(1.0f, fixture.port.rateValue, 0.0001f)
        assertEquals(listOf(10_250L), fixture.port.seekTargets)
    }

    @Test
    fun `连续校正 seek 至少间隔 2 秒`() = loop { fixture ->
        fixture.join(synced = true)
        fixture.playAtTenSeconds()

        fixture.advance(250)
        // 期望 10_250，读侧 10_700 → 领先 450ms 触发第一次校正 seek
        fixture.port.positionMs = 10_700
        fixture.tick()
        assertEquals(1, fixture.port.seekTargets.size)

        // 收尾确认并重锚点
        fixture.advance(250)
        fixture.tick()

        // 立刻又越限：护栏拦住
        fixture.port.positionMs = 11_500
        fixture.advance(250)
        fixture.tick()
        assertEquals("两次校正 seek 之间至少要隔 2s", 1, fixture.port.seekTargets.size)

        // 距第一次校正满 2s：放行
        fixture.advance(1_500)
        fixture.port.positionMs = 13_000
        fixture.tick()
        assertEquals(2, fixture.port.seekTargets.size)
    }

    @Test
    fun `大跳落定前读侧停在旧值时持续挂起不发新 seek`() = loop { fixture ->
        fixture.join(synced = true)
        // 大跳（组把本地从 380s 拉回 0.4s）：mpv 要重缓冲数秒，读侧一直停在旧位置 0
        fixture.port.seekLatencyMs = 6_000
        fixture.controller.onCommand(fixture.play(whenAt = fixture.now(), atSeconds = 10))
        assertEquals(1, fixture.port.seekTargets.size)

        // 旧实现在 2s 超时后按旧读数重锚点，每 2.4s 再发一次（真机取证即此节奏）
        repeat(20) {
            fixture.advance(250)
            fixture.tick()
        }
        assertEquals("落定前读侧停在旧值，一轮都不该重发", 1, fixture.port.seekTargets.size)
    }

    @Test
    fun `读侧落到 seek 目标附近即视为落定并重锚点`() = loop { fixture ->
        fixture.join(synced = true)
        fixture.playAtTenSeconds()
        // sink 一直停在 Seeking（不确认），但引擎实际已把位置跳到目标
        fixture.port.confirmsSeeks = false
        fixture.port.seekLatencyMs = 1_000

        fixture.advance(250)
        fixture.port.positionMs = 10_700
        fixture.tick()
        assertEquals(listOf(10_250L), fixture.port.seekTargets)

        // 落定：读侧变成 10_250（= 目标）
        fixture.advance(1_000)
        fixture.tick()
        // 之后按新锚点外推：读侧跟着走就不再定位
        fixture.port.positionMs = 10_500
        fixture.advance(250)
        fixture.tick()

        assertEquals(listOf(10_250L), fixture.port.seekTargets)
    }

    @Test
    fun `播放中读侧前进本身不算落定依据`() = loop { fixture ->
        fixture.join(synced = true)
        fixture.playAtTenSeconds()
        // 不确认、读侧缓慢前进（播放中位置本来就在走），但离目标很远
        fixture.port.confirmsSeeks = false
        fixture.port.seekLatencyMs = 20_000

        fixture.advance(250)
        fixture.port.positionMs = 10_700
        fixture.tick()
        assertEquals(1, fixture.port.seekTargets.size)

        repeat(6) {
            fixture.advance(250)
            fixture.port.positionMs = (fixture.port.positionMs ?: 0L) + 250
            fixture.tick()
        }
        assertEquals("读侧前进不等于落定，不能重发", 1, fixture.port.seekTargets.size)
    }

    @Test
    fun `播放口报定位失败时立刻按读侧位置收尾`() = loop { fixture ->
        fixture.join(synced = true)
        fixture.playAtTenSeconds()
        fixture.port.confirmsSeeks = false
        fixture.port.seekFails = true
        fixture.port.seekLatencyMs = 5_000

        fixture.advance(250)
        fixture.port.positionMs = 10_700
        fixture.tick()
        assertEquals(1, fixture.port.seekTargets.size)

        // 失败信号到达：本轮就收尾，不补发
        fixture.advance(250)
        fixture.tick()
        assertEquals(1, fixture.port.seekTargets.size)
    }

    @Test
    fun `读侧读不到位置时跳过漂移评估`() = loop { fixture ->
        fixture.join(synced = true)
        fixture.playAtTenSeconds()

        fixture.port.positionMs = null
        repeat(4) {
            fixture.advance(250)
            fixture.tick()
        }

        assertTrue("读不到位置时不应变速或定位：${fixture.port.calls}", fixture.port.calls.isEmpty())
        assertTrue(fixture.port.seekTargets.isEmpty())
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
    val port = FakePlaybackPort { localClock.now() }
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

    /**
     * 起播在 10s：锚点 = 当前时刻 / 10_000ms，之后每一步只动 [FakePlaybackPort.positionMs]。
     *
     * 起播自带的定位会登记 in-flight；先把落点确认收尾，锚点才落在「当前时刻 / 10_000ms」。
     */
    suspend fun playAtTenSeconds() {
        controller.onCommand(play(whenAt = now(), atSeconds = 10))
        settleSeek()
        port.calls.clear()
        port.seekTargets.clear()
    }

    /** 让驱动循环把上一笔定位的 in-flight 收掉（不推进时间，也不产生新的漂移动作）。 */
    suspend fun settleSeek() {
        repeat(4) {
            if (!controller.awaitingSeekConfirmation) return
            tick()
        }
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

private class FakePlaybackPort(private val nowProvider: () -> Instant = { Instant.EPOCH }) :
    SyncPlayPlaybackPort {
    /** 读侧当前位置；null = 引擎未就绪 / 正在重建，读不到可信值。 */
    var positionMs: Long? = 0
    var playing: Boolean = false
    var rateValue: Float = 1.0f

    /** seek 后读侧滞后多久才反映新位置（同时延迟落点确认）：mpv 解码器重建期的读侧表现。 */
    var seekLatencyMs: Long = 0

    /** 落点相对请求目标的偏差（引擎落点误差 / 关键帧对齐）。 */
    var landingOffsetMs: Long = 0

    /** false = 这个实现不提供落点确认（走控制器的兜底收尾）。 */
    var confirmsSeeks: Boolean = true

    /** true = 当前定位被播放口判为失败（sink 拒绝 / 引擎错误）。 */
    var seekFails: Boolean = false

    val calls: MutableList<String> = mutableListOf()

    /** 每次 seek 的目标位置（区分「发了多少次定位」，calls 里混有倍速与 play/pause）。 */
    val seekTargets: MutableList<Long> = mutableListOf()

    private var pendingLanding: PendingLanding? = null
    private var confirmation: SyncPlaySeekConfirmation? = null

    private data class PendingLanding(val requestId: Long, val landingMs: Long, val at: Instant)

    override fun play() {
        playing = true
        calls += "play"
    }

    override fun pause() {
        playing = false
        calls += "pause"
    }

    override fun seekTo(positionMs: Long, requestId: Long) {
        seekTargets += positionMs
        calls += "seek($positionMs)"
        val landing = positionMs + landingOffsetMs
        if (seekLatencyMs <= 0) {
            this.positionMs = landing
            if (confirmsSeeks) {
                confirmation = SyncPlaySeekConfirmation(requestId, landing)
            }
        } else {
            pendingLanding =
                PendingLanding(requestId, landing, nowProvider().plusMillis(seekLatencyMs))
        }
    }

    override fun setRate(rate: Float) {
        rateValue = rate
        calls += "rate($rate)"
    }

    override val currentPositionMs: Long?
        get() {
            settleLanding()
            return positionMs
        }

    override val isPlaying: Boolean
        get() = playing

    override val seekConfirmation: SyncPlaySeekConfirmation?
        get() {
            settleLanding()
            return confirmation
        }

    override val lastSeekFailed: Boolean
        get() = seekFails

    /** 滞后窗口过去后读侧才看到新位置、落点确认才出现。 */
    private fun settleLanding() {
        val pending = pendingLanding ?: return
        if (nowProvider().isBefore(pending.at)) return
        positionMs = pending.landingMs
        if (confirmsSeeks) {
            confirmation = SyncPlaySeekConfirmation(pending.requestId, pending.landingMs)
        }
        pendingLanding = null
    }
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
