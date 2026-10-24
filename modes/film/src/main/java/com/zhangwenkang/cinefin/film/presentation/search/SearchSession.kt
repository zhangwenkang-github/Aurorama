package com.zhangwenkang.cinefin.film.presentation.search

import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.SearchScope
import com.zhangwenkang.cinefin.utils.DownloadMediaRecord
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import timber.log.Timber

data class SearchRequest(val query: String, val scope: SearchScope)

/** 一次搜索固定使用的服务器 / 用户 namespace；不得在结果返回后再从当前会话改写。 */
data class SearchNamespace(val serverId: String, val userId: UUID)

/**
 * 搜索结果落地前的 namespace 守卫。
 *
 * generation 防止旧请求覆盖新请求；requested==current 防止搜索途中切 server/user 后把旧域结果 emit 到新会话。
 */
internal class SearchNamespaceGuard {
    private var generation = 0L
    private var activeNamespace: SearchNamespace? = null
    private var startedInNamespace = false

    fun begin(requested: SearchNamespace?, current: SearchNamespace?): Long {
        generation += 1
        activeNamespace = requested
        startedInNamespace = requested == current
        return generation
    }

    fun invalidate() {
        generation += 1
        activeNamespace = null
        startedInNamespace = false
    }

    fun isCurrent(generation: Long): Boolean = generation == this.generation

    fun accepts(generation: Long, current: SearchNamespace?): Boolean =
        isCurrent(generation) && startedInNamespace && activeNamespace == current
}

/** 下载项先按 query / scope / namespace 过滤，再参与搜索折叠。 */
internal object DownloadSearchRules {
    fun filter(
        request: SearchRequest,
        namespace: SearchNamespace?,
        downloads: List<DownloadMediaRecord>,
    ): List<DownloadMediaRecord> {
        return downloads.filter { record ->
            record.matches(request.scope, request.query) && record.matchesNamespace(namespace)
        }
    }

    private fun DownloadMediaRecord.matches(scope: SearchScope, query: String): Boolean {
        val normalized = query.trim()
        if (
            normalized.isNotEmpty() &&
                listOfNotNull(displayName, albumName, artist).none { field ->
                    field.contains(normalized, ignoreCase = true)
                }
        ) {
            return false
        }
        val category = SearchResultRules.categoryOf(this)
        return when (scope) {
            SearchScope.ALL -> true
            SearchScope.VIDEO -> category == SearchCategory.VIDEO
            SearchScope.MUSIC -> category == SearchCategory.MUSIC
            SearchScope.BOOK -> category == SearchCategory.BOOK
        }
    }

    private fun DownloadMediaRecord.matchesNamespace(namespace: SearchNamespace?): Boolean {
        if (namespace == null) return true
        val explicitServerId = serverId?.takeIf { it.isNotBlank() }
        if (explicitServerId != null && explicitServerId != namespace.serverId) return false
        val explicitUserId = userId?.let { value ->
            runCatching { UUID.fromString(value) }.getOrNull()
        }
        return explicitUserId == null || explicitUserId == namespace.userId
    }
}

data class SearchOutcome(
    val serverItems: List<FindroidItem>,
    val localItems: List<LocalSearchHit>,
    val results: List<SearchResult> = emptyList(),
    val serverFailed: Boolean,
    val localFailed: Boolean,
)

/** 两类来源并行执行，但成功和失败严格按来源归因。取消必须继续上抛，不能被当成用户可见的普通失败。 */
internal suspend fun executeSearch(
    request: SearchRequest,
    serverSearch: suspend (SearchRequest) -> List<FindroidItem>,
    localSearch: suspend (SearchRequest) -> List<LocalSearchHit>,
    namespace: SearchNamespace? = null,
    downloads: List<DownloadMediaRecord> = emptyList(),
): SearchOutcome = coroutineScope {
    val filteredDownloads = DownloadSearchRules.filter(request, namespace, downloads)
    val server = async { captureSource("server search") { serverSearch(request) } }
    val local = async { captureSource("local search") { localSearch(request) } }
    val serverResult = server.await()
    val localResult = local.await()
    val serverCandidates = namespace?.let { fixedNamespace ->
        serverResult.items.map { item ->
            ServerSearchCandidate(
                identity =
                    ServerItemIdentity(
                        serverId = fixedNamespace.serverId,
                        userId = fixedNamespace.userId,
                        itemId = item.id,
                    ),
                item = item,
            )
        }
    }
    val dedupedServerCandidates = serverCandidates?.distinctBy { it.identity }
    val dedupedLocal = SearchResultRules.dedupeLocal(localResult.items)
    val folded =
        dedupedServerCandidates?.let { candidates ->
            SearchResultRules.fold(
                serverItems = candidates,
                localHits = dedupedLocal,
                downloads = filteredDownloads,
            )
        }
            ?: serverResult.items.distinctBy { it.id }.map { SearchResult.UnidentifiedServer(it) } +
                dedupedLocal.map { hit ->
                    SearchResult.LocalLibrary(
                        identity = LocalLibraryItemIdentity(hit.libraryId, hit.entry.itemId),
                        hit = hit,
                    )
                } +
                filteredDownloads
                    .distinctBy { SearchResult.Download(it).stableKey }
                    .map { SearchResult.Download(it) }
    SearchOutcome(
        serverItems =
            dedupedServerCandidates?.map { it.item }
                ?: SearchResultRules.dedupeServer(serverResult.items),
        localItems = dedupedLocal,
        results = folded,
        serverFailed = serverResult.failed,
        localFailed = localResult.failed,
    )
}

private data class SourceResult<T>(val items: List<T>, val failed: Boolean)

private suspend fun <T> captureSource(
    label: String,
    block: suspend () -> List<T>,
): SourceResult<T> =
    try {
        SourceResult(items = block(), failed = false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "%s failed", label)
        SourceResult(items = emptyList(), failed = true)
    }
