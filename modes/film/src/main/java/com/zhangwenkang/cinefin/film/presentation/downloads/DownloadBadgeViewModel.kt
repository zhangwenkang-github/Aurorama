package com.zhangwenkang.cinefin.film.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.utils.Downloader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * W51 侧栏 / 抽屉 / 底栏「下载」角标：活动任务数 = 下载中 + 排队 + 暂停（0 隐藏）。
 *
 * 只做只读快照（不触发对账 / 引擎唤醒）；由 NavigationRoot 在前台可见期间定时调用 [refresh]。
 */
@HiltViewModel
class DownloadBadgeViewModel @Inject constructor(private val downloader: Downloader) : ViewModel() {
    private val _activeCount = MutableStateFlow(0)
    val activeCount = _activeCount.asStateFlow()

    init {
        // W63：入队 / 完成 / 失败 / 删除等队列事件即时刷新（NavigationRoot 的 2s 轮询仍作兜底）。
        viewModelScope.launch { downloader.queueChanges.collect { refresh() } }
    }

    /** 活动任务数 = 活动队列条目去重 − 已落盘完成条目（见 [DownloadBadgeRules.activeBadgeCount]）。 */
    suspend fun refresh() {
        val count = runCatching {
            DownloadBadgeRules.activeBadgeCount(
                activeItemIds = downloader.activeItemIds(),
                downloadedItemIds = downloader.downloadedItemIds(),
            )
        }
            .getOrElse { _activeCount.value }
        _activeCount.value = count
    }
}
