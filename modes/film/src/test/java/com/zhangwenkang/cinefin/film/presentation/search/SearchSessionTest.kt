package com.zhangwenkang.cinefin.film.presentation.search

import com.zhangwenkang.cinefin.local.LocalLibraryEntry
import com.zhangwenkang.cinefin.local.LocalMediaKind
import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.models.FindroidFolder
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.JellyfinOrigin
import com.zhangwenkang.cinefin.repository.SearchScope
import com.zhangwenkang.cinefin.repository.catalog.CatalogFreshness
import com.zhangwenkang.cinefin.repository.catalog.CatalogPrincipalContext
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadOutcome
import com.zhangwenkang.cinefin.repository.catalog.CatalogScope
import com.zhangwenkang.cinefin.repository.catalog.CatalogSnapshot
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadMediaRecord
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSessionTest {

    @Test
    fun `W1A-P3-LOCAL-ONLY-REGRESSION 服务器失败只标记 serverFailed 并保留本地结果`() = runBlocking {
        val local = hit("sample.mp3", LocalMediaKind.MUSIC)
        var received: SearchRequest? = null

        val outcome =
            executeSearch(
                SearchRequest(query = "sample", scope = SearchScope.MUSIC),
                serverSearch = { throw IllegalStateException("server down") },
                localSearch = {
                    received = it
                    listOf(local)
                },
                scope = ALICE_SCOPE,
            )

        assertTrue(outcome.serverFailed)
        assertFalse(outcome.localFailed)
        assertEquals(emptyList<FindroidItem>(), outcome.serverItems)
        assertEquals(listOf(local), outcome.localItems)
        assertEquals(1, outcome.results.size)
        assertTrue(outcome.results.single() is SearchResult.LocalLibrary)
        assertEquals(SearchRequest("sample", SearchScope.MUSIC), received)
    }

    @Test
    fun `服务器身份不可用时本地和下载结果都不落地`() = runBlocking {
        val local = hit("sample.mp3", LocalMediaKind.MUSIC)
        val download = downloadRecord(name = "sample.flac", kind = DownloadMediaKind.MUSIC)

        val outcome =
            executeSearch(
                SearchRequest(query = "sample", scope = SearchScope.MUSIC),
                serverSearch = { CatalogReadOutcome.IdentityUnavailable },
                localSearch = { listOf(local) },
                scope = ALICE_SCOPE,
                downloads = listOf(download),
            )

        assertTrue(outcome.serverFailed)
        assertTrue(outcome.localFailed)
        assertEquals(emptyList<FindroidItem>(), outcome.serverItems)
        assertEquals(emptyList<LocalSearchHit>(), outcome.localItems)
        assertTrue(outcome.results.isEmpty())
    }

    @Test
    fun `本地失败只标记 localFailed 并保留服务器结果`() = runBlocking {
        val server = folder("sample")

        val outcome =
            executeSearch(
                SearchRequest(query = "sample", scope = SearchScope.VIDEO),
                serverSearch = { available(listOf(server), ALICE_SCOPE) },
                localSearch = { throw IllegalStateException("local db down") },
                scope = ALICE_SCOPE,
            )

        assertFalse(outcome.serverFailed)
        assertTrue(outcome.localFailed)
        assertEquals(listOf(server), outcome.serverItems)
        assertEquals(emptyList<LocalSearchHit>(), outcome.localItems)
    }

    @Test
    fun `原始来源失败独立且取消不被吞掉`() = runBlocking {
        val both =
            executeSearch(
                SearchRequest(query = "sample", scope = SearchScope.ALL),
                serverSearch = { throw IllegalStateException("server down") },
                localSearch = { throw IllegalStateException("local db down") },
                scope = ALICE_SCOPE,
            )

        assertTrue(both.serverFailed)
        assertTrue(both.localFailed)
        assertTrue(both.serverItems.isEmpty())
        assertTrue(both.localItems.isEmpty())

        val cancelled = runCatching {
            executeSearch(
                SearchRequest(query = "sample", scope = SearchScope.ALL),
                serverSearch = { throw CancellationException("cancelled") },
                localSearch = { emptyList() },
                scope = ALICE_SCOPE,
            )
        }
            .exceptionOrNull()
        assertTrue(cancelled is CancellationException)
    }

    @Test
    fun `搜索请求不在调用方线程执行`() = runBlocking {
        val callerThread = Thread.currentThread()
        var serverThread: Thread? = null
        var localThread: Thread? = null

        executeSearch(
            SearchRequest(query = "sample", scope = SearchScope.VIDEO),
            serverSearch = {
                serverThread = Thread.currentThread()
                available(listOf(folder("sample")), ALICE_SCOPE)
            },
            localSearch = {
                localThread = Thread.currentThread()
                emptyList()
            },
            scope = ALICE_SCOPE,
        )

        assertNotEquals(callerThread, serverThread)
        assertNotEquals(callerThread, localThread)
    }

    @Test
    fun `页内 scope 只显示所属分类`() {
        val video = SearchState(scope = SearchScope.VIDEO)
        val music = SearchState(scope = SearchScope.MUSIC)
        val book = SearchState(scope = SearchScope.BOOK)
        val all = SearchState(scope = SearchScope.ALL)

        assertEquals(listOf(SearchCategory.VIDEO), video.visibleCategories)
        assertEquals(listOf(SearchCategory.MUSIC), music.visibleCategories)
        assertEquals(listOf(SearchCategory.BOOK), book.visibleCategories)
        assertEquals(SearchCategory.entries, all.visibleCategories)
    }

    @Test
    fun `搜索会话冻结 canonical scope 并只折叠完整同源下载`() = runBlocking {
        val itemId = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val serverItem = folder("sample").copy(id = itemId, kind = "BOOK")
        val record =
            DownloadMediaRecord(
                itemId = itemId.toString(),
                kind = DownloadMediaKind.BOOK,
                serverId = ALICE_SCOPE.origin.serverId,
                userId = ALICE.toString(),
                sourceId = null,
                displayName = "sample",
            )

        val outcome =
            executeSearch(
                SearchRequest(query = "sample", scope = SearchScope.BOOK),
                serverSearch = { available(listOf(serverItem), ALICE_SCOPE) },
                localSearch = { emptyList() },
                scope = ALICE_SCOPE,
                downloads = listOf(record),
            )

        assertEquals(
            setOf(SearchSource.SERVER, SearchSource.DOWNLOADED),
            outcome.results.single().sources,
        )
    }

    @Test
    fun `无身份时服务器本地和下载结果都不落地`() = runBlocking {
        val local = hit("only-this.mp4", LocalMediaKind.VIDEO)
        val download = downloadRecord(name = "only-this", serverId = "server-a", userId = ALICE)

        val outcome =
            executeSearch(
                SearchRequest(query = "only-this", scope = SearchScope.VIDEO),
                serverSearch = { available(listOf(folder("only-this")), ALICE_SCOPE) },
                localSearch = { listOf(local) },
                scope = null,
                downloads = listOf(download),
            )

        assertTrue(outcome.serverFailed)
        assertTrue(outcome.localFailed)
        assertTrue(outcome.serverItems.isEmpty())
        assertTrue(outcome.localItems.isEmpty())
        assertTrue(outcome.results.isEmpty())
    }

    @Test
    fun `下载结果只保留匹配 query 与 scope 的条目`() {
        val matching =
            downloadRecord(
                name = "only-this.flac",
                kind = DownloadMediaKind.MUSIC,
                itemId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
            )
        val unrelated = downloadRecord(name = "unrelated.flac", kind = DownloadMediaKind.MUSIC)
        val wrongScope = downloadRecord(name = "only-this.mp4", kind = DownloadMediaKind.VIDEO)

        val filtered =
            DownloadSearchRules.filter(
                request = SearchRequest("only-this", SearchScope.MUSIC),
                scope = ALICE_SCOPE,
                downloads = listOf(matching, unrelated, wrongScope),
            )

        assertEquals(listOf(matching), filtered)
    }

    @Test
    fun `显式其他 server 或 user 与未知来源的下载都隐藏`() {
        val matching = downloadRecord(name = "same", serverId = "server-a", userId = ALICE)
        val otherServer = downloadRecord(name = "same", serverId = "server-b", userId = ALICE)
        val otherUser = downloadRecord(name = "same", serverId = "server-a", userId = BOB)
        val unknown = downloadRecord(name = "same", serverId = null, userId = null)

        val filtered =
            DownloadSearchRules.filter(
                request = SearchRequest("same", SearchScope.VIDEO),
                scope = ALICE_SCOPE,
                downloads = listOf(otherServer, matching, otherUser, unknown),
            )

        assertEquals(listOf(matching), filtered)
    }

    @Test
    fun `身份匹配但 video source 缺失时下载保持独立`() = runBlocking {
        val itemId = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val record =
            downloadRecord(
                name = "sample",
                itemId = itemId,
                serverId = "server-a",
                userId = ALICE,
                sourceId = null,
            )

        val outcome =
            executeSearch(
                SearchRequest(query = "sample", scope = SearchScope.VIDEO),
                serverSearch = {
                    available(listOf(folder("sample").copy(id = itemId)), ALICE_SCOPE)
                },
                localSearch = { emptyList() },
                scope = ALICE_SCOPE,
                downloads = listOf(record),
            )

        assertEquals(2, outcome.results.size)
        assertEquals(setOf(SearchSource.SERVER), outcome.results.first().sources)
        assertEquals(SearchSource.DOWNLOADED, outcome.results.last().sources.single())
        assertEquals(record, (outcome.results.last() as SearchResult.Download).record)
    }

    @Test
    fun `只有其他 server 或 user 的部分下载在当前 scope 隐藏`() {
        val otherServer =
            downloadRecord(
                name = "same",
                serverId = "server-b",
                userId = null,
                sourceId = null,
            )
        val otherUser =
            downloadRecord(
                name = "same",
                serverId = null,
                userId = BOB,
                sourceId = null,
            )

        val filtered =
            DownloadSearchRules.filter(
                request = SearchRequest("same", SearchScope.VIDEO),
                scope = ALICE_SCOPE,
                downloads = listOf(otherServer, otherUser),
            )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `身份缺失或损坏的部分下载不得跨身份可见`() {
        val missing =
            downloadRecord(
                name = "same",
                serverId = null,
                userId = null,
                sourceId = null,
            )
        val corrupt =
            DownloadMediaRecord(
                itemId = UUID.fromString("33333333-3333-4333-8333-333333333333").toString(),
                kind = DownloadMediaKind.VIDEO,
                serverId = "server-a",
                userId = "not-a-uuid",
                sourceId = null,
                displayName = "same",
            )

        val filtered =
            DownloadSearchRules.filter(
                request = SearchRequest("same", SearchScope.VIDEO),
                scope = ALICE_SCOPE,
                downloads = listOf(missing, corrupt),
            )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `current scope 缺失时所有下载都拒绝`() {
        val matching = downloadRecord(name = "same", serverId = "server-a", userId = ALICE)

        val filtered =
            DownloadSearchRules.filter(
                request = SearchRequest("same", SearchScope.VIDEO),
                scope = null,
                downloads = listOf(matching),
            )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `scope guard 同时检查请求、scope 与 snapshot content generation`() {
        val guard = SearchCatalogGuard()
        val generation = guard.begin(requested = ALICE_SCOPE, current = ALICE_SCOPE)
        val currentSnapshot = snapshot(listOf(folder("sample")), ALICE_SCOPE, generation = 5L)
        val oldSnapshot = snapshot(listOf(folder("old")), ALICE_SCOPE, generation = 4L)
        val wrongScopeSnapshot = snapshot(listOf(folder("other")), BOB_SCOPE, generation = 6L)

        assertTrue(guard.accepts(generation, currentSnapshot, ALICE_SCOPE))
        assertFalse(guard.accepts(generation, oldSnapshot, ALICE_SCOPE))
        assertFalse(guard.accepts(generation, wrongScopeSnapshot, ALICE_SCOPE))
        assertFalse(guard.accepts(generation, currentSnapshot, BOB_SCOPE))

        val newerRequest = guard.begin(requested = ALICE_SCOPE, current = ALICE_SCOPE)
        assertFalse(guard.accepts(generation, currentSnapshot, ALICE_SCOPE))
        assertTrue(guard.accepts(newerRequest, currentSnapshot, ALICE_SCOPE))
    }

    @Test
    fun `scope guard rejects same origin after identity generation changes`() {
        val guard = SearchCatalogGuard()
        val nextScope = ALICE_SCOPE.copy(identityGeneration = 2L)
        val requestGeneration = guard.begin(requested = nextScope, current = nextScope)

        assertFalse(
            guard.accepts(
                requestGeneration,
                snapshot(listOf(folder("old-generation")), ALICE_SCOPE, generation = 1L),
                nextScope,
            )
        )
        assertTrue(
            guard.accepts(
                requestGeneration,
                snapshot(listOf(folder("new-generation")), nextScope, generation = 1L),
                nextScope,
            )
        )
    }

    private fun available(
        items: List<FindroidItem>,
        scope: CatalogScope,
        generation: Long = 1L,
        freshness: CatalogFreshness = CatalogFreshness.FRESH,
    ): CatalogReadOutcome<List<FindroidItem>> =
        CatalogReadOutcome.Available(snapshot = snapshot(items, scope, generation, freshness))

    private fun snapshot(
        items: List<FindroidItem>,
        scope: CatalogScope,
        generation: Long,
        freshness: CatalogFreshness = CatalogFreshness.FRESH,
    ): CatalogSnapshot<List<FindroidItem>> =
        CatalogSnapshot(
            scope = scope,
            contentGeneration = generation,
            freshness = freshness,
            observedAtEpochMs = 1L,
            value = items,
        )

    private fun hit(name: String, kind: LocalMediaKind): LocalSearchHit =
        LocalSearchHit(
            entry =
                LocalLibraryEntry(
                    itemId = UUID.nameUUIDFromBytes(name.toByteArray()),
                    folderId = 1L,
                    name = name,
                    kind = kind,
                    documentUri = "content://test/$name",
                    relativePath = name,
                    sizeBytes = 1024L,
                    lastModified = 0L,
                    title = null,
                ),
            folderLabel = "Test",
            libraryId = 1L,
        )

    private fun folder(name: String): FindroidFolder =
        FindroidFolder(
            id = UUID.nameUUIDFromBytes(name.toByteArray()),
            name = name,
            played = false,
            favorite = false,
            unplayedItemCount = null,
            images = FindroidImages(),
            kind = "VIDEO",
        )

    private fun downloadRecord(
        name: String,
        kind: DownloadMediaKind = DownloadMediaKind.VIDEO,
        itemId: UUID = UUID.nameUUIDFromBytes(name.toByteArray()),
        serverId: String? = "server-a",
        userId: UUID? = ALICE,
        sourceId: String? = if (kind == DownloadMediaKind.BOOK) null else "source-a",
    ) =
        DownloadMediaRecord(
            itemId = itemId.toString(),
            kind = kind,
            serverId = serverId,
            userId = userId?.toString(),
            sourceId = sourceId,
            displayName = name,
        )

    private companion object {
        val ALICE: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val BOB: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val ALICE_SCOPE =
            CatalogScope(
                origin = JellyfinOrigin("server-a", ALICE),
                principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
                identityGeneration = 1L,
            )
        val BOB_SCOPE =
            CatalogScope(
                origin = JellyfinOrigin("server-a", BOB),
                principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
                identityGeneration = 2L,
            )
    }
}
