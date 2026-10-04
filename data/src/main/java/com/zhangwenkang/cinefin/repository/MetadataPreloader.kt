package com.zhangwenkang.cinefin.repository

import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import timber.log.Timber

/**
 * W69b：元数据预加载（低优先级 / 可取消 / 失败静默）。
 *
 * 不新增第二套缓存：预取只是调用仓库的**同名读方法**，命中 [MetadataCache]（TTL 内直接复用、同 key 并发去重）； 页面随后进入时同样的读方法就变成 `metadata
 * cache hit`（logcat 可见）。全部在 [scope]（IO、并发上限 2）执行， 不阻塞首屏；失败只打 debug 日志，不向上抛；进程退出 / 被取消即停止。
 */
class MetadataPreloader(private val repositoryProvider: Provider<JellyfinRepository>) {

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(PARALLELISM))

    /** W69b：按来源分组的在途预取——页面离开时可只取消自己的预取，不影响其他页面。 */
    private val jobs = ConcurrentHashMap<String, MutableSet<Job>>()

    /** 预取一批卡片的「详情」字段（首页走廊 / 库网格可见条目）：详情页打开时直接命中缓存。 */
    fun prefetchDetails(items: List<FindroidItem>, limit: Int = DETAIL_LIMIT, source: String = "") {
        if (items.isEmpty()) return
        items.distinctBy { it.id }.take(limit).forEach { item -> prefetchDetail(item, source) }
    }

    /** 预取单个条目的详情（同类条目并发上限 [PARALLELISM]，同 id 由仓库去重）。 */
    fun prefetchDetail(item: FindroidItem, source: String = "") {
        Timber.d("prefetch detail: %s", item.id)
        launchPrefetch(source) {
            when (item) {
                is FindroidMovie -> repositoryProvider.get().getMovie(item.id)
                is FindroidShow -> repositoryProvider.get().getShow(item.id)
                is FindroidSeason -> repositoryProvider.get().getSeason(item.id)
                is FindroidEpisode -> repositoryProvider.get().getEpisode(item.id)
                // 书籍 / 音乐 / 合集：封面与列表字段已在首页取到，不额外预取详情。
                else -> Unit
            }
        }
    }

    /** 取消某个来源尚未完成的预取（页面离开 / 不再需要；不影响其他来源）。 */
    fun cancel(source: String) {
        jobs.remove(source)?.forEach { it.cancel() }
    }

    private fun launchPrefetch(source: String, block: suspend () -> Unit) {
        val job = scope.launch {
            runCatching { block() }.onFailure { Timber.d(it, "prefetch failed: %s", source) }
        }
        if (source.isNotEmpty()) {
            jobs.computeIfAbsent(source) { ConcurrentHashMap.newKeySet() } += job
            job.invokeOnCompletion { jobs[source]?.remove(job) }
        }
    }

    /** 预取库内容页的下一页（首屏渲染后 / 接近末尾）：与 Paging 用**同一组参数**，命中同一条缓存键， 之后分页加载该页即为 `metadata cache hit`。 */
    fun prefetchItemsPage(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        startIndex: Int,
        limit: Int,
        filters: List<ItemFilter>? = null,
        genres: List<String>? = null,
        studios: List<String>? = null,
        source: String = "",
    ) {
        Timber.d("prefetch items page: %s@%s", parentId, startIndex)
        launchPrefetch(source) {
            repositoryProvider
                .get()
                .getItems(
                    parentId = parentId,
                    includeTypes = includeTypes,
                    recursive = recursive,
                    sortBy = sortBy,
                    sortOrder = sortOrder,
                    startIndex = startIndex,
                    limit = limit,
                    filters = filters,
                    genres = genres,
                    studios = studios,
                )
            Unit
        }
    }

    companion object {
        /** 预取并发上限（低优先级：不抢首页 / 分页请求的带宽）。 */
        const val PARALLELISM = 2

        /** 一次预取多少张卡片的详情。 */
        const val DETAIL_LIMIT = 6
    }
}
