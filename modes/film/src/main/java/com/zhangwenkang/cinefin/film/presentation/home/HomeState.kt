package com.zhangwenkang.cinefin.film.presentation.home

import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.HomeItem
import com.zhangwenkang.cinefin.models.Server

data class HomeState(
    val server: Server? = null,
    val suggestionsSection: HomeItem.Suggestions? = null,
    /** 继续观看（视频：电影 / 单集）。沿用旧字段名，TV 端首页不受影响。 */
    val resumeSection: HomeItem.Section? = null,
    /** 继续阅读（W54-D）：书籍续读，无内容自动隐藏。 */
    val resumeReadingSection: HomeItem.Section? = null,
    /** 继续收听（W54-D）：音频续播，无内容自动隐藏。 */
    val resumeListeningSection: HomeItem.Section? = null,
    val nextUpSection: HomeItem.Section? = null,
    /** 「最近添加 · 视频 / 书籍 / 音乐」三条（W54-D）：视频保留原海报墙形态。 */
    val recentlyAddedVideos: List<FindroidItem> = emptyList(),
    val recentlyAddedBooks: List<FindroidItem> = emptyList(),
    val recentlyAddedMusic: List<FindroidItem> = emptyList(),
    val views: List<HomeItem.ViewItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: Exception? = null,
)
