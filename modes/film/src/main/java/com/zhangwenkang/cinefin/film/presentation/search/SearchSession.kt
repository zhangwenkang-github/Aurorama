package com.zhangwenkang.cinefin.film.presentation.search

import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.SearchScope
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadOutcome
import com.zhangwenkang.cinefin.repository.catalog.CatalogScope
import com.zhangwenkang.cinefin.repository.catalog.CatalogSnapshot
import com.zhangwenkang.cinefin.utils.DownloadMediaRecord
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber

data class SearchRequest(val query: String, val scope: SearchScope)

/**
 * 搜索结果落地前的 canonical scope 守卫。
 *
 * request generation 防止旧请求覆盖新请求；scope 防止搜索途中切号；snapshot content generation 防止已被新内容代际替换的旧快照进入界面。
 */
internal class SearchCatalogGuard {
    private var generation = 0L
    private var activeScope: CatalogScope? = null
    private var startedInScope = false
    private var acceptedGenerationScope: CatalogScope? = null
    private var acceptedContentGeneration: Long? = null

    fun beginPending(): Long {
        generation += 1
        activeScope = null
        startedInScope = false
        acceptedGenerationScope = null
        acceptedContentGeneration = null
        return generation
    }

    fun start(
        generation: Long,
        requested: CatalogScope?,
        current: CatalogScope?,
    ): Boolean {
        if (!isCurrent(generation)) return false
        if (requested == null || current == null || requested != current) {
            activeScope = null
            startedInScope = false
            acceptedGenerationScope = null
            acceptedContentGeneration = null
            return false
        }
        activeScope = requested
        startedInScope = true
        acceptedGenerationScope = requested
        acceptedContentGeneration = null
        return true
    }

    fun begin(requested: CatalogScope?, current: CatalogScope?): Long =
        beginPending().also { generation -> start(generation, requested, current) }

    fun invalidate() {
        generation += 1
        activeScope = null
        startedInScope = false
        acceptedGenerationScope = null
        acceptedContentGeneration = null
    }

    fun isCurrent(generation: Long): Boolean = generation == this.generation

    fun isScopeStarted(generation: Long): Boolean = isCurrent(generation) && startedInScope

    fun accepts(
        generation: Long,
        snapshot: CatalogSnapshot<*>?,
        current: CatalogScope?,
    ): Boolean {
        val active = activeScope
        if (
            !isCurrent(generation) ||
                !startedInScope ||
                active == null ||
                current == null ||
                active != current
        ) {
            return false
        }
        if (snapshot == null) return true
        if (snapshot.scope != active || snapshot.scope != current) return false
        if (snapshot.contentGeneration < 0L) return false
        if (acceptedGenerationScope == snapshot.scope) {
            val accepted = acceptedContentGeneration
            if (accepted != null && snapshot.contentGeneration < accepted) return false
            acceptedContentGeneration = maxOf(accepted ?: 0L, snapshot.contentGeneration)
        } else {
            acceptedGenerationScope = snapshot.scope
            acceptedContentGeneration = snapshot.contentGeneration
        }
        return true
    }
}

/** 下载项先按 query / scope / canonical scope 过滤，再参与搜索折叠。 */
internal object DownloadSearchRules {
    fun filter(
        request: SearchRequest,
        scope: CatalogScope?,
        downloads: List<DownloadMediaRecord>,
    ): List<DownloadMediaRecord> {
        return downloads.filter { record ->
            record.matches(request.scope, request.query) && record.matchesScope(scope)
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

    private fun DownloadMediaRecord.matchesScope(scope: CatalogScope?): Boolean {
        if (scope == null) return false
        val explicitServerId = serverId?.takeIf { it.isNotBlank() } ?: return false
        val explicitUserId =
            userId?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
                ?: return false
        return explicitServerId == scope.origin.serverId && explicitUserId == scope.origin.userId
    }
}

data class SearchOutcome(
    val serverItems: List<FindroidItem>,
    val localItems: List<LocalSearchHit>,
    val results: List<SearchResult> = emptyList(),
    val serverFailed: Boolean,
    val localFailed: Boolean,
    val serverSnapshot: CatalogSnapshot<List<FindroidItem>>? = null,
)

/** 两类来源并行执行，但成功和失败严格按来源归因。取消必须继续上抛，不能被当成用户可见的普通失败。 */
internal suspend fun executeSearch(
    request: SearchRequest,
    serverSearch: suspend (SearchRequest) -> CatalogReadOutcome<List<FindroidItem>>,
    localSearch: suspend (SearchRequest) -> List<LocalSearchHit>,
    scope: CatalogScope? = null,
    downloads: List<DownloadMediaRecord> = emptyList(),
): SearchOutcome {
    if (scope == null) {
        return SearchOutcome(
            serverItems = emptyList(),
            localItems = emptyList(),
            results = emptyList(),
            serverFailed = true,
            localFailed = true,
        )
    }
    return withContext(Dispatchers.Default) {
        coroutineScope {
            val filteredDownloads = DownloadSearchRules.filter(request, scope, downloads)
            val server = async { captureCatalogSource(scope) { serverSearch(request) } }
            val local = async { captureSource("local search") { localSearch(request) } }
            val serverResult = server.await()
            val localResult = local.await()
            if (serverResult.identityUnavailable) {
                return@coroutineScope SearchOutcome(
                    serverItems = emptyList(),
                    localItems = emptyList(),
                    results = emptyList(),
                    serverFailed = true,
                    localFailed = true,
                )
            }
            val serverSnapshot = serverResult.snapshot
            val serverItems = serverSnapshot?.value.orEmpty()
            val serverCandidates = scope?.let { fixedScope ->
                serverItems.map { item ->
                    ServerSearchCandidate(
                        identity =
                            ServerItemIdentity(
                                serverId = fixedScope.origin.serverId,
                                userId = fixedScope.origin.userId,
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
                    ?: dedupedLocal.map { hit ->
                        SearchResult.LocalLibrary(
                            identity = LocalLibraryItemIdentity(hit.libraryId, hit.entry.itemId),
                            hit = hit,
                        )
                    } +
                        filteredDownloads
                            .distinctBy { SearchResult.Download(it).stableKey }
                            .map { SearchResult.Download(it) }
            SearchOutcome(
                serverItems = dedupedServerCandidates?.map { it.item } ?: emptyList(),
                localItems = dedupedLocal,
                results = folded,
                serverFailed = serverResult.failed,
                localFailed = localResult.failed,
                serverSnapshot = serverSnapshot,
            )
        }
    }
}

private data class SourceResult<T>(val items: List<T>, val failed: Boolean)

private data class CatalogSourceResult(
    val snapshot: CatalogSnapshot<List<FindroidItem>>?,
    val failed: Boolean,
    val identityUnavailable: Boolean,
)

private suspend fun captureCatalogSource(
    expectedScope: CatalogScope?,
    block: suspend () -> CatalogReadOutcome<List<FindroidItem>>,
): CatalogSourceResult =
    try {
        when (val outcome = block()) {
            is CatalogReadOutcome.Available -> {
                val snapshot = outcome.snapshot
                if (expectedScope != null && snapshot.scope == expectedScope) {
                    CatalogSourceResult(
                        snapshot = snapshot,
                        failed = false,
                        identityUnavailable = false,
                    )
                } else {
                    CatalogSourceResult(
                        snapshot = null,
                        failed = true,
                        identityUnavailable = true,
                    )
                }
            }
            CatalogReadOutcome.IdentityUnavailable ->
                CatalogSourceResult(
                    snapshot = null,
                    failed = true,
                    identityUnavailable = true,
                )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "server catalog search failed")
        CatalogSourceResult(
            snapshot = null,
            failed = true,
            identityUnavailable = false,
        )
    }

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
