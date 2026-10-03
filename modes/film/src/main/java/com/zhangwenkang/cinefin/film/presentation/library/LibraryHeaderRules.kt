package com.zhangwenkang.cinefin.film.presentation.library

import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.SortBy
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter

/**
 * 库内容页（视频库 / 书籍库 / 书架共用）头部的**纯规则**（W54-B）。
 *
 * 这里只放"看得见的口径"：tab 出现规则、工具行动作集合、计数文案、筛选到请求参数的映射、 库类型到查询类型的映射。放在
 * `modes:film`（而不是页面里）是为了让三处入口共用同一份口径， 且可以脱离 Compose / Android 单测（见 `LibraryHeaderRulesTest`）。
 */

/** 库内容页顶部 tabs（W54-B，Jellyfin 官方客户端 IA 口径）。 */
enum class LibraryTab {
    /** 库名 tab：库本体内容网格（默认）。 */
    Library,
    /** 建议：库内推荐（随机抽样）。 */
    Suggestions,
    /** 即将播出：仅剧集库（`/Shows/Upcoming`）。 */
    Upcoming,
    /** 类型：库内 Genre 分类网格。 */
    Genres,
    /** 制片发行商：库内 Studio 分类网格。 */
    Studios,
    /** 剧集：剧集库内的全部单集。 */
    Episodes,
}

/** 库内容页显示方式（工具行第二组按钮）：网格 / 列表。 */
enum class LibraryViewMode {
    Grid,
    List,
}

/** 工具行（funnel）常用筛选集合，取值映射到 Jellyfin `filters`。 */
enum class LibraryFilter {
    Unplayed,
    Played,
    Favorite,
}

/** 工具行动作可见性（按当前 tab 计算，避免"点了没用"的按钮）。 */
data class LibraryToolbarSpec(
    val showViewMode: Boolean,
    val showSort: Boolean,
    val showFilter: Boolean,
)

/**
 * tab 出现规则：按库类型给出 tab 序列，该库类型不适用的 tab 不出现。
 *
 * 口径：①「库名」永远是第一项；②「建议」对所有可浏览库成立；③「即将播出」只对剧集库成立 （Jellyfin 的 Upcoming 就是"即将播出的单集"）；④「类型」对所有可浏览库成立（图书
 * / 家庭视频 有 Genre 就用、没有就是空态）；⑤「制片发行商」只对电影 / 剧集 / 混合 / 文件夹库成立 （图书、音乐、家庭视频的 Studio
 * 字段没有业务含义）；⑥「剧集」只对剧集库成立。
 */
fun libraryTabs(libraryType: CollectionType): List<LibraryTab> =
    when (libraryType) {
        CollectionType.Movies ->
            listOf(
                LibraryTab.Library,
                LibraryTab.Suggestions,
                LibraryTab.Genres,
                LibraryTab.Studios,
            )
        CollectionType.TvShows ->
            listOf(
                LibraryTab.Library,
                LibraryTab.Suggestions,
                LibraryTab.Upcoming,
                LibraryTab.Genres,
                LibraryTab.Studios,
                LibraryTab.Episodes,
            )
        CollectionType.Books ->
            listOf(LibraryTab.Library, LibraryTab.Suggestions, LibraryTab.Genres)
        CollectionType.HomeVideos ->
            listOf(LibraryTab.Library, LibraryTab.Suggestions, LibraryTab.Genres)
        CollectionType.BoxSets ->
            listOf(LibraryTab.Library, LibraryTab.Suggestions, LibraryTab.Genres)
        CollectionType.Music ->
            listOf(LibraryTab.Library, LibraryTab.Suggestions, LibraryTab.Genres)
        CollectionType.Mixed,
        CollectionType.Folders ->
            listOf(
                LibraryTab.Library,
                LibraryTab.Suggestions,
                LibraryTab.Genres,
                LibraryTab.Studios,
            )
        // 播放列表库只有"列表本体"，其它 tab 都没有意义
        CollectionType.Playlists,
        CollectionType.LiveTv,
        CollectionType.Unknown -> listOf(LibraryTab.Library)
    }

/** 工具行动作集合：库名 tab 全量；条目型 tab 保留计数与视图切换；分类 tab 只有计数。 */
fun libraryToolbarSpec(tab: LibraryTab): LibraryToolbarSpec =
    when (tab) {
        LibraryTab.Library ->
            LibraryToolbarSpec(
                showViewMode = true,
                showSort = true,
                showFilter = true,
            )
        LibraryTab.Suggestions,
        LibraryTab.Upcoming,
        LibraryTab.Episodes ->
            LibraryToolbarSpec(
                showViewMode = true,
                showSort = false,
                showFilter = false,
            )
        LibraryTab.Genres,
        LibraryTab.Studios ->
            LibraryToolbarSpec(
                showViewMode = false,
                showSort = false,
                showFilter = false,
            )
    }

/** 常用筛选集合（按库类型）：播放列表库不提供筛选。 */
fun libraryFilters(libraryType: CollectionType): List<LibraryFilter> =
    when (libraryType) {
        CollectionType.Playlists,
        CollectionType.LiveTv,
        CollectionType.Unknown -> emptyList()
        else -> listOf(LibraryFilter.Unplayed, LibraryFilter.Played, LibraryFilter.Favorite)
    }

/** 筛选 → Jellyfin `filters` 参数（null = 不过滤）。 */
fun libraryFilterItemFilters(filter: LibraryFilter?): List<ItemFilter>? =
    when (filter) {
        LibraryFilter.Unplayed -> listOf(ItemFilter.IS_UNPLAYED)
        LibraryFilter.Played -> listOf(ItemFilter.IS_PLAYED)
        LibraryFilter.Favorite -> listOf(ItemFilter.IS_FAVORITE)
        null -> null
    }

/** 筛选文案：影视域用"看"、阅读域用"读"（同一个筛选在两类库里的中文不同）。 */
fun libraryFilterLabelRes(libraryType: CollectionType, filter: LibraryFilter): Int =
    when (filter) {
        LibraryFilter.Favorite -> FilmR.string.library_filter_favorite
        LibraryFilter.Unplayed ->
            if (libraryType == CollectionType.Books) FilmR.string.library_filter_unread
            else FilmR.string.library_filter_unplayed
        LibraryFilter.Played ->
            if (libraryType == CollectionType.Books) FilmR.string.library_filter_read
            else FilmR.string.library_filter_played
    }

/** 库内容页要查询的条目类型（与 Jellyfin 库类型一一对应）。 */
fun libraryItemTypes(libraryType: CollectionType): List<BaseItemKind>? =
    when (libraryType) {
        CollectionType.Movies -> listOf(BaseItemKind.MOVIE)
        CollectionType.TvShows -> listOf(BaseItemKind.SERIES)
        CollectionType.BoxSets -> listOf(BaseItemKind.BOX_SET)
        CollectionType.Music -> listOf(BaseItemKind.MUSIC_ALBUM)
        CollectionType.Books -> listOf(BaseItemKind.BOOK)
        CollectionType.HomeVideos -> listOf(BaseItemKind.VIDEO)
        CollectionType.Playlists -> listOf(BaseItemKind.PLAYLIST)
        CollectionType.Mixed,
        CollectionType.Folders ->
            listOf(
                BaseItemKind.FOLDER,
                BaseItemKind.MOVIE,
                BaseItemKind.SERIES,
                BaseItemKind.MUSIC_ALBUM,
                BaseItemKind.BOOK,
                BaseItemKind.VIDEO,
                BaseItemKind.PLAYLIST,
            )
        else -> null
    }

/** 混合 / 文件夹库要下钻（保留文件夹层级），其余库一律递归取全部条目。 */
fun libraryRecursive(types: List<BaseItemKind>?): Boolean =
    types == null || !types.contains(BaseItemKind.FOLDER)

/** 排序映射：剧集库的「按观看时间」要换成 Jellyfin 的 `SeriesDatePlayed`。 */
fun librarySortByFor(libraryType: CollectionType, sortBy: SortBy): SortBy =
    if (libraryType == CollectionType.TvShows && sortBy == SortBy.DATE_PLAYED)
        SortBy.SERIES_DATE_PLAYED
    else sortBy

/**
 * 工具行计数文案（W54-B）：`1-94 / 94`。
 *
 * - 总数未知（离线 / 请求失败）时只显示已加载条数；
 * - 已加载 >= 总数时显示完整范围 `1-N / N`；
 * - 空库返回空串（空态本身已经在说"这里没有内容"）。
 */
fun libraryCountText(loadedCount: Int, totalCount: Int?): String {
    val loaded = loadedCount.coerceAtLeast(0)
    if (totalCount == null || totalCount <= 0) {
        return if (loaded > 0) loaded.toString() else ""
    }
    if (loaded <= 0) return "0 / $totalCount"
    val visible = loaded.coerceAtMost(totalCount)
    return "1-$visible / $totalCount"
}
