package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.di.CatalogReadModule
import com.zhangwenkang.cinefin.identity.IDENTITY_STATE_PREFERENCE_KEY
import com.zhangwenkang.cinefin.identity.IdentityReadPort
import com.zhangwenkang.cinefin.identity.IdentitySelection
import com.zhangwenkang.cinefin.identity.IdentitySnapshot
import com.zhangwenkang.cinefin.identity.IdentityStateCodec
import com.zhangwenkang.cinefin.identity.IdentityStateStatus
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidMovieDto
import com.zhangwenkang.cinefin.models.ServerAddress
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder
import com.zhangwenkang.cinefin.models.User
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
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CatalogReadersContractTest {
    @Test
    fun `dynamic catalog routes all four readers and preserves frozen scope`() = runBlocking {
        val online = FakeCatalogPort(scope = ONLINE_SCOPE)
        val offline = FakeCatalogPort(scope = OFFLINE_SCOPE)
        val dynamic =
            CatalogReadModule.dynamicCatalogReadPort(
                online = online,
                offline = offline,
                identityReadPort = identityPort(identity(offlineMode = false)),
            )

        assertAvailableScope(
            ONLINE_SCOPE,
            dynamic.home(homeQuery(ONLINE_SCOPE)),
        )
        assertAvailableScope(
            ONLINE_SCOPE,
            dynamic.libraries(CatalogLibrariesQuery(ONLINE_SCOPE)),
        )
        assertAvailableScope(
            ONLINE_SCOPE,
            dynamic.itemsPage(pageQuery(ONLINE_SCOPE)),
        )
        assertAvailableScope(
            ONLINE_SCOPE,
            dynamic.movie(CatalogMovieQuery(ITEM_ID, ONLINE_SCOPE)),
        )

        assertEquals(1, online.homeCalls)
        assertEquals(1, online.libraryCalls)
        assertEquals(1, online.pageCalls)
        assertEquals(1, online.movieCalls)
        assertEquals(0, offline.homeCalls)
        assertEquals(0, offline.libraryCalls)
        assertEquals(0, offline.pageCalls)
        assertEquals(0, offline.movieCalls)
    }

    @Test
    fun `dynamic catalog rejects stale or mismatched reader snapshots`() = runBlocking {
        val online =
            FakeCatalogPort(scope = ONLINE_SCOPE).apply {
                homeResult = { query ->
                    available(query.frozenScope.copy(identityGeneration = 99L), CatalogHomeData())
                }
                libraryResult = { query ->
                    available(query.frozenScope.copy(identityGeneration = 99L), emptyList())
                }
                pageResult = { query ->
                    available(query.frozenScope.copy(identityGeneration = 99L), emptyList())
                }
                movieResult = { query ->
                    available(query.frozenScope.copy(identityGeneration = 99L), movie())
                }
            }
        val dynamic =
            CatalogReadModule.dynamicCatalogReadPort(
                online = online,
                offline = FakeCatalogPort(scope = OFFLINE_SCOPE),
                identityReadPort = identityPort(identity(offlineMode = false)),
            )

        assertTrue(dynamic.home(homeQuery(ONLINE_SCOPE)) is CatalogReadOutcome.IdentityUnavailable)
        assertTrue(
            dynamic.libraries(CatalogLibrariesQuery(ONLINE_SCOPE))
                is CatalogReadOutcome.IdentityUnavailable
        )
        assertTrue(
            dynamic.itemsPage(pageQuery(ONLINE_SCOPE)) is CatalogReadOutcome.IdentityUnavailable
        )
        assertTrue(
            dynamic.movie(CatalogMovieQuery(ITEM_ID, ONLINE_SCOPE))
                is CatalogReadOutcome.IdentityUnavailable
        )
    }

    @Test
    fun `offline readers use the frozen scope instead of singleton api user`() = runBlocking {
        val mappedUsers = mutableListOf<UUID>()
        val database =
            mockk<ServerDatabaseDao>(relaxed = true).apply {
                coEvery { getServerCurrentUser(SERVER_ID) } returns user(USER_ID)
                coEvery { getServerCurrentAddress(SERVER_ID) } returns address()
                coEvery { getMoviesByServerId(SERVER_ID) } returns
                    listOf(movieDto(name = "local movie"))
                coEvery { getEpisodesByServerId(SERVER_ID) } returns emptyList()
                coEvery { getMovie(ITEM_ID) } returns movieDto(name = "detail")
            }
        val repository =
            JellyfinRepositoryOfflineImpl(
                context = mockk<Context>(relaxed = true),
                jellyfinApi =
                    mockk<JellyfinApi>(relaxed = true).apply {
                        every { userId } returns OTHER_USER_ID
                    },
                database = database,
                appPreferences = preferences(offline = true),
                localLibrary =
                    mockk<LocalLibraryRepository>(relaxed = true).apply {
                        coEvery { syntheticMovie(any()) } returns null
                    },
                offlineMovieMapper = { dto, userId ->
                    mappedUsers += userId
                    movie(dto)
                },
                offlineShowMapper = { _, _ -> error("show mapper must not run") },
                offlineEpisodeMapper = { _, _ -> error("episode mapper must not run") },
            )

        val home =
            repository.home(
                homeQuery(OFFLINE_SCOPE)
                    .copy(
                        includeSuggestions = false,
                        includeContinueReading = false,
                        includeContinueListening = false,
                        includeNextUp = false,
                        includeUserViews = false,
                    )
            ) as CatalogReadOutcome.Available
        val detail =
            repository.movie(CatalogMovieQuery(ITEM_ID, OFFLINE_SCOPE))
                as CatalogReadOutcome.Available

        assertEquals(OFFLINE_SCOPE, home.snapshot.scope)
        assertEquals(CatalogFreshness.LOCAL_QUERY, home.snapshot.freshness)
        assertEquals(0L, home.snapshot.contentGeneration)
        assertEquals(
            listOf("local movie"),
            home.snapshot.value.resumeWatching.map(FindroidItem::name),
        )
        assertEquals(OFFLINE_SCOPE, detail.snapshot.scope)
        assertEquals("detail", detail.snapshot.value.name)
        assertEquals(listOf(USER_ID, USER_ID), mappedUsers)
        assertTrue(mappedUsers.none { it == OTHER_USER_ID })
    }

    @Test
    fun `offline libraries and pages are legal empty snapshots but stale scopes fail closed`() =
        runBlocking {
            val repository = offlineRepository(currentUser = USER_ID)

            val libraries =
                repository.libraries(CatalogLibrariesQuery(OFFLINE_SCOPE))
                    as CatalogReadOutcome.Available
            val page =
                repository.itemsPage(pageQuery(OFFLINE_SCOPE)) as CatalogReadOutcome.Available

            assertTrue(libraries.snapshot.value.isEmpty())
            assertTrue(page.snapshot.value.isEmpty())
            assertEquals(CatalogFreshness.LOCAL_QUERY, libraries.snapshot.freshness)
            assertEquals(0L, page.snapshot.contentGeneration)

            val staleRepository = offlineRepository(currentUser = OTHER_USER_ID)
            assertTrue(
                staleRepository.libraries(CatalogLibrariesQuery(OFFLINE_SCOPE))
                    is CatalogReadOutcome.IdentityUnavailable
            )
            assertTrue(
                staleRepository.itemsPage(pageQuery(OFFLINE_SCOPE))
                    is CatalogReadOutcome.IdentityUnavailable
            )
            assertTrue(
                staleRepository.movie(CatalogMovieQuery(ITEM_ID, OFFLINE_SCOPE))
                    is CatalogReadOutcome.IdentityUnavailable
            )
        }

    @Test
    fun `dynamic catalog clears old routing immediately when canonical identity changes`() =
        runBlocking {
            var snapshot = identity(offlineMode = false)
            val online = FakeCatalogPort(scope = ONLINE_SCOPE)
            val offline = FakeCatalogPort(scope = OFFLINE_SCOPE)
            val dynamic =
                CatalogReadModule.dynamicCatalogReadPort(
                    online = online,
                    offline = offline,
                    identityReadPort = identityPort { snapshot },
                )

            assertEquals(ONLINE_SCOPE, dynamic.currentScope())
            snapshot = identity(offlineMode = true)

            assertEquals(OFFLINE_SCOPE, dynamic.currentScope())
            assertTrue(
                dynamic.home(homeQuery(ONLINE_SCOPE)) is CatalogReadOutcome.IdentityUnavailable
            )
            assertEquals(0, online.homeCalls)
            assertAvailableScope(OFFLINE_SCOPE, dynamic.home(homeQuery(OFFLINE_SCOPE)))
            assertEquals(1, offline.homeCalls)
        }

    @Test
    fun `online catalog readers fail closed when singleton user disagrees with canonical scope`() =
        runBlocking {
            val directory = Files.createTempDirectory("catalog-readers-identity-mismatch")
            try {
                val repository =
                    JellyfinRepositoryImpl(
                        context = mockk<Context>(relaxed = true),
                        jellyfinApi =
                            mockk<JellyfinApi>(relaxed = true).apply {
                                every { userId } returns OTHER_USER_ID
                            },
                        database =
                            mockk<ServerDatabaseDao>(relaxed = true).apply {
                                coEvery { getServerCurrentUser(SERVER_ID) } returns user(USER_ID)
                                coEvery { getServerCurrentAddress(SERVER_ID) } returns address()
                            },
                        appPreferences = preferences(offline = false),
                        localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
                        metadataSnapshotStore = MetadataSnapshotStore(directory.toFile()),
                    )

                assertTrue(
                    repository.home(homeQuery(ONLINE_SCOPE))
                        is CatalogReadOutcome.IdentityUnavailable
                )
                assertTrue(
                    repository.libraries(CatalogLibrariesQuery(ONLINE_SCOPE))
                        is CatalogReadOutcome.IdentityUnavailable
                )
                assertTrue(
                    repository.itemsPage(pageQuery(ONLINE_SCOPE))
                        is CatalogReadOutcome.IdentityUnavailable
                )
                assertTrue(
                    repository.movie(CatalogMovieQuery(ITEM_ID, ONLINE_SCOPE))
                        is CatalogReadOutcome.IdentityUnavailable
                )
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun `dynamic catalog preserves cancellation and reader-local failures`() = runBlocking {
        val cancelPort = FakeCatalogPort(scope = ONLINE_SCOPE)
        cancelPort.homeResult = { throw CancellationException("cancelled") }
        val cancelling =
            CatalogReadModule.dynamicCatalogReadPort(
                online = cancelPort,
                offline = FakeCatalogPort(scope = OFFLINE_SCOPE),
                identityReadPort = identityPort(identity(offlineMode = false)),
            )
        val cancelled =
            try {
                cancelling.home(homeQuery(ONLINE_SCOPE))
                null
            } catch (cancellation: CancellationException) {
                cancellation
            }
        assertEquals("cancelled", cancelled?.message)

        val failure = IOException("permission denied")
        val failedPort = FakeCatalogPort(scope = ONLINE_SCOPE)
        failedPort.movieResult = { throw failure }
        val failing =
            CatalogReadModule.dynamicCatalogReadPort(
                online = failedPort,
                offline = FakeCatalogPort(scope = OFFLINE_SCOPE),
                identityReadPort = identityPort(identity(offlineMode = false)),
            )
        val thrown =
            try {
                failing.movie(CatalogMovieQuery(ITEM_ID, ONLINE_SCOPE))
                null
            } catch (error: IOException) {
                error
            }
        assertSame(failure, thrown)
    }

    @Test
    fun `dynamic resolver preserves cancellation from the selected adapter`() = runBlocking {
        val cancellation = CancellationException("adapter scope cancelled")
        val online =
            FakeCatalogPort(scope = ONLINE_SCOPE).apply {
                currentScopeResult = { throw cancellation }
            }
        val dynamic =
            CatalogReadModule.dynamicCatalogReadPort(
                online = online,
                offline = FakeCatalogPort(scope = OFFLINE_SCOPE),
                identityReadPort = identityPort(identity(offlineMode = false)),
            )

        try {
            dynamic.currentScope()
            fail("expected CancellationException")
        } catch (thrown: CancellationException) {
            assertEquals(cancellation.message, thrown.message)
        }
    }

    @Test
    fun `dynamic resolver preserves cancellation from canonical identity`() = runBlocking {
        val cancellation = CancellationException("canonical identity cancelled")
        val dynamic =
            CatalogReadModule.dynamicCatalogReadPort(
                online = FakeCatalogPort(scope = ONLINE_SCOPE),
                offline = FakeCatalogPort(scope = OFFLINE_SCOPE),
                identityReadPort = identityPort { throw cancellation },
            )

        try {
            dynamic.currentScope()
            fail("expected CancellationException")
        } catch (thrown: CancellationException) {
            assertEquals(cancellation.message, thrown.message)
        }
    }

    @Test
    fun `expected namespace invalidation cannot clear generation or disk after A to B barrier`() =
        runBlocking {
            val directory = Files.createTempDirectory("catalog-namespace-invalidation")
            try {
                var namespace: MetadataNamespace? = NAMESPACE_A
                val cache = MetadataCache(namespace = { namespace })
                val store = MetadataSnapshotStore(directory.toFile())
                val swr = MetadataSnapshotSwr(cache = cache, store = store)
                val expectedA = requireNotNull(cache.currentFence())
                assertTrue(cache.put("entry", "cache-a"))
                assertTrue(
                    store.write(expectedA.namespace, "entry", "disk-a")
                        is MetadataSnapshotWriteResult.Success
                )

                namespace = NAMESPACE_B
                val fenceB = requireNotNull(cache.currentFence())
                assertTrue(cache.put("entry", "cache-b"))
                assertTrue(
                    store.write(fenceB.namespace, "entry", "disk-b")
                        is MetadataSnapshotWriteResult.Success
                )

                assertFalse(swr.invalidateAll(expectedA))

                assertEquals(fenceB, cache.currentFence())
                assertEquals("cache-b", cache.get<String>(fenceB, "entry")?.value)
                val diskB =
                    store.read(fenceB, "entry", maxAgeMs = 60_000L)
                        as MetadataSnapshotReadResult.Hit
                assertEquals("disk-b", diskB.value)
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    private fun offlineRepository(currentUser: UUID): JellyfinRepositoryOfflineImpl =
        JellyfinRepositoryOfflineImpl(
            context = mockk<Context>(relaxed = true),
            jellyfinApi = mockk<JellyfinApi>(relaxed = true),
            database =
                mockk<ServerDatabaseDao>(relaxed = true).apply {
                    coEvery { getServerCurrentUser(SERVER_ID) } returns user(currentUser)
                    coEvery { getServerCurrentAddress(SERVER_ID) } returns address()
                },
            appPreferences = preferences(offline = true),
            localLibrary =
                mockk<LocalLibraryRepository>(relaxed = true).apply {
                    coEvery { syntheticMovie(any()) } returns null
                },
        )

    private fun homeQuery(scope: CatalogScope): CatalogHomeQuery =
        CatalogHomeQuery(
            frozenScope = scope,
            includeSuggestions = true,
            includeContinueWatching = true,
            includeContinueReading = true,
            includeContinueListening = true,
            includeNextUp = true,
            includeUserViews = true,
        )

    private fun pageQuery(scope: CatalogScope): CatalogItemsPageQuery =
        CatalogItemsPageQuery(
            frozenScope = scope,
            parentId = LIBRARY_ID,
            includeTypes = null,
            recursive = true,
            sortBy = SortBy.DATE_ADDED,
            sortOrder = SortOrder.DESCENDING,
            startIndex = 0,
            limit = 30,
            filters = null,
            genres = null,
            studios = null,
        )

    private fun identity(offlineMode: Boolean): IdentitySnapshot =
        IdentitySnapshot(
            selection =
                IdentitySelection(
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                    addressId = ADDRESS_ID.toString(),
                    offlineMode = offlineMode,
                ),
            identityGeneration = if (offlineMode) 2L else 1L,
            status = IdentityStateStatus.READY,
        )

    private fun identityPort(snapshot: IdentitySnapshot): IdentityReadPort = identityPort {
        snapshot
    }

    private fun identityPort(snapshot: () -> IdentitySnapshot): IdentityReadPort =
        object : IdentityReadPort {
            override suspend fun hydrate(): IdentitySnapshot = snapshot()

            override suspend fun current(): IdentitySnapshot = snapshot()
        }

    private fun preferences(offline: Boolean): AppPreferences {
        val preferences = mockk<SharedPreferences>(relaxed = true)
        val appPreferences = AppPreferences(preferences)
        val encodedIdentity = IdentityStateCodec.encode(identity(offlineMode = offline))
        every { preferences.getString(any(), any()) } answers
            {
                if (firstArg<String>() == IDENTITY_STATE_PREFERENCE_KEY) encodedIdentity
                else SERVER_ID
            }
        every { preferences.getBoolean(any(), any()) } answers
            {
                if (firstArg<String>() == appPreferences.offlineMode.backendName) offline
                else secondArg()
            }
        return appPreferences
    }

    private fun user(id: UUID): User =
        User(
            id = id,
            name = "user",
            serverId = SERVER_ID,
            accessToken = "token",
        )

    private fun address(): ServerAddress =
        ServerAddress(
            id = ADDRESS_ID,
            serverId = SERVER_ID,
            address = "https://server",
        )

    private fun movieDto(name: String): FindroidMovieDto =
        FindroidMovieDto(
            id = ITEM_ID,
            serverId = SERVER_ID,
            name = name,
            originalTitle = null,
            overview = "",
            runtimeTicks = 0L,
            premiereDate = null,
            communityRating = null,
            officialRating = null,
            status = "Released",
            productionYear = null,
            endDate = null,
            chapters = null,
        )

    private fun movie(dto: FindroidMovieDto = movieDto("movie")): FindroidMovie =
        FindroidMovie(
            id = dto.id,
            name = dto.name,
            originalTitle = dto.originalTitle,
            overview = dto.overview,
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = dto.runtimeTicks,
            playbackPositionTicks = 42L,
            premiereDate = dto.premiereDate,
            people = emptyList(),
            genres = emptyList(),
            communityRating = dto.communityRating,
            officialRating = dto.officialRating,
            status = dto.status,
            productionYear = dto.productionYear,
            endDate = dto.endDate,
            trailer = null,
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = emptyMap(),
        )

    private fun collection(name: String): FindroidCollection =
        FindroidCollection(
            id = UUID.nameUUIDFromBytes(name.toByteArray()),
            name = name,
            type = CollectionType.Movies,
            images = FindroidImages(),
        )

    private fun <T> available(scope: CatalogScope, value: T): CatalogReadOutcome<T> =
        CatalogReadOutcome.Available(
            CatalogSnapshot(
                scope = scope,
                contentGeneration = 1L,
                freshness = CatalogFreshness.FRESH,
                observedAtEpochMs = 1L,
                value = value,
            )
        )

    private fun assertAvailableScope(
        expectedScope: CatalogScope,
        outcome: CatalogReadOutcome<*>,
    ) {
        val available = outcome as CatalogReadOutcome.Available
        assertEquals(expectedScope, available.snapshot.scope)
    }

    private inner class FakeCatalogPort(var scope: CatalogScope?) : CatalogReadPort {
        var homeCalls = 0
        var libraryCalls = 0
        var pageCalls = 0
        var movieCalls = 0
        var currentScopeResult: suspend () -> CatalogScope? = { scope }
        var homeResult: suspend (CatalogHomeQuery) -> CatalogReadOutcome<CatalogHomeData> = {
            available(it.frozenScope, CatalogHomeData())
        }
        var libraryResult:
            suspend (CatalogLibrariesQuery) -> CatalogReadOutcome<List<FindroidCollection>> =
            {
                available(it.frozenScope, emptyList())
            }
        var pageResult: suspend (CatalogItemsPageQuery) -> CatalogReadOutcome<List<FindroidItem>> =
            {
                available(it.frozenScope, emptyList())
            }
        var movieResult: suspend (CatalogMovieQuery) -> CatalogReadOutcome<FindroidMovie> = {
            available(it.frozenScope, movie())
        }

        override suspend fun currentScope(): CatalogScope? = currentScopeResult()

        override suspend fun search(
            query: CatalogSearchQuery
        ): CatalogReadOutcome<List<FindroidItem>> = CatalogReadOutcome.IdentityUnavailable

        override suspend fun home(query: CatalogHomeQuery): CatalogReadOutcome<CatalogHomeData> {
            homeCalls += 1
            return homeResult(query)
        }

        override suspend fun libraries(
            query: CatalogLibrariesQuery
        ): CatalogReadOutcome<List<FindroidCollection>> {
            libraryCalls += 1
            return libraryResult(query)
        }

        override suspend fun itemsPage(
            query: CatalogItemsPageQuery
        ): CatalogReadOutcome<List<FindroidItem>> {
            pageCalls += 1
            return pageResult(query)
        }

        override suspend fun movie(query: CatalogMovieQuery): CatalogReadOutcome<FindroidMovie> {
            movieCalls += 1
            return movieResult(query)
        }
    }

    private companion object {
        const val SERVER_ID = "server-a"
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val OTHER_USER_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val LIBRARY_ID: UUID = UUID.fromString("55555555-5555-4555-8555-555555555555")
        val ADDRESS_ID: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val NAMESPACE_A = MetadataNamespace(serverId = "server-a", userId = USER_ID.toString())
        val NAMESPACE_B =
            MetadataNamespace(serverId = "server-b", userId = OTHER_USER_ID.toString())
        val ONLINE_SCOPE =
            CatalogScope(
                origin = JellyfinOrigin(SERVER_ID, USER_ID),
                principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
                identityGeneration = 1L,
            )
        val OFFLINE_SCOPE =
            CatalogScope(
                origin = JellyfinOrigin(SERVER_ID, USER_ID),
                principalContext = CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT,
                identityGeneration = 2L,
            )
    }
}
