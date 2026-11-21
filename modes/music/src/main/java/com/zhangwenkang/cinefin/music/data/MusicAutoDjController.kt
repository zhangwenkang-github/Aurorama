package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.QueueEndedEvent
import com.zhangwenkang.cinefin.player.core.domain.models.TrackSourceType
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackController
import com.zhangwenkang.cinefin.player.local.domain.MusicQueueEditor
import com.zhangwenkang.cinefin.player.local.domain.SleepTimerController
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Auto DJ 控制器（W5-T12，MUSIC-14；设计定稿五）。
 *
 * 数据流：`MusicPlaybackController.queueEnded` → 六格闸门（[autoDjGate]）→ `getInstantMix(种子, 10)` → 解析
 * `PlayerItem` → `appendToQueue` + `play()`（`pauseAtEndOfMediaItems=false`，同一 MediaSession 续播）。
 *
 * 范围（设计定稿五 §4）：只钩 `STATE_ENDED`，不预取；只支持服务器种子；不做常驻 UI；刹车计数进程级、不落盘。 装配方式与
 * [MusicLyricsOverlayController] 一致：进程级单例，音乐页首次进入时 [start]（幂等）。
 */
@Singleton
class MusicAutoDjController
@Inject
constructor(
    private val playbackController: MusicPlaybackController,
    private val queueEditor: MusicQueueEditor,
    private val repository: MusicRepository,
    private val trackResolver: MusicTrackResolver,
    private val preferences: AppPreferences,
    private val sleepTimer: SleepTimerController,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 刹车提示（第 5 格）：音乐页据此弹一次非阻塞 snackbar。 */
    private val _batchLimitReached = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val batchLimitReached: SharedFlow<Unit> = _batchLimitReached.asSharedFlow()

    private var started = false

    /** 同一播放会话内连续自动追加批数（用户任一传输操作清零）。 */
    private var autoAppendBatches = 0

    /** 一轮续播处理中（InstantMix 拉取 + 追加），防止重复事件叠着跑。 */
    private var handling = false

    /** 幂等启动：音乐 ViewModel 首次进入时调用（与队列存档 / 歌词悬浮窗同一套路）。 */
    fun start() {
        if (started) return
        started = true
        scope.launch { playbackController.queueEnded.collect(::onQueueEnded) }
    }

    /**
     * 用户任一传输 / 队列操作（播放 / 暂停 / 切歌 / seek / 队列编辑）：刹车计数清零。
     *
     * 只覆盖 App 内音乐页的操作；通知栏 / 锁屏传输按钮不经此处（设计定稿五未纳入范围）。
     */
    fun onUserTransportAction() {
        autoAppendBatches = 0
    }

    private suspend fun onQueueEnded(event: QueueEndedEvent) {
        if (handling) return
        val queue = playbackController.queue.value ?: return
        val input =
            AutoDjGateInput(
                enabled = preferences.getValue(preferences.musicAutoDj),
                seedIsServerItem = findSeedItem(queue, event.itemId)?.isServerSource == true,
                sleepStopPending = sleepStopPending(),
                endedByError = event.endedByError,
                autoAppendBatches = autoAppendBatches,
            )
        val preFetch = autoDjGate(input)
        if (preFetch.decision == AutoDjDecision.SKIP) {
            onSkipped(preFetch.reason)
            return
        }
        handling = true
        try {
            val appended =
                fetchAndAppendAutoDj(
                    seedItemId = event.itemId,
                    limit = AUTO_DJ_INSTANT_MIX_LIMIT,
                    fetch = { seed, limit ->
                        runCatching { repository.getInstantMix(seed, limit) }
                            .onFailure { Timber.w(it, "Auto DJ InstantMix 拉取失败：%s", seed) }
                            .getOrDefault(emptyList())
                    },
                    gate = { count ->
                        val result = autoDjGate(input.copy(instantMixItemCount = count))
                        if (result.decision == AutoDjDecision.SKIP) {
                            Timber.i("Auto DJ 跳过：%s（InstantMix 条数 %d）", result.reason, count)
                        }
                        result.decision == AutoDjDecision.APPEND
                    },
                    resolve = { song ->
                        runCatching { trackResolver.toPlayerItem(song) }
                            .onFailure { Timber.w(it, "Auto DJ 曲目解析失败：%s", song.name) }
                            .getOrNull()
                    },
                    append = queueEditor::appendToQueue,
                    queueLength = { playbackController.queue.value?.items?.size ?: -1 },
                    playFromIndex = queueEditor::playFromQueueIndex,
                )
            if (appended <= 0) return
            autoAppendBatches += 1
            // 风险①取证：续播批次追加后立刻留一行，K60 对照「音乐 PlaybackStart 上报」核对逐曲上报。
            Timber.i(
                "Auto DJ 续播：seed=%s 追加 %d 首（第 %d 批 / 上限 %d）",
                event.itemId,
                appended,
                autoAppendBatches,
                AUTO_DJ_MAX_BATCHES,
            )
        } finally {
            handling = false
        }
    }

    /** 闸门跳过：睡眠挂起要消费标志、刹车要提示；其余静默只留日志（设计定稿五 §3）。 */
    private fun onSkipped(reason: AutoDjGateReason) {
        when (reason) {
            AutoDjGateReason.SLEEP_STOP_PENDING -> {
                // 风险②：消费「播完当前曲目停止」挂起标志，防止 Auto DJ 把睡眠定时"复活"。
                sleepTimer.consumeStopAfterCurrentTrack()
                Timber.i("Auto DJ 跳过：睡眠定时挂起（已消费停止标志）")
            }
            AutoDjGateReason.BATCH_LIMIT -> {
                Timber.i("Auto DJ 跳过：连续续播已满 %d 批，等待用户操作", AUTO_DJ_MAX_BATCHES)
                _batchLimitReached.tryEmit(Unit)
            }
            else -> Timber.i("Auto DJ 跳过：%s", reason)
        }
    }

    /** 睡眠挂起 = 「播完当前曲目停止」标志在，或分钟计时已到期（到点即停）。 */
    private fun sleepStopPending(): Boolean {
        val state = sleepTimer.state.value
        return state.stopAfterCurrentTrack || (state.active && state.remainingMs <= 0L)
    }

    private fun findSeedItem(queue: MusicQueue, itemId: UUID): PlayerItem? {
        return queue.currentItem?.takeIf { it.itemId == itemId }
            ?: queue.items.firstOrNull { it.itemId == itemId }
            ?: queue.upNext.firstOrNull { it.itemId == itemId }
    }

    /** 闸门第 2 格：只有服务器来源曲目能做 InstantMix 种子（下载到本机的服务器曲目解析后也是 LOCAL 源）。 */
    private val PlayerItem.isServerSource: Boolean
        get() = trackSource?.sourceType == TrackSourceType.SERVER
}

/**
 * 拉取 → 第 6 格复评 → 解析 → 追加 → 从追加批次第一首起播（W5-T12）。
 *
 * 依赖全是入参，单测据此钉住交互顺序：`fetch(seedItemId, limit)` → `gate(size)` → `append(items)` →
 * `playFromIndex(追加前队长)`。队列播完（`STATE_ENDED`）后 `play()` 不会前进到新添加的条目， 所以起播必须显式指定「追加批次第一首」的主队列下标（=
 * 追加前队长）；[queueLength] 返回负数（队列不可用）时只追加不起播。
 *
 * @return 实际追加的曲目数；0 = 未追加（第 6 格未过 / 全部解析失败）。
 */
internal suspend fun fetchAndAppendAutoDj(
    seedItemId: UUID,
    limit: Int,
    fetch: suspend (UUID, Int) -> List<MusicSong>,
    gate: (Int) -> Boolean,
    resolve: suspend (MusicSong) -> PlayerItem?,
    append: (List<PlayerItem>) -> Unit,
    queueLength: () -> Int,
    playFromIndex: (Int) -> Unit,
): Int {
    val songs = fetch(seedItemId, limit)
    if (!gate(songs.size)) return 0
    val items = songs.mapNotNull { song -> resolve(song) }
    if (items.isEmpty()) return 0
    val startIndex = queueLength()
    append(items)
    if (startIndex >= 0) playFromIndex(startIndex)
    return items.size
}
