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
                downloads = downloads,
            )
        }
            ?: serverResult.items.distinctBy { it.id }.map { SearchResult.UnidentifiedServer(it) } +
                downloads
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
