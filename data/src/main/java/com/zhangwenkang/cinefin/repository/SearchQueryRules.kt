package com.zhangwenkang.cinefin.repository

import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemKind

/** 搜索结果的业务域；`ALL` 只表示用户明确允许跨域，不是服务器类型缺失。 */
@Serializable
enum class SearchScope {
    ALL,
    VIDEO,
    MUSIC,
    BOOK;

    companion object {
        fun fromRouteValue(value: String?): SearchScope =
            entries.firstOrNull { it.name == value } ?: ALL
    }
}

/**
 * W82 #25：服务器搜索的条目类型单一来源。
 *
 * 影视保留原有 Movie / Series / Episode，另补家庭视频；音乐只取可直接播放的 Audio（服务器音乐库没有 MusicAlbum 实体，见
 * `MusicRepository`）；书籍取 Book。类型按「可映射为 FindroidItem 且可打开」收敛， 不把文件夹 / 专辑容器混进结果，避免同一内容以容器和条目两种身份重复出现。
 */
object SearchQueryRules {
    private val videoItemTypes: List<BaseItemKind> =
        listOf(
            BaseItemKind.MOVIE,
            BaseItemKind.SERIES,
            BaseItemKind.EPISODE,
            BaseItemKind.VIDEO,
        )

    val includeItemTypes: List<BaseItemKind> = itemTypes(SearchScope.ALL)

    fun itemTypes(scope: SearchScope): List<BaseItemKind> =
        when (scope) {
            SearchScope.ALL -> videoItemTypes + listOf(BaseItemKind.AUDIO, BaseItemKind.BOOK)
            SearchScope.VIDEO -> videoItemTypes
            SearchScope.MUSIC -> listOf(BaseItemKind.AUDIO)
            SearchScope.BOOK -> listOf(BaseItemKind.BOOK)
        }
}
