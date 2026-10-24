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
}
