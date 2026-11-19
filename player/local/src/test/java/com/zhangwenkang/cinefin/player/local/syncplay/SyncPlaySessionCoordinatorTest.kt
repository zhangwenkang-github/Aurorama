package com.zhangwenkang.cinefin.player.local.syncplay

import com.zhangwenkang.cinefin.player.core.playback.PlaybackEngine
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayControllerState
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayDelay
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayDriftProfile
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayTicker
import com.zhangwenkang.cinefin.syncplay.SyncPlayCommand
import com.zhangwenkang.cinefin.syncplay.SyncPlayCommandType
import com.zhangwenkang.cinefin.syncplay.SyncPlayConnectionState
import com.zhangwenkang.cinefin.syncplay.SyncPlayError
import com.zhangwenkang.cinefin.syncplay.SyncPlayResult
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T8d：协调器的装配契约——双循环、WS 三路流只收一次、入组 / 退组 / 断线恢复 / 原地切内核。
 *
 * 时钟采样间隔压到 10ms 让 `isSynchronized` 快速成立；状态机 ticker 用「永不返回」的假实现， 只在入口（join / command /
 * 连接态）唤醒时求值，测试不睡真实时钟。
 */
class SyncPlaySessionCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val gateway = FakeSyncPlayGateway()
    private val host = FakeSyncPlayHost(scope = scope)
    private val coordinator = SyncPlaySessionCoordinator(gateway, host)

    private val groupId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val itemId = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa")

    init {
        coordinator.ticker = SyncPlayTicker { awaitCancellation() }
        coordinator.clockDelay = SyncPlayDelay { delay(10) }
        gateway.groupSnapshot = SyncPlayResult.Success(FakeSyncPlayGateway.group(groupId))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // —— 订阅契约 ——

    @Test
    fun `WS 三路流只收集一次——重复 start 入组 切内核 重连都不重收`() {
        coordinator.start()
        coordinator.start()
        awaitUntil { gateway.commandCollections.get() == 1 }
        awaitUntil { gateway.groupUpdateCollections.get() == 1 }

        joinAndAwaitInGroup()
        host.backendName = PlaybackEngine.BACKEND_MPV
        host.engineImpl = FakePlaybackEngine(PlaybackEngine.BACKEND_MPV)
        host.notifyBackendRebound()
        gateway.setConnectionState(SyncPlayConnectionState.DISCONNECTED)
        gateway.setConnectionState(SyncPlayConnectionState.CONNECTED)
        awaitUntil { gateway.calls.count { it.startsWith("getGroup") } >= 2 }

        assertEquals(1, gateway.commandCollections.get())
        assertEquals(1, gateway.groupUpdateCollections.get())
        assertEquals(1, gateway.connectionCollections.get())
    }

    // —— 入组 ——

    @Test
    fun `joinGroup 成功——Joining 解决、闸门开启、组快照落地`() {
        coordinator.start()
        awaitClockSync()

        val outcome = runSuspending { coordinator.joinGroup(groupId) }

        assertEquals(SyncPlayJoinOutcome.Success, outcome)
        awaitUntil { coordinator.state.value == SyncPlayControllerState.Idle }
        assertEquals(groupId, coordinator.activeGroup.value?.id)
        assertTrue("闸门应开启", coordinator.gate.ownsTransport.value)
        assertEquals(listOf("joinGroup:$groupId"), gateway.calls.take(1))
    }

    @Test
    fun `joinGroup 失败——回到未入组且闸门关闭`() {
        coordinator.start()
        gateway.joinGroupResult = SyncPlayResult.Failure(SyncPlayError.Network("offline"))

        val outcome = runSuspending { coordinator.joinGroup(groupId) }

        assertTrue(outcome is SyncPlayJoinOutcome.Failure)
        assertEquals(SyncPlayControllerState.NotInGroup, coordinator.state.value)
        assertFalse(coordinator.gate.ownsTransport.value)
    }

    @Test
    fun `createAndJoin 直接吃建组快照 不再发 join`() {
        coordinator.start()
        awaitClockSync()
        gateway.createGroupResult =
            SyncPlayResult.Success(FakeSyncPlayGateway.group(groupId, name = "新建组"))

        val outcome = runSuspending { coordinator.createAndJoin("新建组") }

        assertEquals(SyncPlayJoinOutcome.Success, outcome)
        awaitUntil { coordinator.state.value == SyncPlayControllerState.Idle }
        assertEquals("新建组", coordinator.activeGroup.value?.name)
        assertTrue(gateway.calls.any { it.startsWith("createGroup:") })
        assertFalse("建组后不应再发 join", gateway.calls.any { it.startsWith("joinGroup") })
    }

    // —— 命令与漂移 ——

    @Test
    fun `组命令驱动状态机并落到播放口`() {
        coordinator.start()
        joinAndAwaitInGroup()

        gateway.emitCommand(groupCommand(SyncPlayCommandType.UNPAUSE))
        awaitUntil { coordinator.state.value == SyncPlayControllerState.Playing }

        assertTrue(host.engineImpl!!.calls.contains("play"))
    }

    @Test
    fun `组内定位走 seek sink 且带标签`() {
        coordinator.start()
        joinAndAwaitInGroup()

        gateway.emitCommand(groupCommand(SyncPlayCommandType.SEEK, positionTicks = 900_000_000L))
        awaitUntil { host.seeks.isNotEmpty() }

        assertEquals(SYNC_PLAY_SEEK_SOURCE, host.seeks.single().second)
        assertTrue(host.engineImpl!!.calls.none { it.startsWith("seekTo") })
    }

    // —— 退组 ——

    @Test
    fun `leaveGroup——REST 退组、状态复位、闸门关闭、倍速回 1`() {
        coordinator.start()
        joinAndAwaitInGroup()

        runSuspending { coordinator.leaveGroup() }

        assertEquals(SyncPlayControllerState.NotInGroup, coordinator.state.value)
        assertFalse(coordinator.gate.ownsTransport.value)
        assertEquals(null, coordinator.activeGroup.value)
        assertTrue("退组应调 REST", gateway.calls.contains("leaveGroup"))
        assertTrue("倍速应回 1.0", host.engineImpl!!.calls.contains("setRate:1.0"))
    }

    @Test
    fun `退组后组内事件被忽略 不再拉回状态`() {
        coordinator.start()
        joinAndAwaitInGroup()
        runSuspending { coordinator.leaveGroup() }

        gateway.emitCommand(groupCommand(SyncPlayCommandType.UNPAUSE))
        Thread.sleep(50)

        assertEquals(SyncPlayControllerState.NotInGroup, coordinator.state.value)
    }

    // —— 断线恢复 ——

    @Test
    fun `断线重连先拉组快照再恢复 组态不被重置`() {
        coordinator.start()
        joinAndAwaitInGroup()
        val getGroupCallsBefore = gateway.calls.count { it.startsWith("getGroup") }

        gateway.setConnectionState(SyncPlayConnectionState.DISCONNECTED)
        gateway.setConnectionState(SyncPlayConnectionState.CONNECTED)
        awaitUntil { gateway.calls.count { it.startsWith("getGroup") } > getGroupCallsBefore }

        assertEquals(getGroupCallsBefore + 1, gateway.calls.count { it.startsWith("getGroup") })
        assertEquals(SyncPlayControllerState.Idle, coordinator.state.value)
        assertEquals(SyncPlayConnectionState.CONNECTED, coordinator.connectionState.value)
    }

    @Test
    fun `重连后组已不存在——按退组收尾`() {
        coordinator.start()
        joinAndAwaitInGroup()
        gateway.groupSnapshot = SyncPlayResult.Failure(SyncPlayError.GroupDoesNotExist(groupId))

        gateway.setConnectionState(SyncPlayConnectionState.DISCONNECTED)
        gateway.setConnectionState(SyncPlayConnectionState.CONNECTED)
        awaitUntil { coordinator.state.value == SyncPlayControllerState.NotInGroup }

        assertFalse(coordinator.gate.ownsTransport.value)
        assertNotNull(coordinator.connectionState.value)
    }

    // —— 原地切内核 ——

    @Test
    fun `切内核重建漂移档并按切前状态恢复 Playing 不重新 Join`() {
        coordinator.start()
        joinAndAwaitInGroup()
        gateway.emitCommand(groupCommand(SyncPlayCommandType.UNPAUSE))
        awaitUntil { coordinator.state.value == SyncPlayControllerState.Playing }
        assertEquals(SyncPlayDriftProfile.Exo, coordinator.driftProfile)

        host.backendName = PlaybackEngine.BACKEND_MPV
        val mpvEngine = FakePlaybackEngine(PlaybackEngine.BACKEND_MPV)
        host.engineImpl = mpvEngine
        host.notifyBackendRebound()

        awaitUntil { coordinator.state.value == SyncPlayControllerState.Playing }
        assertEquals(SyncPlayDriftProfile.Mpv, coordinator.driftProfile)
        assertTrue("恢复播放态应重新 play 新内核", mpvEngine.calls.contains("play"))
        assertEquals(1, gateway.calls.count { it.startsWith("joinGroup") })
        assertTrue(coordinator.gate.ownsTransport.value)
    }

    @Test
    fun `切内核后 Paused 态按暂停恢复`() {
        coordinator.start()
        joinAndAwaitInGroup()
        gateway.emitCommand(groupCommand(SyncPlayCommandType.PAUSE))
        awaitUntil { coordinator.state.value == SyncPlayControllerState.Paused }

        host.backendName = PlaybackEngine.BACKEND_MPV
        val mpvEngine = FakePlaybackEngine(PlaybackEngine.BACKEND_MPV)
        host.engineImpl = mpvEngine
        host.notifyBackendRebound()

        awaitUntil { coordinator.state.value == SyncPlayControllerState.Paused }
        assertEquals(SyncPlayDriftProfile.Mpv, coordinator.driftProfile)
        assertTrue(mpvEngine.calls.contains("pause"))
    }

    @Test
    fun `内核没换时切内核回调是空操作`() {
        coordinator.start()
        joinAndAwaitInGroup()

        host.notifyBackendRebound()

        assertEquals(SyncPlayDriftProfile.Exo, coordinator.driftProfile)
        assertEquals(SyncPlayControllerState.Idle, coordinator.state.value)
    }

    // —— 辅助 ——

    private fun joinAndAwaitInGroup() {
        awaitClockSync()
        runSuspending { coordinator.joinGroup(groupId) }
        awaitUntil { coordinator.state.value == SyncPlayControllerState.Idle }
    }

    private fun awaitClockSync() {
        // 时钟收敛后 join 才会直达 Idle；先等一轮采样完成（首采在 10ms 后）。
        Thread.sleep(40)
    }

    private fun groupCommand(
        type: SyncPlayCommandType,
        positionTicks: Long? = null,
    ): SyncPlayCommand =
        SyncPlayCommand(
            groupId = groupId,
            playlistItemId = itemId,
            type = type,
            whenAt = Instant.EPOCH,
            positionTicks = positionTicks,
            emittedAt = Instant.EPOCH,
        )

    private fun <T> runSuspending(block: suspend () -> T): T {
        // 测试主线程驱动：Unconfined 作用域下入口与状态机在同一调用栈执行。
        var failure: Throwable? = null
        var result: T? = null
        val done = java.util.concurrent.CountDownLatch(1)
        scope.launch {
            try {
                result = block()
            } catch (error: Throwable) {
                failure = error
            } finally {
                done.countDown()
            }
        }
        if (!done.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
            fail("挂起入口超时未返回")
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("等待条件超时：state=${coordinator.state.value}")
    }
}
