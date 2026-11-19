package com.zhangwenkang.cinefin.player.core.syncplay

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 状态机的七态（W4-T8c）。
 *
 * 迁移表见 [SyncPlayController] 的 KDoc。
 */
enum class SyncPlayControllerState {
    /** 未入组：初始态，也是被踢 / 主动退组后的终态。 */
    NotInGroup,

    /** 已发起加入请求，等服务器回 `GroupJoined`。 */
    Joining,

    /** 已入组且时钟已收敛：命令随到随执行。 */
    Idle,

    /** 已排定一条 `whenAt` 在未来的组命令，等 deadline 到点。 */
    Armed,

    /** 正在随组播放（也是漂移校正的活跃态）。 */
    Playing,

    /** 随组暂停。 */
    Paused,

    /** 已入组但时钟未收敛：命令挂起，等 [SyncPlayClock.state] 出第一个样本。 */
    ClockWarming,
}

/**
 * 到点等待端口（W4-T8c）。
 *
 * 生产默认实现走 `kotlinx.coroutines.delay`；单测注入可手动触发的虚拟 ticker，不睡真实时间。一次 [await] 返回后由控制器重新判定是否需要继续等待（到点命令
 * / 漂移校正 / 时钟收敛轮询）。
 */
fun interface SyncPlayTicker {
    suspend fun await(duration: Duration)

    companion object {
        /** 生产默认：真实 `delay`。0 时长兜底成 1ms，避免忙等。 */
        val Default: SyncPlayTicker = SyncPlayTicker { duration ->
            delay(duration.toMillis().coerceAtLeast(1L))
        }
    }
}

/**
 * SyncPlay 客户端状态机（W4-T8c，VIDEO-37/39/40/41/43）。
 *
 * 只做三件事：把组命令落到播放口、按时钟到点触发、按漂移分档校正。协议细节（WS、REST、SDK 类型）全在 data 层，本类不认识任何 `data` 类型；命令 / 事件模型见
 * [SyncPlayCommand] / [SyncPlayGroupUpdate]。
 *
 * ## 线程约定
 *
 * 单线程：所有入口（[onJoinRequested] / [onGroupUpdate] / [onCommand] / [leaveGroup]）与 [run]
 * 必须在同一线程调用（生产为主线程），因此内部状态不加锁。
 *
 * ## 迁移表
 * |起点                     |触发                                                                                       |终点          |动作                                              |
 * |-----------------------|-----------------------------------------------------------------------------------------|------------|------------------------------------------------|
 * |NotInGroup             |`onJoinRequested()`                                                                      |Joining     |清空挂起命令 / 锚点 / 倍速                                |
 * |Joining                |`GroupJoined` 且时钟未收敛                                                                     |ClockWarming|记下组 id                                          |
 * |Joining                |`GroupJoined` 且时钟已收敛                                                                     |Idle        |记下组 id                                          |
 * |Joining                |`onJoinFailed()`                                                                         |NotInGroup  |清空组 id                                          |
 * |ClockWarming           |[run] 轮询到 `clock.state.isSynchronized`                                                   |Idle        |冲刷挂起命令（未来 ⇒ Armed，已过期 ⇒ 直跳）                     |
 * |Idle / Paused / Playing|收到 `whenAt` 在未来的命令                                                                       |Armed       |记下 Armed 之前的状态，等 deadline                       |
 * |Armed                  |到点执行 UNPAUSE                                                                             |Playing     |seek(期望位置) → play                               |
 * |Armed                  |到点执行 PAUSE                                                                               |Paused      |seek(positionTicks) → pause                     |
 * |Armed                  |到点执行 SEEK                                                                                |Armed 之前的状态 |seek(期望位置)                                      |
 * |Armed                  |到点执行 STOP                                                                                |Idle        |pause                                           |
 * |Idle / Paused          |收到立即执行的 UNPAUSE                                                                          |Playing     |晚到直跳：seek(positionTicks + elapsed × rate) → play|
 * |Playing                |收到立即执行的 PAUSE                                                                            |Paused      |seek(positionTicks) → pause，锚点冻结在暂停位置           |
 * |Playing / Paused / Idle|收到立即执行的 SEEK                                                                             |原态          |seek(positionTicks + elapsed × rate)            |
 * |任意已入组态                 |STOP                                                                                     |Idle        |pause，清锚点                                       |
 * |任意已入组态                 |`GroupLeft` / `NotInGroup` / `GroupDoesNotExist` / `LibraryAccessDenied` / `leaveGroup()`|NotInGroup  |[SyncPlayClock.reset]，倍速回 1.0                   |
 * |任意                     |`UserJoined` / `UserLeft` / `StateChanged` / `Unknown`                                   |不变          |无（组态变化只供 UI，控制器只认命令）                            |
 *
 * 未入组（NotInGroup / Joining）收到的命令直接丢弃：不属于本组会话。时钟未收敛（ClockWarming）收到的命令挂起，
 * 后到覆盖先到（组命令是收敛型语义，中间态没有补执行的价值）。其它组的命令 / 踢出事件一律忽略。
 *
 * ## 时刻口径
 *
 * 命令 deadline 一律用 [SyncPlayClock.nowServer] 与 `whenAt` 比对；晚到（`nowServer > whenAt`）按
 * `positionTicks + (nowServer − whenAt) × rate` 直跳，不补播。PAUSE 晚到不做 elapsed 补偿（该时刻起组已冻结，期望位置就是
 * `positionTicks`），见 [applyNow]。
 *
 * ## 漂移校正
 *
 * 只在 Playing 态、时钟已收敛、播放口报告在播时进行：期望位置 = 锚点位置 + (nowServer − 锚点时刻) × rate，漂移 = 播放口位置 − 期望位置，分档见
 * [SyncPlayDriftProfile]。追帧倍速由 [applyRate] 去重，避免每个 tick 都写播放口。
 *
 * [run] 由装配层放进自己的 scope（`launch { controller.run() }`），[SyncPlayClock.run] 的采样循环同理另行启动。
 */
class SyncPlayController(
    private val port: SyncPlayPlaybackPort,
    private val clock: SyncPlayClock,
    private val profile: SyncPlayDriftProfile = SyncPlayDriftProfile.Exo,
    private val ticker: SyncPlayTicker = SyncPlayTicker.Default,
    /** 组播放倍率：期望位置换算用（当前组只有 1.0x，倍率同步不在 T8c 范围）。 */
    private val groupRate: Double = 1.0,
    private val driftCheckInterval: Duration = DRIFT_CHECK_INTERVAL,
    private val clockPollInterval: Duration = CLOCK_POLL_INTERVAL,
    private val idlePollInterval: Duration = IDLE_POLL_INTERVAL,
) {
    private val _state = MutableStateFlow(SyncPlayControllerState.NotInGroup)

    /** 当前状态机态，供 T8d / UI 观察。 */
    val state: StateFlow<SyncPlayControllerState> = _state.asStateFlow()

    /** 当前所在的组；未入组为 null。 */
    var activeGroupId: UUID? = null
        private set

    /** 挂起（时钟未收敛）或已排定（Armed）的命令。 */
    private var pendingCommand: SyncPlayCommand? = null

    /** 进入 Armed 之前的播放态，SEEK 到点后回落用。 */
    private var armedFrom: SyncPlayControllerState? = null

    /** 组时间线锚点：期望位置由它外推。 */
    private var anchor: GroupAnchor? = null

    /** 当前写给播放口的倍速（播放口没有倍速读侧）。 */
    private var appliedRate: Float = profile.nominalRate

    private var waitJob: Job? = null

    // —— 入口 ——

    /** 装配层发起加入请求（REST 之前）时调用。 */
    fun onJoinRequested(groupId: UUID) {
        activeGroupId = groupId
        pendingCommand = null
        armedFrom = null
        anchor = null
        applyRate(profile.nominalRate)
        _state.value = SyncPlayControllerState.Joining
        wakeUp()
    }

    /** 加入请求失败（REST 报错）时调用：回到未入组。 */
    fun onJoinFailed() {
        clearGroup()
        _state.value = SyncPlayControllerState.NotInGroup
        wakeUp()
    }

    /** 主动退组。不触碰播放口（继续播 / 停播由调用方决定）。 */
    fun leaveGroup() {
        clearGroup()
        applyRate(profile.nominalRate)
        _state.value = SyncPlayControllerState.NotInGroup
        clock.reset()
        wakeUp()
    }

    /** 组事件入口（T8d 把 data 层 `SyncPlayGroupUpdate` 映射成同名类型后喂进来）。 */
    fun onGroupUpdate(update: SyncPlayGroupUpdate) {
        when (update) {
            is SyncPlayGroupUpdate.GroupJoined -> {
                activeGroupId = update.groupId
                pendingCommand = null
                armedFrom = null
                anchor = null
                applyRate(profile.nominalRate)
                // Joining → ClockWarming / Idle：加入完成的瞬间就按时钟收敛情况落位。
                _state.value =
                    if (clock.state.isSynchronized) {
                        SyncPlayControllerState.Idle
                    } else {
                        SyncPlayControllerState.ClockWarming
                    }
                wakeUp()
            }
            is SyncPlayGroupUpdate.GroupLeft,
            is SyncPlayGroupUpdate.NotInGroup,
            is SyncPlayGroupUpdate.GroupDoesNotExist,
            is SyncPlayGroupUpdate.LibraryAccessDenied -> {
                if (!isForeignGroup(update.groupId)) {
                    leaveGroup()
                }
            }
            is SyncPlayGroupUpdate.UserJoined,
            is SyncPlayGroupUpdate.UserLeft,
            is SyncPlayGroupUpdate.StateChanged,
            is SyncPlayGroupUpdate.Unknown -> Unit
        }
    }

    /** 组命令入口（WS `SyncPlayCommand` 映射后喂进来）。 */
    fun onCommand(command: SyncPlayCommand) {
        if (command.groupId != activeGroupId) return
        when (_state.value) {
            SyncPlayControllerState.NotInGroup,
            SyncPlayControllerState.Joining -> return
            SyncPlayControllerState.ClockWarming -> {
                if (!clock.state.isSynchronized) {
                    pendingCommand = command
                    return
                }
                // 时钟刚好收敛：先迁出 ClockWarming，再按正常路径处理（旧挂起命令被这条取代）。
                _state.value = SyncPlayControllerState.Idle
            }
            else -> Unit
        }
        pendingCommand = command
        scheduleOrApply(command, clock.nowServer())
        wakeUp()
    }

    /**
     * 驱动循环：到点命令、时钟收敛轮询、Playing 漂移校正都在这里。
     *
     * 由装配层放进自己的 scope：`scope.launch { controller.run() }`。取消协程即停止。
     */
    suspend fun run() {
        coroutineScope {
            while (currentCoroutineContext().isActive) {
                evaluate(clock.nowServer())
                val wait = nextWaitDuration(clock.nowServer())
                val job = launch { ticker.await(wait) }
                waitJob = job
                job.join()
                waitJob = null
            }
        }
    }

    // —— 状态机内部 ——

    private fun evaluate(now: Instant) {
        when (_state.value) {
            SyncPlayControllerState.ClockWarming -> {
                if (!clock.state.isSynchronized) return
                _state.value = SyncPlayControllerState.Idle
                pendingCommand?.let { scheduleOrApply(it, now) }
            }
            SyncPlayControllerState.Armed -> {
                val command = pendingCommand ?: return
                if (!now.isBefore(command.whenAt)) {
                    pendingCommand = null
                    applyNow(command, now)
                }
            }
            SyncPlayControllerState.Playing -> correctDrift(now)
            else -> Unit
        }
    }

    private fun scheduleOrApply(command: SyncPlayCommand, now: Instant) {
        if (now.isBefore(command.whenAt)) {
            if (_state.value != SyncPlayControllerState.Armed) {
                armedFrom = _state.value
            }
            _state.value = SyncPlayControllerState.Armed
            return
        }
        pendingCommand = null
        applyNow(command, now)
    }

    private fun applyNow(command: SyncPlayCommand, now: Instant) {
        when (command.type) {
            SyncPlayCommandType.UNPAUSE -> {
                val ticks = command.positionTicks
                val target = ticks?.let { expectedPositionMs(command, now) }
                target?.let { port.seekTo(it) }
                applyRate(profile.nominalRate)
                port.play()
                anchor =
                    if (target != null) {
                        GroupAnchor(command.whenAt, target, groupRate)
                    } else {
                        GroupAnchor(now, port.currentPositionMs, groupRate)
                    }
                _state.value = SyncPlayControllerState.Playing
            }
            SyncPlayCommandType.PAUSE -> {
                command.positionTicks?.let { port.seekTo(ticksToMillis(it)) }
                port.pause()
                anchor = GroupAnchor(now, port.currentPositionMs, groupRate)
                _state.value = SyncPlayControllerState.Paused
            }
            SyncPlayCommandType.SEEK -> {
                val ticks = command.positionTicks
                if (ticks != null) {
                    val target = expectedPositionMs(command, now)
                    port.seekTo(target)
                    anchor = GroupAnchor(command.whenAt, target, groupRate)
                }
                _state.value = seekReturnState()
            }
            SyncPlayCommandType.STOP -> {
                port.pause()
                anchor = null
                _state.value = SyncPlayControllerState.Idle
            }
            SyncPlayCommandType.UNKNOWN -> Unit
        }
        armedFrom = null
    }

    /** SEEK 到点后回到哪个态：Armed 之前是 Playing/Paused 就回去，否则 Idle。 */
    private fun seekReturnState(): SyncPlayControllerState {
        val previous = armedFrom
        if (
            previous == SyncPlayControllerState.Playing ||
                previous == SyncPlayControllerState.Paused
        ) {
            return previous
        }
        return if (_state.value == SyncPlayControllerState.Armed) {
            SyncPlayControllerState.Idle
        } else {
            _state.value
        }
    }

    private fun correctDrift(now: Instant) {
        val current = anchor ?: return
        if (!clock.state.isSynchronized) return
        // 缓冲 / 卡顿时引擎会短暂报 not playing：这一轮不校正，避免把追帧倍速叠在缓冲上。
        if (!port.isPlaying) return
        val expected = current.expectedAt(now)
        val drift = port.currentPositionMs - expected
        when (val action = SyncPlayDrift.decide(drift, appliedRate, profile, expected)) {
            SyncPlayDriftAction.Hold -> Unit
            is SyncPlayDriftAction.AdjustRate -> applyRate(action.rate)
            SyncPlayDriftAction.RestoreRate -> applyRate(profile.nominalRate)
            is SyncPlayDriftAction.Seek -> {
                port.seekTo(action.positionMs)
                applyRate(profile.nominalRate)
                anchor = GroupAnchor(now, action.positionMs, groupRate)
            }
        }
    }

    /**
     * 晚到 / 到点命令的期望位置：`positionTicks + (nowServer − whenAt) × rate`。
     *
     * 调用点保证 `positionTicks != null` 且 `nowServer >= whenAt`（早于 whenAt 的走 Armed）。
     */
    private fun expectedPositionMs(command: SyncPlayCommand, now: Instant): Long {
        val base = ticksToMillis(command.positionTicks ?: 0L)
        val elapsedMs = Duration.between(command.whenAt, now).toMillis()
        return base + (elapsedMs * groupRate).toLong()
    }

    private fun nextWaitDuration(now: Instant): Duration =
        when (_state.value) {
            SyncPlayControllerState.Armed ->
                pendingCommand?.let { maxOf(Duration.between(now, it.whenAt), Duration.ZERO) }
                    ?: idlePollInterval
            SyncPlayControllerState.Playing -> driftCheckInterval
            SyncPlayControllerState.ClockWarming -> clockPollInterval
            else -> idlePollInterval
        }

    private fun applyRate(rate: Float) {
        if (appliedRate == rate) return
        port.setRate(rate)
        appliedRate = rate
    }

    private fun wakeUp() {
        waitJob?.cancel()
    }

    private fun isForeignGroup(groupId: UUID): Boolean {
        val active = activeGroupId ?: return true
        return groupId != active
    }

    private fun clearGroup() {
        activeGroupId = null
        pendingCommand = null
        armedFrom = null
        anchor = null
    }

    /** 组时间线锚点：`whenAt` 时刻组位于 [positionMs]，之后按 [rate] 外推。 */
    private data class GroupAnchor(
        val whenAt: Instant,
        val positionMs: Long,
        val rate: Double,
    ) {
        fun expectedAt(now: Instant): Long =
            positionMs + (Duration.between(whenAt, now).toMillis() * rate).toLong()
    }

    companion object {
        /** Playing 态漂移校正的检查周期。定稿只钉分档阈值，未定周期；250ms 远小于 Exo 的 60ms 忽略带 ×4 的抖动容忍。 */
        val DRIFT_CHECK_INTERVAL: Duration = Duration.ofMillis(250)

        /** ClockWarming 态轮询 `clock.state.isSynchronized` 的周期（时钟自身 1s 一采样）。 */
        val CLOCK_POLL_INTERVAL: Duration = Duration.ofMillis(250)

        /** 空闲态（Idle / Paused / NotInGroup / Joining）的驱动循环空转周期。 */
        val IDLE_POLL_INTERVAL: Duration = Duration.ofSeconds(1)
    }
}
