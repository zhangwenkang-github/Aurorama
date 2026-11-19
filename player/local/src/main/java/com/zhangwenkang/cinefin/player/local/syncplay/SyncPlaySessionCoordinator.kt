package com.zhangwenkang.cinefin.player.local.syncplay

import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayClock
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayCommand as CoreSyncPlayCommand
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayCommandType as CoreSyncPlayCommandType
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayController
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayControllerState
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayDelay
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayDriftProfile
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroup as CoreSyncPlayGroup
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroupState as CoreSyncPlayGroupState
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroupUpdate as CoreSyncPlayGroupUpdate
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayServerTimeSample
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayTicker
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayTimeSource
import com.zhangwenkang.cinefin.syncplay.SyncPlayCapability
import com.zhangwenkang.cinefin.syncplay.SyncPlayConnectionState
import com.zhangwenkang.cinefin.syncplay.SyncPlayError
import com.zhangwenkang.cinefin.syncplay.SyncPlayGateway
import com.zhangwenkang.cinefin.syncplay.SyncPlayResult
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** 入组 / 建组的结果（T8e 面板按 [SyncPlayJoinOutcome.Failure.error] 分支提示）。 */
sealed interface SyncPlayJoinOutcome {
    data object Success : SyncPlayJoinOutcome

    data class Failure(val error: SyncPlayError) : SyncPlayJoinOutcome
}

/** `GetUtcTime` 失败在时钟端口上以异常形式抛出（[SyncPlayClock.run] 吞掉并等下个周期）。 */
class SyncPlayServerTimeException(val error: SyncPlayError) :
    IllegalStateException("SyncPlay 服务器时间采样失败：$error")

/**
 * SyncPlay 会话协调器（W4-T8d）：状态机、时钟、播放口与传输闸门的唯一装配点。
 *
 * 进程级单例，挂在 [SyncPlayPlayerHost.scope]（生产 = `PlayerHolder.processScope`）上，活得比播放页久：
 *
 * ```
 * gateway ──commands/groupUpdates/connectionState（各收集一次）──▶ controller（七态状态机）
 *    ▲                                                            │
 *    │ pause/unpause/seek（闸门）                                  ▼
 * gate ◀── UI 手势 / 面板播控                            port ──▶ PlayerHolder.currentEngine
 *                                                          └── seekSink.submit("syncplay-command")
 * clock ◀── gateway.getServerTime()（SyncPlayTimeSource 适配）
 * ```
 *
 * 生命周期要点（T8c / T8a 交接事实）：
 * - `gateway.commands` / `gateway.groupUpdates` / `gateway.connectionState` 的 getter **每次收集都会重订阅
 *   WS**， 所以 [start] 只收集一次，之后再不重收——包括原地切内核重建状态机时。
 * - 状态机与时钟是两条独立循环：`launch { clock.run() }` + `launch { controller.run() }`，只启一个会卡在 ClockWarming。
 * - 断线重连（`DISCONNECTED → CONNECTED`）先 `getGroup` 拉快照对齐，`GroupDoesNotExist` 按退组收尾；命令流不断，自动恢复。
 * - 入组期间原地切内核（T3a-2 管线）：重建播放口与漂移档，按切前的 Idle/Playing/Paused 恢复组态，**不重新 Join**。
 *
 * 线程约定：与 [SyncPlayController] 一致，所有入口在主线程调用（scope 为 `Main.immediate`）。
 */
@Singleton
class SyncPlaySessionCoordinator
@Inject
constructor(
    private val gateway: SyncPlayGateway,
    private val host: SyncPlayPlayerHost,
) : SyncPlayTransportCommands {
    private companion object {
        /** 1 ms = 10_000 ticks（`player:core` 的同名常量是 internal，这里按 Jellyfin 协议重写一份）。 */
        const val TICKS_PER_MILLISECOND = 10_000L

        /** 退组后恢复的标称倍速。 */
        const val NOMINAL_RATE = 1.0f

        /** 组态恢复用的占位条目 id：状态机只比对 groupId，不消费条目。 */
        val PLACEHOLDER_PLAYLIST_ITEM: UUID = UUID(0L, 0L)
    }

    /** 单测替换：时钟采样间隔（测试用短间隔让时钟快速收敛）。 */
    internal var clockDelay: SyncPlayDelay = SyncPlayDelay.Default

    private val clock: SyncPlayClock by lazy {
        SyncPlayClock(
            timeSource =
                object : SyncPlayTimeSource {
                    override suspend fun fetchServerTime(): SyncPlayServerTimeSample =
                        fetchServerTimeSample()
                },
            pause = clockDelay,
        )
    }

    private val seekDispatcher = SyncPlaySeekDispatcher { positionMs, source ->
        host.submitSeek(positionMs, source)
    }

    /** 单测替换：默认真实 `delay`；测试用「永不返回」的 ticker 让循环只在入口唤醒时求值。 */
    internal var ticker: SyncPlayTicker = SyncPlayTicker.Default

    /** 单测替换：内核标识 → 漂移档（默认 Exo / mpv 两档）。 */
    internal var driftProfileFor: (String) -> SyncPlayDriftProfile =
        SyncPlayDriftProfile::forBackend

    private var controller: SyncPlayController? = null
    private var controllerJob: Job? = null
    private var stateJob: Job? = null
    private var started = false
    private var awaitingResync = true
    private var builtBackend: String? = null
    private var appliedDriftProfile: SyncPlayDriftProfile = SyncPlayDriftProfile.Exo

    private val _state = MutableStateFlow(SyncPlayControllerState.NotInGroup)

    /** 状态机当前态（七态）；T8e 面板与闸门共用。 */
    val state: StateFlow<SyncPlayControllerState> = _state.asStateFlow()

    private val _activeGroup = MutableStateFlow<CoreSyncPlayGroup?>(null)

    /** 当前组快照（组名 / 成员 / 组态）；未入组为 null。 */
    val activeGroup: StateFlow<CoreSyncPlayGroup?> = _activeGroup.asStateFlow()

    private val _connectionState = MutableStateFlow<SyncPlayConnectionState?>(null)

    /** WS 连接态（重连中提示用）；null = 还没收到第一帧。 */
    val connectionState: StateFlow<SyncPlayConnectionState?> = _connectionState.asStateFlow()

    /** 传输闸门：入组期间本地手势只发组命令。 */
    val gate: SyncPlayTransportGate =
        SyncPlayTransportGate(
            scope = host.scope,
            isPlaying = { host.isPlaying },
            currentPositionMs = { host.currentPositionMs },
            commands = this,
        )

    /** 当前状态机使用的漂移档（切内核后重建；供诊断与单测断言）。 */
    internal val driftProfile: SyncPlayDriftProfile
        get() = appliedDriftProfile

    // —— 生命周期 ——

    /**
     * 幂等启动：时钟循环、状态机循环、WS 三路收集各一次。
     *
     * 由 `PlayerActivity.onCreate` 调用（进程级单例，播放页重建不重订阅）；重复调用无副作用。
     */
    fun start() {
        if (started) return
        started = true
        val scope = host.scope
        // 双循环：时钟采样与状态机各一条（T8c 交接原话，只启一个会卡 ClockWarming）。
        scope.launch { clock.run() }
        launchControllerLoop()
        launchStateMirror()
        scope.launch { gateway.commands.collect { currentController().onCommand(it.toCore()) } }
        scope.launch {
            gateway.groupUpdates.collect { update ->
                val core = update.toCore() ?: return@collect
                if (core is CoreSyncPlayGroupUpdate.GroupJoined) {
                    _activeGroup.value = core.group
                }
                currentController().onGroupUpdate(core)
            }
        }
        scope.launch { gateway.connectionState.collect(::onConnectionChanged) }
        host.addBackendReboundListener(::onEngineChanged)
    }

    // —— T8e 消费面 ——

    /** 入组前能力探针（服务器版本过低时 T8e 隐藏入口）。 */
    suspend fun probeCapability(): SyncPlayCapability = gateway.probeCapability()

    /**
     * 加入已有组：先 `controller.onJoinRequested`（进 Joining），再 REST join；失败回到未入组。
     *
     * 成功后立刻拉一次组快照，保证 WS `GroupJoined` 晚到 / 丢失时也不会卡在 Joining；WS 事件先到时快照不会覆盖当前态。
     */
    suspend fun joinGroup(groupId: UUID): SyncPlayJoinOutcome {
        ensureStarted()
        if (isInSession()) leaveGroup()
        currentController().onJoinRequested(groupId)
        return when (val result = gateway.joinGroup(groupId)) {
            is SyncPlayResult.Success -> {
                fetchSnapshot(groupId)
                SyncPlayJoinOutcome.Success
            }
            is SyncPlayResult.Failure -> {
                currentController().onJoinFailed()
                SyncPlayJoinOutcome.Failure(result.error)
            }
        }
    }

    /**
     * 建组并加入（服务器把创建者直接放进新组）。
     *
     * 拿到组快照后立即按 `GroupJoined` 对齐状态机，不等 WS 推送。
     */
    suspend fun createAndJoin(name: String): SyncPlayJoinOutcome {
        ensureStarted()
        if (isInSession()) leaveGroup()
        return when (val result = gateway.createGroup(name)) {
            is SyncPlayResult.Success -> {
                val group = result.value.toCore()
                currentController().onJoinRequested(group.id)
                applySnapshot(group)
                SyncPlayJoinOutcome.Success
            }
            is SyncPlayResult.Failure -> SyncPlayJoinOutcome.Failure(result.error)
        }
    }

    /**
     * 退组：REST leave（失败只记日志）+ 状态机清组 + 时钟复位 + 闸门关闭 + 倍速回 1.0。
     *
     * 不碰播放：继续播还是停播由调用方（T8e）决定。
     */
    suspend fun leaveGroup() {
        if (started && isInSession()) {
            when (val result = gateway.leaveGroup()) {
                is SyncPlayResult.Success -> Unit
                is SyncPlayResult.Failure -> Timber.w("SyncPlay 退组 REST 失败：%s", result.error)
            }
        }
        endSession()
    }

    /**
     * 建组后把本机队列推给服务器（W4-T8e 薄封装：面板/宿主不直接碰 gateway）。
     *
     * 服务器据此向组内其它端下发 `PlayQueue`（jellyfin-web 等参考端要拿到队列才有东西可跟随）； 本机不吃这条命令（组队列接管在 T8c 拍板里
     * DEFERRED）。失败只返回错误，组本身不受影响。
     */
    suspend fun startGroupQueue(
        playlistItemIds: List<UUID>,
        playingItemIndex: Int,
        startPositionMs: Long,
    ): SyncPlayError? {
        if (playlistItemIds.isEmpty()) return null
        val index = playingItemIndex.coerceIn(0, playlistItemIds.lastIndex)
        return errorOf(
            gateway.playQueue(
                playlistItemIds = playlistItemIds,
                playingItemIndex = index,
                startPositionTicks = startPositionMs.coerceAtLeast(0L) * TICKS_PER_MILLISECOND,
            )
        )
    }

    /**
     * 原地切内核后的重绑（T3a-2 管线成功后由 `PlayerViewModel` 调用）。
     *
     * 重建播放口与漂移档，把组态按切前状态恢复（Playing / Paused → 合成一条立即执行的组命令完成恢复）， **不**重新 Join、**不**重收 WS 流。
     */
    private fun onEngineChanged() {
        if (!started) return
        val backend = host.backendName
        if (builtBackend == backend) return
        val previous = controller ?: return
        val groupId = previous.activeGroupId
        val group = _activeGroup.value
        val previousState = previous.state.value

        Timber.i("SyncPlay 原地切内核：重建播放口与漂移档（%s -> %s）", builtBackend, backend)
        stateJob?.cancel()
        controllerJob?.cancel()
        controller = null
        val rebuilt = currentController()
        launchControllerLoop()
        launchStateMirror()

        if (groupId == null) return
        rebuilt.onJoinRequested(groupId)
        rebuilt.onGroupUpdate(
            CoreSyncPlayGroupUpdate.GroupJoined(
                groupId = groupId,
                group = group ?: placeholderGroup(groupId),
            )
        )
        val now = clock.nowServer()
        when (previousState) {
            SyncPlayControllerState.Playing ->
                rebuilt.onCommand(groupCommand(groupId, CoreSyncPlayCommandType.UNPAUSE, now))
            SyncPlayControllerState.Paused ->
                rebuilt.onCommand(groupCommand(groupId, CoreSyncPlayCommandType.PAUSE, now))
            else -> Unit
        }
    }

    // —— SyncPlayTransportCommands（闸门出口） ——

    override suspend fun pause(): SyncPlayError? = errorOf(gateway.pause())

    override suspend fun unpause(): SyncPlayError? = errorOf(gateway.unpause())

    override suspend fun seek(positionMs: Long): SyncPlayError? =
        errorOf(gateway.seek(positionMs.coerceAtLeast(0L) * TICKS_PER_MILLISECOND))

    // —— 内部 ——

    private fun ensureStarted() {
        if (!started) start()
    }

    private fun isInSession(): Boolean =
        controller?.activeGroupId != null || _state.value != SyncPlayControllerState.NotInGroup

    private fun currentController(): SyncPlayController =
        controller ?: buildController().also { controller = it }

    private fun buildController(): SyncPlayController {
        val profile = driftProfileFor(host.backendName)
        appliedDriftProfile = profile
        builtBackend = host.backendName
        return SyncPlayController(
            port =
                PlayerSyncPlayPlaybackPort(
                    engineProvider = { host.engine },
                    seekDispatcher = seekDispatcher,
                    positionProvider = { host.currentPositionMs },
                    isPlayingProvider = { host.isPlaying },
                ),
            clock = clock,
            profile = profile,
            ticker = ticker,
        )
    }

    private fun launchControllerLoop() {
        val target = currentController()
        controllerJob?.cancel()
        controllerJob = host.scope.launch { target.run() }
    }

    private fun launchStateMirror() {
        val target = currentController()
        stateJob?.cancel()
        stateJob =
            host.scope.launch {
                target.state.collect { state ->
                    _state.value = state
                    gate.updateOwned(ownsTransport(state))
                    if (state == SyncPlayControllerState.NotInGroup) {
                        _activeGroup.value = null
                        gate.updateOwned(false)
                    }
                }
            }
    }

    private fun ownsTransport(state: SyncPlayControllerState): Boolean =
        when (state) {
            SyncPlayControllerState.NotInGroup,
            SyncPlayControllerState.Joining -> false
            SyncPlayControllerState.Idle,
            SyncPlayControllerState.Armed,
            SyncPlayControllerState.Playing,
            SyncPlayControllerState.Paused,
            SyncPlayControllerState.ClockWarming -> true
        }

    private suspend fun fetchServerTimeSample(): SyncPlayServerTimeSample =
        when (val result = gateway.getServerTime()) {
            is SyncPlayResult.Success -> result.value.toCoreSample()
            is SyncPlayResult.Failure -> throw SyncPlayServerTimeException(result.error)
        }

    private suspend fun fetchSnapshot(groupId: UUID) {
        when (val result = gateway.getGroup(groupId)) {
            is SyncPlayResult.Success -> applySnapshot(result.value.toCore())
            is SyncPlayResult.Failure -> Timber.w("SyncPlay 组快照拉取失败（%s）：%s", groupId, result.error)
        }
    }

    /**
     * 用组快照对齐状态机：只在 Joining 时补一次 `GroupJoined`。
     *
     * 其它态（Playing / Paused / Idle）**不**重放 `GroupJoined`——它会把锚点与播放态打回 Idle；快照只更新 UI 观测面。
     */
    private fun applySnapshot(group: CoreSyncPlayGroup) {
        _activeGroup.value = group
        val target = controller ?: return
        if (target.activeGroupId != group.id) return
        if (target.state.value != SyncPlayControllerState.Joining) return
        target.onGroupUpdate(CoreSyncPlayGroupUpdate.GroupJoined(group.id, group))
    }

    private fun onConnectionChanged(state: SyncPlayConnectionState) {
        _connectionState.value = state
        when (state) {
            SyncPlayConnectionState.DISCONNECTED -> awaitingResync = true
            SyncPlayConnectionState.CONNECTED ->
                if (awaitingResync) {
                    awaitingResync = false
                    resyncAfterReconnect()
                }
            SyncPlayConnectionState.CONNECTING -> Unit
        }
    }

    /** 断线恢复：命令流从未中断（只收集一次），这里补的是「重连后先拉组快照对齐」。 */
    private fun resyncAfterReconnect() {
        val groupId = controller?.activeGroupId ?: return
        host.scope.launch {
            when (val result = gateway.getGroup(groupId)) {
                is SyncPlayResult.Success -> {
                    applySnapshot(result.value.toCore())
                    Timber.i("SyncPlay 重连：已拉取组快照对齐（%s）", groupId)
                }
                is SyncPlayResult.Failure ->
                    if (result.error is SyncPlayError.GroupDoesNotExist) {
                        Timber.i("SyncPlay 重连：组已不存在，按退组收尾（%s）", groupId)
                        endSession()
                    } else {
                        Timber.w("SyncPlay 重连：组快照拉取失败（%s）：%s", groupId, result.error)
                    }
            }
        }
    }

    private fun endSession() {
        controller?.leaveGroup()
        clock.reset()
        host.engine?.setRate(NOMINAL_RATE)
        _activeGroup.value = null
        gate.updateOwned(false)
    }

    private fun groupCommand(
        groupId: UUID,
        type: CoreSyncPlayCommandType,
        whenAt: Instant,
    ): CoreSyncPlayCommand =
        CoreSyncPlayCommand(
            groupId = groupId,
            playlistItemId = PLACEHOLDER_PLAYLIST_ITEM,
            type = type,
            whenAt = whenAt,
            positionTicks = null,
            emittedAt = whenAt,
        )

    private fun placeholderGroup(groupId: UUID): CoreSyncPlayGroup =
        CoreSyncPlayGroup(
            id = groupId,
            name = "",
            state = CoreSyncPlayGroupState.UNKNOWN,
            participants = emptyList(),
            lastUpdatedAt = Instant.EPOCH,
        )

    private fun errorOf(result: SyncPlayResult<*>): SyncPlayError? =
        (result as? SyncPlayResult.Failure)?.error
}
