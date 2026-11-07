package com.zhangwenkang.cinefin.film.presentation.search

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
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCatalogConsumerTest {
    @Test
    fun `stale snapshot remains explicitly stale at search consumer boundary`() = runBlocking {
        val item = folder("sample")
        val outcome =
            executeSearch(
                request = SearchRequest("sample", SearchScope.VIDEO),
                serverSearch = {
                    available(
                        value = listOf(item),
                        scope = ALICE_SCOPE,
                        generation = 2L,
                        freshness = CatalogFreshness.STALE,
                    )
                },
                localSearch = { emptyList() },
                scope = ALICE_SCOPE,
            )

        assertEquals(listOf(item), outcome.serverItems)
        assertEquals(CatalogFreshness.STALE, outcome.serverSnapshot?.freshness)
        assertEquals(2L, outcome.serverSnapshot?.contentGeneration)
        assertFalse(outcome.serverFailed)
    }

    @Test
    fun `snapshot from another scope is rejected before consumer projection`() = runBlocking {
        val outcome =
            executeSearch(
                request = SearchRequest("sample", SearchScope.VIDEO),
                serverSearch = {
                    available(
                        value = listOf(folder("other-user")),
                        scope = BOB_SCOPE,
                    )
                },
                localSearch = { emptyList<LocalSearchHit>() },
                scope = ALICE_SCOPE,
            )

        assertTrue(outcome.serverFailed)
        assertTrue(outcome.serverItems.isEmpty())
        assertTrue(outcome.results.isEmpty())
        assertNull(outcome.serverSnapshot)
    }

    @Test
    fun `consumer guard rejects switched scope and superseded content generation`() {
        val guard = SearchCatalogGuard()
        val requestGeneration = guard.begin(requested = ALICE_SCOPE, current = ALICE_SCOPE)
        val freshSnapshot = snapshot(ALICE_SCOPE, generation = 8L)
        val olderSnapshot = snapshot(ALICE_SCOPE, generation = 7L)

        assertTrue(guard.accepts(requestGeneration, freshSnapshot, ALICE_SCOPE))
        assertFalse(guard.accepts(requestGeneration, olderSnapshot, ALICE_SCOPE))
        assertFalse(guard.accepts(requestGeneration, freshSnapshot, BOB_SCOPE))

        guard.invalidate()
        assertFalse(guard.accepts(requestGeneration, freshSnapshot, ALICE_SCOPE))
    }

    @Test
    fun `consumer guard never starts or accepts a request without identity`() {
        val guard = SearchCatalogGuard()

        val requestGeneration = guard.begin(requested = null, current = null)

        assertFalse(guard.isScopeStarted(requestGeneration))
        assertFalse(guard.accepts(requestGeneration, null, null))
        assertFalse(
            guard.accepts(
                requestGeneration,
                snapshot(ALICE_SCOPE, generation = 1L),
                ALICE_SCOPE,
            )
        )
    }

    @Test
    fun `pending request token is invalidated before scope resolution can report failure`() {
        val guard = SearchCatalogGuard()
        val first = guard.beginPending()
        val second = guard.beginPending()

        assertFalse(guard.isCurrent(first))
        assertFalse(guard.isScopeStarted(first))
        assertTrue(guard.isCurrent(second))
    }

    private fun available(
        value: List<FindroidItem>,
        scope: CatalogScope,
        generation: Long = 1L,
        freshness: CatalogFreshness = CatalogFreshness.FRESH,
    ): CatalogReadOutcome<List<FindroidItem>> =
        CatalogReadOutcome.Available(
            CatalogSnapshot(
                scope = scope,
                contentGeneration = generation,
                freshness = freshness,
                observedAtEpochMs = 123L,
                value = value,
            )
        )

    private fun snapshot(
        scope: CatalogScope,
        generation: Long,
    ): CatalogSnapshot<List<FindroidItem>> =
        CatalogSnapshot(
            scope = scope,
            contentGeneration = generation,
            freshness = CatalogFreshness.FRESH,
            observedAtEpochMs = 123L,
            value = listOf(folder("sample")),
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

    private companion object {
        val ALICE: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val BOB: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val ALICE_SCOPE =
            CatalogScope(
                origin = JellyfinOrigin("server-a", ALICE),
                principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
            )
        val BOB_SCOPE =
            CatalogScope(
                origin = JellyfinOrigin("server-a", BOB),
                principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
            )
    }
}
