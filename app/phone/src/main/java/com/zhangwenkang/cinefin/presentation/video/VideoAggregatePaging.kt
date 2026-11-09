package com.zhangwenkang.cinefin.presentation.video

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder
import com.zhangwenkang.cinefin.repository.catalog.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.model.api.BaseItemKind

/** 视频模式页的库门控（W53；W73 #16 扩到全部「视频类」库）。 */
internal fun pickVideoLibraries(libraries: List<FindroidCollection>): List<FindroidCollection> =
    libraries.filter {
        it.type == CollectionType.Movies ||
            it.type == CollectionType.TvShows ||
            it.type == CollectionType.HomeVideos ||
            it.type == CollectionType.Mixed
    }

/** 聚合列表的条目类型：电影 + 剧集 + 家庭视频（`Video`）。 */
internal val VIDEO_AGGREGATE_TYPES: List<BaseItemKind> =
    listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.VIDEO)

/**
 * 聚合列表游标：[libraryIndex] = 当前库在视频库列表里的下标，[startIndex] = 该库内的偏移。 [contentGeneration] binds every page
 * in one aggregate traversal to one catalog fence.
 */
internal data class VideoAggregateCursor(
    val libraryIndex: Int,
    val startIndex: Int,
    val contentGeneration: Long? = null,
)

internal fun advanceVideoAggregateCursor(
    cursor: VideoAggregateCursor,
    fetched: Int,
    requested: Int,
): VideoAggregateCursor =
    if (fetched < requested) {
        VideoAggregateCursor(
            libraryIndex = cursor.libraryIndex + 1,
            startIndex = 0,
            contentGeneration = cursor.contentGeneration,
        )
    } else {
        VideoAggregateCursor(
            libraryIndex = cursor.libraryIndex,
            startIndex = cursor.startIndex + fetched,
            contentGeneration = cursor.contentGeneration,
        )
    }

internal fun isVideoAggregateFinished(cursor: VideoAggregateCursor, libraryCount: Int): Boolean =
    cursor.libraryIndex >= libraryCount

internal class CatalogIdentityChangedException : IllegalStateException("catalog scope changed")

internal class ContentGenerationChangedException :
    IllegalStateException("catalog content generation changed")

/**
 * 按库顺序拼接每个视频库的「电影 + 剧集 + 家庭视频」。
 *
 * 同一 [load] 与后续 append 都绑定同一冻结 scope 和 content generation；任何漂移都返回
 * [PagingSource.LoadResult.Error]，绝不把两代页面拼接上屏。
 */
internal class VideoAggregatePagingSource(
    private val catalogReadPort: CatalogReadPort,
    private val frozenScope: CatalogScope,
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
                if (catalogReadPort.currentScope() != frozenScope) {
                    throw CatalogIdentityChangedException()
                }
                val limit = params.loadSize - items.size
                val outcome =
                    catalogReadPort.itemsPage(
                        CatalogItemsPageQuery(
                            frozenScope = frozenScope,
                            parentId = libraryIds[cursor.libraryIndex],
                            includeTypes = VIDEO_AGGREGATE_TYPES,
                            recursive = true,
                            sortBy = SortBy.DATE_ADDED,
                            sortOrder = SortOrder.DESCENDING,
                            startIndex = cursor.startIndex,
                            limit = limit,
                            filters = null,
                            genres = null,
                            studios = null,
                        )
                    )
                if (catalogReadPort.currentScope() != frozenScope) {
                    throw CatalogIdentityChangedException()
                }
                val snapshot =
                    when (outcome) {
                        CatalogReadOutcome.IdentityUnavailable ->
                            throw CatalogIdentityChangedException()
                        is CatalogReadOutcome.Available -> outcome.snapshot
                    }
                if (snapshot.scope != frozenScope) {
                    throw CatalogIdentityChangedException()
                }
                val establishedGeneration = cursor.contentGeneration
                if (
                    establishedGeneration != null &&
                        establishedGeneration != snapshot.contentGeneration
                ) {
                    throw ContentGenerationChangedException()
                }
                val page = snapshot.value
                items += page
                cursor =
                    advanceVideoAggregateCursor(
                        cursor = cursor.copy(contentGeneration = snapshot.contentGeneration),
                        fetched = page.size,
                        requested = limit,
                    )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return LoadResult.Error(error)
        }
        return LoadResult.Page(
            data = items,
            prevKey = null,
            nextKey = cursor.takeUnless { isVideoAggregateFinished(it, libraryIds.size) },
        )
    }

    override fun getRefreshKey(
        state: PagingState<VideoAggregateCursor, FindroidItem>
    ): VideoAggregateCursor? = null
}
