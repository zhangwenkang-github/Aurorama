package com.zhangwenkang.cinefin.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import timber.log.Timber

class ItemsPagingSource(
    private val jellyfinRepository: JellyfinRepository,
    private val parentId: UUID?,
    private val includeTypes: List<BaseItemKind>?,
    private val recursive: Boolean,
    private val sortBy: SortBy,
    private val sortOrder: SortOrder,
    private val filters: List<ItemFilter>? = null,
    private val genres: List<String>? = null,
    private val studios: List<String>? = null,
) : PagingSource<Int, FindroidItem>() {

    companion object {
        /** 库内容页分页页片大小（预取下一页 / 计数门槛共用同一常量）。 */
        const val PAGE_SIZE = 10
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, FindroidItem> {
        val position = params.key ?: 0

        Timber.d("Retrieving position: $position")

        return try {
            val items =
                jellyfinRepository.getItems(
                    parentId = parentId,
                    includeTypes = includeTypes,
                    recursive = recursive,
                    sortBy = sortBy,
                    sortOrder = sortOrder,
                    startIndex = position,
                    limit = params.loadSize,
                    filters = filters,
                    genres = genres,
                    studios = studios,
                )
            LoadResult.Page(
                data = items,
                prevKey = if (position == 0) null else position - params.loadSize,
                nextKey = if (items.isEmpty()) null else position + params.loadSize,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, FindroidItem>): Int {
        return 0
    }
}
