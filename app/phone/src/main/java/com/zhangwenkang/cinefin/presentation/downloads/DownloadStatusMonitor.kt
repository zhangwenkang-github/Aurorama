package com.zhangwenkang.cinefin.presentation.downloads

import androidx.lifecycle.ViewModel
import com.zhangwenkang.cinefin.presentation.film.components.DownloadBadgeInfo
import com.zhangwenkang.cinefin.presentation.film.components.DownloadBadgeState
import com.zhangwenkang.cinefin.presentation.film.components.downloadBadgeInfo
import com.zhangwenkang.cinefin.repository.BookDownloadStatus
import com.zhangwenkang.cinefin.repository.BookDownloadTask
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import com.zhangwenkang.cinefin.utils.Downloader
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * 下载状态角标数据源（W60b）：单例轮询下载引擎的只读快照（任务状态 + 进度 + 已下载集合）， 供库网格 / 首页走廊 / 搜索结果 / 详情海报 / 我的收藏页的卡片角标共用。
 *
 * 只在有页面「持有」时轮询（[acquire] / [release] 计数）：所有页面离开组合后停止轮询并清空快照， 不把下载引擎的查询变成常驻负担。轮询周期与下载页一致（1500ms）。
 */
@Singleton
class DownloadStatusMonitor
@Inject
constructor(
    private val downloader: Downloader,
    private val readerRepository: ReaderRepository,
) {
    private val _badges = MutableStateFlow<Map<UUID, DownloadBadgeInfo>>(emptyMap())
    val badges: StateFlow<Map<UUID, DownloadBadgeInfo>> = _badges.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listeners = AtomicInteger(0)
    private var monitorJob: Job? = null
    private var refreshSignals: Channel<Unit>? = null

    fun acquire() {
        if (listeners.incrementAndGet() == 1) start()
    }

    fun release() {
        if (listeners.decrementAndGet() <= 0) {
            listeners.set(0)
            stop()
        }
    }

    private fun start() {
        if (monitorJob?.isActive == true) return
        val signals = Channel<Unit>(capacity = Channel.CONFLATED)
        refreshSignals = signals
        monitorJob = scope.launch {
            supervisorScope {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    downloader.queueChanges.collect { signals.trySend(Unit) }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    readerRepository.bookDownloads.drop(1).collect { signals.trySend(Unit) }
                }
                launch {
                    while (isActive) {
                        signals.send(Unit)
                        delay(POLL_INTERVAL_MS)
                    }
                }
                for (ignored in signals) {
                    refresh()
                }
            }
        }
    }

    private fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        refreshSignals?.close()
        refreshSignals = null
        _badges.value = emptyMap()
    }

    private suspend fun refresh() {
        val tasks = readOrNull { downloader.refreshDownloadTasks() } ?: return
        val downloaded = readOrNull { downloader.downloadedItemIds() } ?: emptySet()
        // W59 阅读器离线链路（files/books/*.book）：书籍不走下载引擎，角标数据源要把本机书籍并进来。
        val bookTasks = readerRepository.bookDownloads.value
        val localBooks =
            readOrNull { readerRepository.listLocalFiles().map { file -> file.itemId }.toSet() }
                ?: emptySet()
        _badges.value =
            downloadStatusProjectionFor(
                    tasks = tasks,
                    downloaded = downloaded,
                    bookTasks = bookTasks,
                    localBookIds = localBooks,
                )
                .badges
    }

    private suspend fun <T> readOrNull(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val POLL_INTERVAL_MS = 1500L
    }
}

/** 页面级入口：把单例监控的生命周期挂在页面 ViewModel 上（多条路由同时存活时共享同一份快照）。 */
@HiltViewModel
class DownloadStatusViewModel @Inject constructor(private val monitor: DownloadStatusMonitor) :
    ViewModel() {
    val badges: StateFlow<Map<UUID, DownloadBadgeInfo>> = monitor.badges

    init {
        monitor.acquire()
    }

    override fun onCleared() {
        monitor.release()
        super.onCleared()
    }
}

/**
 * 纯函数：任务快照 + 已下载集合 → `itemId → 徽标`。
 *
 * 同一 `itemId` 可能有多条任务（重试 / 换源残留），先按 generation / updatedAt 规范化为最新任务， 再从同一快照生成 active / failed /
 * badges。已下载条目即使没有任务也补一枚完成角标。
 */
internal fun badgeMapFor(
    tasks: List<DownloadTask>,
    downloaded: Set<UUID>,
    bookTasks: List<BookDownloadTask> = emptyList(),
    localBookIds: Set<UUID> = emptySet(),
): Map<UUID, DownloadBadgeInfo> =
    downloadStatusProjectionFor(tasks, downloaded, bookTasks, localBookIds).badges

internal data class DownloadStatusProjection(
    val activeItemIds: Set<UUID>,
    val failedItemIds: Set<UUID>,
    val badges: Map<UUID, DownloadBadgeInfo>,
)

internal fun downloadStatusProjectionFor(
    tasks: List<DownloadTask>,
    downloaded: Set<UUID>,
    bookTasks: List<BookDownloadTask> = emptyList(),
    localBookIds: Set<UUID> = emptySet(),
): DownloadStatusProjection {
    val completedIds = downloaded + localBookIds
    val latestTasks =
        tasks
            .groupBy(DownloadTask::itemId)
            .mapValues { (_, duplicates) -> duplicates.maxWithOrNull(downloadTaskRecency) }
            .mapNotNull { (_, task) -> task }
    val visibleBooks = bookTasks.filterNot(BookDownloadTask::isSupersededDeleteRecord)
    val latestBooks =
        visibleBooks
            .groupBy(BookDownloadTask::itemId)
            .mapValues { (_, duplicates) -> duplicates.maxWithOrNull(bookTaskRecency) }
            .mapNotNull { (_, task) -> task }
    val map = mutableMapOf<UUID, DownloadBadgeInfo>()
    for (itemId in completedIds) {
        map[itemId] = DownloadBadgeInfo(DownloadBadgeState.DOWNLOADED)
    }
    for (task in latestTasks) {
        map[task.itemId] =
            downloadBadgeInfo(
                downloaded = task.itemId in completedIds,
                taskStatus = task.status,
                progress = task.progress,
                totalKnown = task.totalBytes > 0L,
            )
    }
    for (task in latestBooks) {
        if (task.itemId in completedIds) continue
        val info =
            when (task.status) {
                BookDownloadStatus.RUNNING ->
                    DownloadBadgeInfo(
                        state = DownloadBadgeState.IN_PROGRESS,
                        progress = task.progress,
                        indeterminate = task.totalBytes <= 0L,
                    )
                BookDownloadStatus.FAILED -> DownloadBadgeInfo(DownloadBadgeState.FAILED)
                BookDownloadStatus.COMPLETED -> DownloadBadgeInfo()
            }
        if (map[task.itemId]?.state != DownloadBadgeState.DOWNLOADED) {
            map[task.itemId] = info
        }
    }
    return DownloadStatusProjection(
        activeItemIds =
            latestTasks
                .filter {
                    it.status == DownloadTaskStatus.RUNNING ||
                        it.status == DownloadTaskStatus.PENDING
                }
                .mapTo(linkedSetOf(), DownloadTask::itemId) +
                latestBooks
                    .filter { it.status == BookDownloadStatus.RUNNING }
                    .mapTo(linkedSetOf(), BookDownloadTask::itemId),
        failedItemIds =
            latestTasks
                .filter { it.status == DownloadTaskStatus.FAILED }
                .mapTo(linkedSetOf(), DownloadTask::itemId) +
                latestBooks
                    .filter { it.status == BookDownloadStatus.FAILED }
                    .mapTo(linkedSetOf(), BookDownloadTask::itemId),
        badges = map,
    )
}

private val downloadTaskRecency =
    compareBy<DownloadTask>(
        { it.taskGeneration },
        { it.updatedAt },
        { downloadStatusRank(it.status) },
    )

private val bookTaskRecency =
    compareBy<BookDownloadTask>(
        { it.taskGeneration },
        { it.updatedAtEpochMillis },
        { bookStatusRank(it.status) },
    )

private fun downloadStatusRank(status: DownloadTaskStatus): Int =
    when (status) {
        DownloadTaskStatus.FAILED -> 4
        DownloadTaskStatus.COMPLETED -> 3
        DownloadTaskStatus.RUNNING -> 2
        DownloadTaskStatus.PENDING -> 1
        DownloadTaskStatus.PAUSED,
        DownloadTaskStatus.DELETING -> 0
    }

private fun bookStatusRank(status: BookDownloadStatus): Int =
    when (status) {
        BookDownloadStatus.FAILED -> 2
        BookDownloadStatus.RUNNING -> 1
        BookDownloadStatus.COMPLETED -> 0
    }
