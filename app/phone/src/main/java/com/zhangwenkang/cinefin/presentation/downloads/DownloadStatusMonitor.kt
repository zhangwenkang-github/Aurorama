package com.zhangwenkang.cinefin.presentation.downloads

import androidx.lifecycle.ViewModel
import com.zhangwenkang.cinefin.presentation.film.components.DownloadBadgeInfo
import com.zhangwenkang.cinefin.presentation.film.components.DownloadBadgeState
import com.zhangwenkang.cinefin.presentation.film.components.downloadBadgeInfo
import com.zhangwenkang.cinefin.presentation.film.components.downloadBadgePriority
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.Downloader
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    private var pollJob: Job? = null

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
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stop() {
        pollJob?.cancel()
        pollJob = null
        _badges.value = emptyMap()
    }

    private suspend fun refresh() {
        val tasks = runCatching {
            downloader.refreshDownloadTasks()
        }
            .getOrElse {
                return
            }
        val downloaded = runCatching {
            downloader.downloadedItemIds()
        }
            .getOrElse { emptySet<UUID>() }
        // W59 阅读器离线链路（files/books/*.book）：书籍不走下载引擎，角标数据源要把本机书籍并进来。
        val localBooks = runCatching {
            readerRepository.listLocalFiles().map { file -> file.itemId }.toSet()
        }
            .getOrElse { emptySet() }
        _badges.value = badgeMapFor(tasks, downloaded + localBooks)
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
 * 同一 `itemId` 可能有多条任务（重试 / 换源残留），取优先级最高的那条；已下载条目即使没有任务也补一枚完成角标。
 */
internal fun badgeMapFor(
    tasks: List<DownloadTask>,
    downloaded: Set<UUID>,
): Map<UUID, DownloadBadgeInfo> {
    val map = mutableMapOf<UUID, DownloadBadgeInfo>()
    fun put(itemId: UUID, info: DownloadBadgeInfo) {
        val existing = map[itemId]
        if (
            existing == null ||
                downloadBadgePriority(info.state) > downloadBadgePriority(existing.state)
        ) {
            map[itemId] = info
        }
    }
    for (task in tasks) {
        put(
            task.itemId,
            downloadBadgeInfo(
                downloaded = task.itemId in downloaded,
                taskStatus = task.status,
                progress = task.progress,
                totalKnown = task.totalBytes > 0L,
            ),
        )
    }
    for (id in downloaded) {
        if (id !in map) map[id] = DownloadBadgeInfo(DownloadBadgeState.DOWNLOADED)
    }
    return map
}
