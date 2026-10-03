package com.zhangwenkang.cinefin.presentation.video

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

/** 视频模式页的库门控 / 聚合口径（W53）：只认 movies + tvshows 两类库。 */
internal fun pickVideoLibraries(libraries: List<FindroidCollection>): List<FindroidCollection> =
    libraries.filter {
        it.type == CollectionType.Movies || it.type == CollectionType.TvShows
    }

/** 聚合列表的条目类型：电影 + 剧集。 */
internal val VIDEO_AGGREGATE_TYPES: List<BaseItemKind> =
    listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)

/**
 * 聚合列表游标：[libraryIndex] = 当前库在视频库列表里的下标，[startIndex] = 该库内的偏移。
 *
 * 聚合列表把全部视频库的条目按「库顺序」拼接，每个库内部按「最近添加」（DateCreated 倒序）排列—— Jellyfin
 * 没有"一次查询多个媒体库"的接口，跨库全局排序需要服务器端祖先过滤，本波不做（见 UI_PLAN D53）。
 */
internal data class VideoAggregateCursor(val libraryIndex: Int, val startIndex: Int)

/**
 * 游标推进（纯逻辑，单测覆盖）：
 *
 * - 本次返回条数 < 请求条数（含返回 0 条）= 这个库已经取完 → 换下一个库，偏移归零；
 * - 否则同一库内继续往后取。
 */
internal fun advanceVideoAggregateCursor(
    cursor: VideoAggregateCursor,
    fetched: Int,
    requested: Int,
): VideoAggregateCursor =
    if (fetched < requested) {
        VideoAggregateCursor(libraryIndex = cursor.libraryIndex + 1, startIndex = 0)
    } else {
        VideoAggregateCursor(
            libraryIndex = cursor.libraryIndex,
            startIndex = cursor.startIndex + fetched,
        )
    }

/** 游标是否已经走过最后一个视频库（聚合列表取完）。 */
internal fun isVideoAggregateFinished(cursor: VideoAggregateCursor, libraryCount: Int): Boolean =
    cursor.libraryIndex >= libraryCount

/**
 * 视频模式页「聚合列表」分页源（W53）：
 *
 * 按库顺序拼接每个视频库的「电影 + 剧集」（每库内 `DateCreated` 倒序）；一次 [load] 会把 `params.loadSize` 填满，中途遇到的空库 /
 * 已取完的库直接跳过，不会让列表提前结束。
 */
internal class VideoAggregatePagingSource(
    private val repository: JellyfinRepository,
    private val libraryIds: List<UUID>,
) : PagingSource<VideoAggregateCursor, FindroidItem>() {

    override suspend fun load(
        params: LoadParams<VideoAggregateCursor>
    ): LoadResult<VideoAggregateCursor, FindroidItem> {
        var cursor = params.key ?: VideoAggregateCursor(libraryIndex = 0, startIndex = 0)
        val items = mutableListOf<FindroidItem>()
        try {
            while (
                items.size < params.loadSize && !isVideoAggregateFinished(cursor, libraryIds.size)
            ) {
                val limit = params.loadSize - items.size
                val page =
                    repository.getItems(
                        parentId = libraryIds[cursor.libraryIndex],
                        includeTypes = VIDEO_AGGREGATE_TYPES,
                        recursive = true,
                        sortBy = SortBy.DATE_ADDED,
                        sortOrder = SortOrder.DESCENDING,
                        startIndex = cursor.startIndex,
                        limit = limit,
                    )
                items += page
                cursor = advanceVideoAggregateCursor(cursor, fetched = page.size, requested = limit)
            }
        } catch (e: Exception) {
            return LoadResult.Error(e)
        }
        return LoadResult.Page(
            data = items,
            // 只向后翻：回到顶部即从头刷新。
            prevKey = null,
            nextKey = cursor.takeUnless { isVideoAggregateFinished(it, libraryIds.size) },
        )
    }

    /** 刷新（下拉 / 库列表变化）从头开始取，不做锚点恢复。 */
    override fun getRefreshKey(
        state: PagingState<VideoAggregateCursor, FindroidItem>
    ): VideoAggregateCursor? = null
}
