package com.zhangwenkang.cinefin.film.presentation.downloads

import androidx.lifecycle.ViewModel
import com.zhangwenkang.cinefin.utils.Downloader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * W51 侧栏 / 抽屉 / 底栏「下载」角标：活动任务数 = 下载中 + 排队 + 暂停（0 隐藏）。
 *
 * 只做只读快照（不触发对账 / 引擎唤醒）；由 NavigationRoot 在前台可见期间定时调用 [refresh]。
 */
@HiltViewModel
class DownloadBadgeViewModel @Inject constructor(private val downloader: Downloader) : ViewModel() {
    private val _activeCount = MutableStateFlow(0)
    val activeCount = _activeCount.asStateFlow()

    suspend fun refresh() {
        val count = runCatching { downloader.activeItemIds().size }.getOrElse { _activeCount.value }
        _activeCount.value = count
    }
}
