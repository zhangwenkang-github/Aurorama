package com.zhangwenkang.cinefin.presentation.film

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.film.presentation.search.SearchAction
import com.zhangwenkang.cinefin.film.presentation.search.SearchCategory
import com.zhangwenkang.cinefin.film.presentation.search.SearchResultRules
import com.zhangwenkang.cinefin.film.presentation.search.SearchViewModel
import com.zhangwenkang.cinefin.local.LocalMediaKind
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.downloads.DownloadStatusViewModel
import com.zhangwenkang.cinefin.presentation.film.components.FilmSearchBar
import java.util.UUID

/**
 * W82 #30：独立搜索目的地。
 *
 * 搜索不再复用媒体库页的浮层状态，而是压入自己的返回栈条目：
 * - 系统返回 / 左上 × 关闭 = pop 回进入前的页面；
 * - 点结果进入详情 / 播放 / 阅读 = 详情压在搜索之上，返回先回本页（查询与结果由 ViewModel 保留）。
 */
@Composable
fun SearchScreen(
    onClose: () -> Unit,
    onItemClick: (FindroidItem) -> Unit,
    onPlayLocalVideo: (UUID) -> Unit,
    onOpenLocalBook: (UUID, String, String) -> Unit,
    onOpenMusic: (UUID) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
    downloadStatusViewModel: DownloadStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloadBadges by downloadStatusViewModel.badges.collectAsStateWithLifecycle()

    FilmSearchBar(
        state = state,
        downloadBadges = downloadBadges,
        expanded = true,
        onExpand = { expanded -> if (!expanded) onClose() },
        onAction = { action ->
            when (action) {
                is SearchAction.OnItemClick ->
                    if (SearchResultRules.categoryOf(action.item) == SearchCategory.MUSIC) {
                        onOpenMusic(action.item.id)
                    } else {
                        onItemClick(action.item)
                    }
                is SearchAction.OnLocalItemClick ->
                    when (action.hit.entry.kind) {
                        LocalMediaKind.VIDEO -> onPlayLocalVideo(action.hit.entry.itemId)
                        LocalMediaKind.BOOK ->
                            onOpenLocalBook(
                                action.hit.entry.itemId,
                                action.hit.entry.displayName,
                                action.hit.entry.documentUri,
                            )
                        LocalMediaKind.MUSIC -> onOpenMusic(action.hit.entry.itemId)
                    }
                else -> Unit
            }
            viewModel.onAction(action)
        },
        // M3 展开态 SearchBar 需要父布局不固定高度；固定 min=max 会把结果区压成 0 高。
        modifier = Modifier.fillMaxWidth(),
    )
}
