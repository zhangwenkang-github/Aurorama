package com.zhangwenkang.cinefin.film.presentation.search

import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.SearchScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import timber.log.Timber

data class SearchRequest(val query: String, val scope: SearchScope)

data class SearchOutcome(
    val serverItems: List<FindroidItem>,
    val localItems: List<LocalSearchHit>,
    val serverFailed: Boolean,
    val localFailed: Boolean,
)

/** 两类来源并行执行，但成功和失败严格按来源归因。取消必须继续上抛，不能被当成用户可见的普通失败。 */
internal suspend fun executeSearch(
    request: SearchRequest,
    serverSearch: suspend (SearchRequest) -> List<FindroidItem>,
    localSearch: suspend (SearchRequest) -> List<LocalSearchHit>,
): SearchOutcome = coroutineScope {
    val server = async { captureSource("server search") { serverSearch(request) } }
    val local = async { captureSource("local search") { localSearch(request) } }
    val serverResult = server.await()
    val localResult = local.await()
    SearchOutcome(
        serverItems = SearchResultRules.dedupeServer(serverResult.items),
        localItems = SearchResultRules.dedupeLocal(localResult.items),
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
