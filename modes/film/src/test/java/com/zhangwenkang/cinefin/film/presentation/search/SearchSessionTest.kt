package com.zhangwenkang.cinefin.film.presentation.search

import com.zhangwenkang.cinefin.local.LocalLibraryEntry
import com.zhangwenkang.cinefin.local.LocalMediaKind
import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.models.FindroidFolder
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.SearchScope
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadMediaRecord
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSessionTest {

    @Test
    fun `服务器失败只标记 serverFailed 并保留本地结果`() = runBlocking {
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
            )

        assertTrue(outcome.serverFailed)
        assertFalse(outcome.localFailed)
        assertEquals(emptyList<FindroidItem>(), outcome.serverItems)
        assertEquals(listOf(local), outcome.localItems)
        assertEquals(SearchRequest("sample", SearchScope.MUSIC), received)
    }

    @Test
    fun `本地失败只标记 localFailed 并保留服务器结果`() = runBlocking {
        val server = folder("sample")

        val outcome =
            executeSearch(
                SearchRequest(query = "sample", scope = SearchScope.VIDEO),
                serverSearch = { listOf(server) },
                localSearch = { throw IllegalStateException("local db down") },
            )

        assertFalse(outcome.serverFailed)
        assertTrue(outcome.localFailed)
        assertEquals(listOf(server), outcome.serverItems)
        assertEquals(emptyList<LocalSearchHit>(), outcome.localItems)
    }

    @Test
    fun `两侧失败互相独立且取消不被吞掉`() = runBlocking {
        val both =
            executeSearch(
                SearchRequest(query = "sample", scope = SearchScope.ALL),
                serverSearch = { throw IllegalStateException("server down") },
                localSearch = { throw IllegalStateException("local db down") },
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
            )
        }
            .exceptionOrNull()
        assertTrue(cancelled is CancellationException)
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
    fun `搜索会话冻结 namespace 并只折叠完整同源下载`() = runBlocking {
        val itemId = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val serverItem = folder("sample").copy(id = itemId, kind = "BOOK")
        val record =
            DownloadMediaRecord(
                itemId = itemId.toString(),
                kind = DownloadMediaKind.BOOK,
                serverId = "server-a",
                userId = userId.toString(),
                sourceId = null,
                displayName = "sample",
            )

        val outcome =
            executeSearch(
                SearchRequest(query = "sample", scope = SearchScope.BOOK),
                serverSearch = { listOf(serverItem) },
                localSearch = { emptyList() },
                namespace = SearchNamespace("server-a", userId),
                downloads = listOf(record),
            )

        assertEquals(
            setOf(SearchSource.SERVER, SearchSource.DOWNLOADED),
            outcome.results.single().sources,
        )
    }

    @Test
    fun `无 userId 的缓存服务器结果保留展示但绝不与下载折叠`() = runBlocking {
        val itemId = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val record =
            DownloadMediaRecord(
                itemId = itemId.toString(),
                kind = DownloadMediaKind.BOOK,
                serverId = "server-a",
                userId = UUID.randomUUID().toString(),
                displayName = "sample",
            )

        val outcome =
            executeSearch(
                SearchRequest(query = "sample", scope = SearchScope.BOOK),
                serverSearch = { listOf(folder("sample").copy(id = itemId, kind = "BOOK")) },
                localSearch = { emptyList() },
                namespace = null,
                downloads = listOf(record),
            )

        assertEquals(2, outcome.results.size)
        assertTrue(outcome.results.first() is SearchResult.UnidentifiedServer)
        assertEquals(SearchSource.DOWNLOADED, outcome.results.last().sources.single())
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
                namespace = null,
                downloads = listOf(matching, unrelated, wrongScope),
            )

        assertEquals(listOf(matching), filtered)
    }

    @Test
    fun `显式其他 server 或 user 的下载隐藏而未知来源保留`() {
        val namespace = SearchNamespace("server-a", ALICE)
        val matching = downloadRecord(name = "same", serverId = "server-a", userId = ALICE)
        val otherServer = downloadRecord(name = "same", serverId = "server-b", userId = ALICE)
        val otherUser = downloadRecord(name = "same", serverId = "server-a", userId = BOB)
        val unknown = downloadRecord(name = "same", serverId = null, userId = null)

        val filtered =
            DownloadSearchRules.filter(
                request = SearchRequest("same", SearchScope.VIDEO),
                namespace = namespace,
                downloads = listOf(otherServer, matching, otherUser, unknown),
            )

        assertEquals(listOf(matching, unknown), filtered)
    }

    @Test
    fun `无 namespace 时仍显示 query 命中的本地库结果`() = runBlocking {
        val local = hit("only-this.mp4", LocalMediaKind.VIDEO)

        val outcome =
            executeSearch(
                SearchRequest(query = "only-this", scope = SearchScope.VIDEO),
                serverSearch = { emptyList() },
                localSearch = { listOf(local) },
                namespace = null,
            )

        assertEquals(listOf(local), outcome.localItems)
        assertEquals(
            listOf(
                SearchResult.LocalLibrary(LocalLibraryItemIdentity(1L, local.entry.itemId), local)
            ),
            outcome.results,
        )
    }

    @Test
    fun `namespace generation 在切号或新请求后拒绝旧结果`() {
        val guard = SearchNamespaceGuard()
        val generation =
            guard.begin(
                requested = SearchNamespace("server-a", ALICE),
                current = SearchNamespace("server-a", ALICE),
            )

        assertTrue(
            guard.accepts(
                generation,
                current = SearchNamespace("server-a", ALICE),
            )
        )
        assertFalse(
            guard.accepts(
                generation,
                current = SearchNamespace("server-b", ALICE),
            )
        )
        assertFalse(
            guard.accepts(
                generation,
                current = SearchNamespace("server-a", BOB),
            )
        )

        val current = SearchNamespace("server-a", ALICE)
        val stale = guard.begin(requested = current, current = current)
        guard.begin(requested = current, current = current)
        assertFalse(guard.accepts(stale, current = current))
    }

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
    ) =
        DownloadMediaRecord(
            itemId = itemId.toString(),
            kind = kind,
            serverId = serverId,
            userId = userId?.toString(),
            sourceId = if (kind == DownloadMediaKind.BOOK) null else "source-a",
            displayName = name,
        )

    private companion object {
        val ALICE: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val BOB: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
    }
}
