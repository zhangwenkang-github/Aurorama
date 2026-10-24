package com.zhangwenkang.cinefin.film.presentation.search

import com.zhangwenkang.cinefin.local.LocalLibraryEntry
import com.zhangwenkang.cinefin.local.LocalMediaKind
import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadMediaRecord
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIdentityFoldTest {

    @Test
    fun `同一 item UUID 不同 serverId 并列`() {
        val results =
            SearchResultRules.fold(
                serverItems =
                    listOf(
                        server(movie(ITEM_ID), serverId = "server-a"),
                        server(movie(ITEM_ID), serverId = "server-b"),
                    ),
                localHits = emptyList(),
                downloads = emptyList(),
            )

        assertEquals(2, results.size)
        assertEquals(2, results.map { it.stableKey }.distinct().size)
    }

    @Test
    fun `同一 server 与 item 但不同 userId 并列`() {
        val results =
            SearchResultRules.fold(
                serverItems =
                    listOf(
                        server(movie(ITEM_ID), userId = ALICE),
                        server(movie(ITEM_ID), userId = BOB),
                    ),
                localHits = emptyList(),
                downloads = emptyList(),
            )

        assertEquals(2, results.size)
        assertEquals(2, results.map { it.stableKey }.distinct().size)
    }

    @Test
    fun `本地库 A 与 B 同 itemId 并列且不与服务器裸 UUID 折叠`() {
        val localA = localHit(libraryId = 11L)
        val localB = localHit(libraryId = 22L)

        val results =
            SearchResultRules.fold(
                serverItems = listOf(server(movie(ITEM_ID))),
                localHits = listOf(localA, localB),
                downloads = emptyList(),
            )

        assertEquals(3, results.size)
        assertEquals(
            listOf(SearchSource.SERVER, SearchSource.LOCAL_LIBRARY, SearchSource.LOCAL_LIBRARY),
            results.map { it.sources.single() },
        )
    }

    @Test
    fun `完整下载证明命中同一服务器条目时折叠并保留双来源`() {
        val results =
            SearchResultRules.fold(
                serverItems =
                    listOf(
                        server(
                            movie(ITEM_ID, sourceId = MEDIA_SOURCE_ID),
                            serverId = SERVER_ID,
                        )
                    ),
                localHits = emptyList(),
                downloads = listOf(downloadRecord(sourceId = MEDIA_SOURCE_ID)),
            )

        assertEquals(1, results.size)
        assertEquals(
            setOf(SearchSource.SERVER, SearchSource.DOWNLOADED),
            results.single().sources,
        )
    }

    @Test
    fun `同标题但身份不同不按标题折叠`() {
        val first = server(movie(ITEM_ID, name = "相同标题"), serverId = "server-a")
        val second = server(movie(OTHER_ITEM_ID, name = "相同标题"), serverId = "server-b")

        val results =
            SearchResultRules.fold(
                serverItems = listOf(first, second),
                localHits = emptyList(),
                downloads = emptyList(),
            )

        assertEquals(2, results.size)
        assertEquals(listOf("相同标题", "相同标题"), results.map { it.displayName })
    }

    @Test
    fun `下载证明缺任一段时来源未知并独立`() {
        val unknown = downloadRecord(userId = null)

        val results =
            SearchResultRules.fold(
                serverItems =
                    listOf(
                        server(
                            movie(ITEM_ID, sourceId = MEDIA_SOURCE_ID),
                            serverId = SERVER_ID,
                        )
                    ),
                localHits = emptyList(),
                downloads = listOf(unknown),
            )

        assertEquals(2, results.size)
        val download = results.filterIsInstance<SearchResult.Download>().single()
        assertEquals(setOf(SearchSource.DOWNLOADED), download.sources)
        assertEquals(null, download.record.provenance())
    }

    @Test
    fun `同 server user item 但媒体 source 不同不误折叠`() {
        val results =
            SearchResultRules.fold(
                serverItems =
                    listOf(
                        server(
                            movie(ITEM_ID, sourceId = "source-a"),
                            serverId = SERVER_ID,
                        )
                    ),
                localHits = emptyList(),
                downloads = listOf(downloadRecord(sourceId = "source-b")),
            )

        assertEquals(2, results.size)
        assertEquals(SearchSource.SERVER, results[0].sources.single())
        assertEquals(SearchSource.DOWNLOADED, results[1].sources.single())
    }

    @Test
    fun `顺序稳定且重复折叠幂等`() {
        val first = server(movie(ITEM_ID, name = "A"), serverId = "server-a")
        val second = server(movie(OTHER_ITEM_ID, name = "B"), serverId = "server-b")
        val local = localHit(libraryId = 11L)
        val unknown = downloadRecord(userId = null)

        val once = SearchResultRules.fold(listOf(first, second), listOf(local), listOf(unknown))
        val twice = SearchResultRules.fold(listOf(first, second), listOf(local), listOf(unknown))
        val inserted = server(movie(UUID.randomUUID(), name = "中间项"), serverId = "server-c")
        val withUnrelated =
            SearchResultRules.fold(
                listOf(first, inserted, second),
                listOf(local),
                listOf(unknown),
            )

        assertEquals(once, twice)
        assertEquals(listOf("A", "B", LOCAL_NAME, "条目"), once.map { it.displayName })
        assertEquals(once.map { it.stableKey }, once.map { it.stableKey }.distinct())
        assertTrue(once.first().sources.contains(SearchSource.SERVER))
        assertEquals(
            once
                .filterNot { it.displayName == LOCAL_NAME || it.displayName == "条目" }
                .map { it.stableKey },
            withUnrelated
                .filterNot {
                    it.displayName == "中间项" ||
                        it.displayName == LOCAL_NAME ||
                        it.displayName == "条目"
                }
                .map { it.stableKey },
        )
    }

    private fun server(
        item: FindroidItem,
        serverId: String = SERVER_ID,
        userId: UUID = ALICE,
    ) = ServerSearchCandidate(ServerItemIdentity(serverId, userId, item.id), item)

    private fun movie(
        id: UUID,
        name: String = "条目",
        sourceId: String? = null,
    ) =
        FindroidMovie(
            id = id,
            name = name,
            originalTitle = null,
            overview = "",
            sources =
                sourceId?.let {
                    listOf(
                        FindroidSource(
                            id = it,
                            name = "source",
                            type = FindroidSourceType.REMOTE,
                            path = "",
                            size = 0L,
                            mediaStreams = emptyList(),
                        )
                    )
                } ?: emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            people = emptyList(),
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            status = "Ended",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
            kind = "MOVIE",
        )

    private fun localHit(libraryId: Long) =
        LocalSearchHit(
            entry =
                LocalLibraryEntry(
                    itemId = ITEM_ID,
                    folderId = 1L,
                    name = LOCAL_NAME,
                    kind = LocalMediaKind.VIDEO,
                    documentUri = "content://test/$libraryId/$ITEM_ID",
                    relativePath = LOCAL_NAME,
                    sizeBytes = 1L,
                    lastModified = 0L,
                ),
            folderLabel = "库 $libraryId",
            libraryId = libraryId,
        )

    private fun downloadRecord(
        sourceId: String? = MEDIA_SOURCE_ID,
        userId: UUID? = ALICE,
    ) =
        DownloadMediaRecord(
            itemId = ITEM_ID.toString(),
            kind = DownloadMediaKind.VIDEO,
            serverId = SERVER_ID,
            userId = userId?.toString(),
            sourceId = sourceId,
            displayName = "条目",
        )

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val OTHER_ITEM_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val ALICE: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val BOB: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
        const val SERVER_ID = "server-a"
        const val MEDIA_SOURCE_ID = "source-a"
        const val LOCAL_NAME = "本地.mp4"
    }
}
