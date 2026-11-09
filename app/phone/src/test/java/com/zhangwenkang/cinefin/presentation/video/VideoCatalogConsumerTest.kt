package com.zhangwenkang.cinefin.presentation.video

import androidx.paging.PagingSource
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.repository.JellyfinOrigin
import com.zhangwenkang.cinefin.repository.catalog.CatalogFreshness
import com.zhangwenkang.cinefin.repository.catalog.CatalogHomeData
import com.zhangwenkang.cinefin.repository.catalog.CatalogHomeQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogItemsPageQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogLibrariesQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogMovieQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogPrincipalContext
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadOutcome
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadPort
import com.zhangwenkang.cinefin.repository.catalog.CatalogScope
import com.zhangwenkang.cinefin.repository.catalog.CatalogSearchQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogSnapshot
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VideoCatalogConsumerTest {
    @Test
    fun `aggregate load keeps one content generation across libraries`() = runBlocking {
        var call = 0
        val port =
            FakePagePort(SCOPE) {
                call += 1
                available(SCOPE, generation = 7L, items = listOf(collection("page-$call")))
            }
        val source =
            VideoAggregatePagingSource(
                catalogReadPort = port,
                frozenScope = SCOPE,
                libraryIds = listOf(LIBRARY_A, LIBRARY_B),
            )

        val page = source.load(refresh(loadSize = 2)) as PagingSource.LoadResult.Page

        assertEquals(listOf("page-1", "page-2"), page.data.map { it.name })
        assertEquals(7L, page.nextKey?.contentGeneration)
        assertEquals(1, page.nextKey?.libraryIndex)
        assertEquals(1, page.nextKey?.startIndex)
    }

    @Test
    fun `content generation change inside one load returns an error without mixing pages`() =
        runBlocking {
            var call = 0
            val port =
                FakePagePort(SCOPE) {
                    call += 1
                    available(
                        SCOPE,
                        generation = if (call == 1) 7L else 8L,
                        items = listOf(collection("page-$call")),
                    )
                }
            val source =
                VideoAggregatePagingSource(
                    catalogReadPort = port,
                    frozenScope = SCOPE,
                    libraryIds = listOf(LIBRARY_A, LIBRARY_B),
                )

            val result = source.load(refresh(loadSize = 2))

            assertTrue(result is PagingSource.LoadResult.Error)
            assertTrue(
                (result as PagingSource.LoadResult.Error).throwable
                    is ContentGenerationChangedException
            )
        }

    @Test
    fun `scope change during aggregate load invalidates the old page`() = runBlocking {
        var call = 0
        lateinit var port: FakePagePort
        port =
            FakePagePort(SCOPE) {
                call += 1
                if (call == 2) port.scope = SCOPE.copy(identityGeneration = 2L)
                available(SCOPE, generation = 7L, items = listOf(collection("page-$call")))
            }
        val source =
            VideoAggregatePagingSource(
                catalogReadPort = port,
                frozenScope = SCOPE,
                libraryIds = listOf(LIBRARY_A, LIBRARY_B),
            )

        val result = source.load(refresh(loadSize = 2))

        assertTrue(result is PagingSource.LoadResult.Error)
        assertTrue(
            (result as PagingSource.LoadResult.Error).throwable is CatalogIdentityChangedException
        )
    }

    @Test
    fun `identity unavailable returns a load error instead of an old page`() = runBlocking {
        val port = FakePagePort(SCOPE) { CatalogReadOutcome.IdentityUnavailable }
        val source =
            VideoAggregatePagingSource(
                catalogReadPort = port,
                frozenScope = SCOPE,
                libraryIds = listOf(LIBRARY_A),
            )

        val result = source.load(refresh(loadSize = 1))

        assertTrue(result is PagingSource.LoadResult.Error)
        assertTrue(
            (result as PagingSource.LoadResult.Error).throwable is CatalogIdentityChangedException
        )
    }

    @Test
    fun `paging cancellation keeps cancellation semantics`() = runBlocking {
        val port = FakePagePort(SCOPE) { throw CancellationException("cancelled") }
        val source =
            VideoAggregatePagingSource(
                catalogReadPort = port,
                frozenScope = SCOPE,
                libraryIds = listOf(LIBRARY_A),
            )

        try {
            source.load(refresh(1))
            fail("expected CancellationException")
        } catch (cancellation: CancellationException) {
            assertEquals("cancelled", cancellation.message)
        }
    }

    @Test
    fun `different source scopes cannot reuse the same completed cursor`() = runBlocking {
        val otherScope = SCOPE.copy(identityGeneration = 2L)
        val port =
            FakePagePort(otherScope) {
                available(otherScope, generation = 8L, items = listOf(collection("new")))
            }
        val source =
            VideoAggregatePagingSource(
                catalogReadPort = port,
                frozenScope = SCOPE,
                libraryIds = listOf(LIBRARY_A),
            )
        val cursor =
            VideoAggregateCursor(
                libraryIndex = 0,
                startIndex = 1,
                contentGeneration = 7L,
            )

        val result = source.load(PagingSource.LoadParams.Append(cursor, 1, false))

        assertTrue(result is PagingSource.LoadResult.Error)
        assertTrue(
            (result as PagingSource.LoadResult.Error).throwable is CatalogIdentityChangedException
        )
    }

    private fun refresh(loadSize: Int): PagingSource.LoadParams.Refresh<VideoAggregateCursor> =
        PagingSource.LoadParams.Refresh(
            key = null,
            loadSize = loadSize,
            placeholdersEnabled = false,
        )

    private fun available(
        scope: CatalogScope,
        generation: Long,
        items: List<FindroidItem>,
    ): CatalogReadOutcome<List<FindroidItem>> =
        CatalogReadOutcome.Available(
            CatalogSnapshot(
                scope = scope,
                contentGeneration = generation,
                freshness = CatalogFreshness.FRESH,
                observedAtEpochMs = 1L,
                value = items,
            )
        )

    private fun collection(name: String): FindroidCollection =
        FindroidCollection(
            id = UUID.nameUUIDFromBytes(name.toByteArray()),
            name = name,
            type = CollectionType.Movies,
            images = FindroidImages(),
        )

    private class FakePagePort(
        initialScope: CatalogScope?,
        private val page: suspend (CatalogItemsPageQuery) -> CatalogReadOutcome<List<FindroidItem>>,
    ) : CatalogReadPort {
        var scope: CatalogScope? = initialScope

        override suspend fun currentScope(): CatalogScope? = scope

        override suspend fun search(
            query: CatalogSearchQuery
        ): CatalogReadOutcome<List<FindroidItem>> = error("search must not run")

        override suspend fun home(query: CatalogHomeQuery): CatalogReadOutcome<CatalogHomeData> =
            error("home must not run")

        override suspend fun libraries(
            query: CatalogLibrariesQuery
        ): CatalogReadOutcome<List<FindroidCollection>> = error("libraries must not run")

        override suspend fun itemsPage(
            query: CatalogItemsPageQuery
        ): CatalogReadOutcome<List<FindroidItem>> = page(query)

        override suspend fun movie(query: CatalogMovieQuery): CatalogReadOutcome<FindroidMovie> =
            error("movie must not run")
    }

    private companion object {
        const val SERVER_ID = "server-a"
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val LIBRARY_A: UUID = UUID.fromString("55555555-5555-4555-8555-555555555555")
        val LIBRARY_B: UUID = UUID.fromString("66666666-6666-4666-8666-666666666666")
        val SCOPE =
            CatalogScope(
                origin = JellyfinOrigin(SERVER_ID, USER_ID),
                principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
                identityGeneration = 1L,
            )
    }
}
