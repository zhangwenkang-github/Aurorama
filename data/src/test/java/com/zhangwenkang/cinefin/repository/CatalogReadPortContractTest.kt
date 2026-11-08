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
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidMovieDto
import com.zhangwenkang.cinefin.models.ServerAddress
import com.zhangwenkang.cinefin.models.User
import com.zhangwenkang.cinefin.repository.catalog.CatalogFreshness
import com.zhangwenkang.cinefin.repository.catalog.CatalogPrincipalContext
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadOutcome
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadPort
import com.zhangwenkang.cinefin.repository.catalog.CatalogScope
import com.zhangwenkang.cinefin.repository.catalog.CatalogScopeResolver
import com.zhangwenkang.cinefin.repository.catalog.CatalogSearchQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogSnapshot
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogReadPortContractTest {
    @Test
    fun `scope snapshot and query expose the frozen catalog contract`() {
        val scope =
            CatalogScope(
                origin = JellyfinOrigin(SERVER_ID, USER_ID),
                principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
                identityGeneration = 3L,
            )
        val snapshot =
            CatalogSnapshot(
                scope = scope,
                contentGeneration = 7L,
                freshness = CatalogFreshness.STALE,
                observedAtEpochMs = 123L,
                value = listOf("sample"),
            )

        assertEquals(JellyfinOrigin(SERVER_ID, USER_ID), scope.origin)
        assertEquals(3L, scope.identityGeneration)
        assertNotEquals(scope, scope.copy(identityGeneration = 2L))
        assertEquals(7L, snapshot.contentGeneration)
        assertEquals(CatalogFreshness.STALE, snapshot.freshness)
        val query =
            CatalogSearchQuery(
                term = "sample",
                scope = SearchScope.VIDEO,
                frozenScope = scope,
            )
        assertEquals(SearchScope.VIDEO, query.scope)
        assertEquals(scope, query.frozenScope)
    }

    @Test
    fun `scope resolver uses saved server current user as canonical identity`() = runBlocking {
        val database = mockk<ServerDatabaseDao>(relaxed = true)
        coEvery { database.getServerCurrentUser(SERVER_ID) } returns user(USER_ID)
        coEvery { database.getServerCurrentAddress(SERVER_ID) } returns address("https://server")
        val resolver =
            CatalogScopeResolver(
                identityReadPort =
                    identityPort(
                        identity(
                            serverId = SERVER_ID,
                            userId = USER_ID,
                            offlineMode = true,
                            generation = 9L,
                        )
                    ),
                database = database,
                principalContext = CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT,
            )

        val scope = resolver.resolve()

        assertEquals(SERVER_ID, scope?.origin?.serverId)
        assertEquals(USER_ID, scope?.origin?.userId)
        assertEquals(CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT, scope?.principalContext)
        assertEquals(9L, scope?.identityGeneration)
    }

    @Test
    fun `scope resolver fails closed without a complete saved identity`() = runBlocking {
        val database = mockk<ServerDatabaseDao>(relaxed = true)
        val recovering =
            CatalogScopeResolver(
                identityReadPort =
                    identityPort(
                        identity(
                                serverId = SERVER_ID,
                                userId = USER_ID,
                                offlineMode = true,
                                generation = 9L,
                            )
                            .copy(status = IdentityStateStatus.RECOVERING)
                    ),
                database = database,
                principalContext = CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT,
            )
        val missingUser =
            CatalogScopeResolver(
                identityReadPort =
                    identityPort(
                        identity(
                            serverId = SERVER_ID,
                            userId = null,
                            offlineMode = true,
                            generation = 9L,
                        )
                    ),
                database = database,
                principalContext = CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT,
            )
        val missingAddress =
            CatalogScopeResolver(
                identityReadPort =
                    identityPort(
                        identity(
                            serverId = SERVER_ID,
                            userId = USER_ID,
                            addressId = null,
                            offlineMode = true,
                            generation = 9L,
                        )
                    ),
                database = database,
                principalContext = CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT,
            )

        assertNull(recovering.resolve())
        assertNull(missingUser.resolve())
        assertNull(missingAddress.resolve())
    }

    @Test
    fun `online scope resolver rejects blank token or address`() = runBlocking {
        val database = mockk<ServerDatabaseDao>(relaxed = true)
        val resolver =
            CatalogScopeResolver(
                identityReadPort =
                    identityPort(
                        identity(
                            serverId = SERVER_ID,
                            userId = USER_ID,
                            offlineMode = false,
                            generation = 9L,
                        )
                    ),
                database = database,
                principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
            )
        coEvery { database.getServerCurrentUser(SERVER_ID) } returns
            user(USER_ID, accessToken = " \t ")
        coEvery { database.getServerCurrentAddress(SERVER_ID) } returns address("https://server")

        assertNull(resolver.resolve())

        coEvery { database.getServerCurrentUser(SERVER_ID) } returns user(USER_ID)
        coEvery { database.getServerCurrentAddress(SERVER_ID) } returns address(" \t ")

        assertNull(resolver.resolve())
    }

    @Test
    fun `catalog module dynamically routes every operation and rejects stale search results`() =
        runBlocking {
            val onlineScope =
                CatalogScope(
                    origin = JellyfinOrigin(SERVER_ID, USER_ID),
                    principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
                    identityGeneration = 1L,
                )
            val offlineScope =
                CatalogScope(
                    origin = JellyfinOrigin(SERVER_ID, USER_ID),
                    principalContext = CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT,
                    identityGeneration = 2L,
                )
            var snapshot =
                identity(
                    serverId = SERVER_ID,
                    userId = USER_ID,
                    offlineMode = false,
                    generation = 1L,
                )
            var onlineSearches = 0
            val online =
                fakePort(currentScope = { onlineScope }) {
                    onlineSearches += 1
                    available(onlineScope)
                }
            val offline =
                fakePort(currentScope = { offlineScope }) {
                    snapshot =
                        identity(
                            serverId = SERVER_ID,
                            userId = USER_ID,
                            offlineMode = true,
                            generation = 3L,
                        )
                    available(offlineScope)
                }
            val port =
                CatalogReadModule.dynamicCatalogReadPort(
                    online = online,
                    offline = offline,
                    identityReadPort = identityPort { snapshot },
                )

            assertEquals(onlineScope, port.currentScope())
            assertTrue(port.search(searchQuery(onlineScope)) is CatalogReadOutcome.Available)
            assertEquals(1, onlineSearches)

            snapshot =
                identity(
                    serverId = SERVER_ID,
                    userId = USER_ID,
                    offlineMode = true,
                    generation = 2L,
                )
            assertEquals(offlineScope, port.currentScope())
            assertTrue(
                port.search(searchQuery(onlineScope)) is CatalogReadOutcome.IdentityUnavailable
            )
            assertTrue(
                port.search(searchQuery(offlineScope)) is CatalogReadOutcome.IdentityUnavailable
            )
        }

    @Test
    fun `catalog module fails closed when canonical identity is unavailable`() = runBlocking {
        val port =
            CatalogReadModule.dynamicCatalogReadPort(
                online = fakePort(currentScope = { null }),
                offline = fakePort(currentScope = { null }),
                identityReadPort =
                    identityPort(
                        identity(
                                serverId = SERVER_ID,
                                userId = USER_ID,
                                offlineMode = false,
                                generation = 1L,
                            )
                            .copy(status = IdentityStateStatus.RECOVERING)
                    ),
            )

        assertNull(port.currentScope())
        assertTrue(
            port.search(searchQuery(OFFLINE_SCOPE)) is CatalogReadOutcome.IdentityUnavailable
        )
    }

    @Test
    fun `offline search binds saved scope and user data without singleton user`() = runBlocking {
        val database =
            mockk<ServerDatabaseDao>(relaxed = true).apply {
                coEvery { getServerCurrentUser(SERVER_ID) } returns user(USER_ID)
                coEvery { getServerCurrentAddress(SERVER_ID) } returns address("https://server")
                coEvery { searchMovies(SERVER_ID, "sample") } returns
                    listOf(movieDto(name = "sample"))
                coEvery { searchShows(SERVER_ID, "sample") } returns emptyList()
                coEvery { searchEpisodes(SERVER_ID, "sample") } returns emptyList()
            }
        val api =
            mockk<JellyfinApi>(relaxed = true).apply { every { userId } returns OTHER_USER_ID }
        var mappedUserId: UUID? = null
        val repository =
            JellyfinRepositoryOfflineImpl(
                context = mockk<Context>(relaxed = true),
                jellyfinApi = api,
                database = database,
                appPreferences = preferences(SERVER_ID, offline = true),
                localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
                offlineMovieMapper = { dto, userId ->
                    mappedUserId = userId
                    movie(dto)
                },
                offlineShowMapper = { _, _ -> error("show mapper must not run") },
                offlineEpisodeMapper = { _, _ -> error("episode mapper must not run") },
            )

        val outcome =
            repository.search(
                CatalogSearchQuery(
                    term = "sample",
                    scope = SearchScope.VIDEO,
                    frozenScope = OFFLINE_SCOPE,
                )
            ) as CatalogReadOutcome.Available
        val result = outcome.snapshot.value.single()

        assertEquals(USER_ID, outcome.snapshot.scope.origin.userId)
        assertEquals(
            CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT,
            outcome.snapshot.scope.principalContext,
        )
        assertEquals(CatalogFreshness.LOCAL_QUERY, outcome.snapshot.freshness)
        assertEquals(USER_ID, mappedUserId)
        assertTrue(result.played)
        assertTrue(result.favorite)
        assertEquals(42L, result.playbackPositionTicks)
        coVerify(exactly = 1) { database.searchMovies(SERVER_ID, "sample") }
    }

    @Test
    fun `offline music and book scopes do not borrow video rows`() = runBlocking {
        val database =
            mockk<ServerDatabaseDao>(relaxed = true).apply {
                coEvery { getServerCurrentUser(SERVER_ID) } returns user(USER_ID)
                coEvery { getServerCurrentAddress(SERVER_ID) } returns address("https://server")
            }
        val repository =
            JellyfinRepositoryOfflineImpl(
                context = mockk<Context>(relaxed = true),
                jellyfinApi = mockk<JellyfinApi>(relaxed = true),
                database = database,
                appPreferences = preferences(SERVER_ID, offline = true),
                localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
            )

        val music =
            repository.search(
                CatalogSearchQuery(
                    term = "sample",
                    scope = SearchScope.MUSIC,
                    frozenScope = OFFLINE_SCOPE,
                )
            ) as CatalogReadOutcome.Available
        val book =
            repository.search(
                CatalogSearchQuery(
                    term = "sample",
                    scope = SearchScope.BOOK,
                    frozenScope = OFFLINE_SCOPE,
                )
            ) as CatalogReadOutcome.Available

        assertTrue(music.snapshot.value.isEmpty())
        assertTrue(book.snapshot.value.isEmpty())
        coVerify(exactly = 0) { database.searchMovies(any(), any()) }
    }

    @Test
    fun `offline search without identity returns identity unavailable`() = runBlocking {
        val repository =
            JellyfinRepositoryOfflineImpl(
                context = mockk<Context>(relaxed = true),
                jellyfinApi = mockk<JellyfinApi>(relaxed = true),
                database = mockk<ServerDatabaseDao>(relaxed = true),
                appPreferences = preferences(null),
                localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
            )

        val outcome =
            repository.search(
                CatalogSearchQuery(
                    term = "sample",
                    scope = SearchScope.VIDEO,
                    frozenScope = OFFLINE_SCOPE,
                )
            )

        assertTrue(outcome is CatalogReadOutcome.IdentityUnavailable)
    }

    @Test
    fun `offline search rejects a frozen scope that is no longer current`() = runBlocking {
        val database =
            mockk<ServerDatabaseDao>(relaxed = true).apply {
                coEvery { getServerCurrentUser(SERVER_ID) } returns user(OTHER_USER_ID)
                coEvery { getServerCurrentAddress(SERVER_ID) } returns address("https://server")
            }
        val repository =
            JellyfinRepositoryOfflineImpl(
                context = mockk<Context>(relaxed = true),
                jellyfinApi = mockk<JellyfinApi>(relaxed = true),
                database = database,
                appPreferences = preferences(SERVER_ID, offline = true),
                localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
            )

        val outcome =
            repository.search(
                CatalogSearchQuery(
                    term = "sample",
                    scope = SearchScope.VIDEO,
                    frozenScope = OFFLINE_SCOPE,
                )
            )

        assertTrue(outcome is CatalogReadOutcome.IdentityUnavailable)
        coVerify(exactly = 0) { database.searchMovies(any(), any()) }
        coVerify(exactly = 0) { database.searchShows(any(), any()) }
        coVerify(exactly = 0) { database.searchEpisodes(any(), any()) }
    }

    private fun fakePort(
        currentScope: suspend () -> CatalogScope?,
        onSearch: suspend (CatalogSearchQuery) -> CatalogReadOutcome<List<FindroidItem>> = {
            CatalogReadOutcome.IdentityUnavailable
        },
    ): CatalogReadPort =
        object : CatalogReadPort {
            override suspend fun currentScope(): CatalogScope? = currentScope()

            override suspend fun search(
                query: CatalogSearchQuery
            ): CatalogReadOutcome<List<FindroidItem>> = onSearch(query)
        }

    private fun identityPort(snapshot: IdentitySnapshot): IdentityReadPort =
        object : IdentityReadPort {
            override suspend fun hydrate(): IdentitySnapshot = snapshot

            override suspend fun current(): IdentitySnapshot = snapshot
        }

    private fun identityPort(snapshot: () -> IdentitySnapshot): IdentityReadPort =
        object : IdentityReadPort {
            override suspend fun hydrate(): IdentitySnapshot = snapshot()

            override suspend fun current(): IdentitySnapshot = snapshot()
        }

    private fun identity(
        serverId: String?,
        userId: UUID?,
        addressId: String? = ADDRESS_ID.toString(),
        offlineMode: Boolean,
        generation: Long,
    ): IdentitySnapshot =
        IdentitySnapshot(
            selection =
                IdentitySelection(
                    serverId = serverId,
                    userId = userId?.toString(),
                    addressId = addressId,
                    offlineMode = offlineMode,
                ),
            identityGeneration = generation,
            status = IdentityStateStatus.READY,
        )

    private fun searchQuery(scope: CatalogScope): CatalogSearchQuery =
        CatalogSearchQuery(
            term = "sample",
            scope = SearchScope.VIDEO,
            frozenScope = scope,
        )

    private fun available(scope: CatalogScope): CatalogReadOutcome<List<FindroidItem>> =
        CatalogReadOutcome.Available(
            CatalogSnapshot(
                scope = scope,
                contentGeneration = 1L,
                freshness = CatalogFreshness.FRESH,
                observedAtEpochMs = 1L,
                value = emptyList(),
            )
        )

    private fun preferences(
        serverId: String?,
        userId: UUID = USER_ID,
        offline: Boolean = false,
        generation: Long = 1L,
    ): AppPreferences {
        val preferences = mockk<SharedPreferences>(relaxed = true)
        val appPreferences = AppPreferences(preferences)
        val encodedIdentity =
            IdentityStateCodec.encode(
                if (serverId == null) {
                    IdentitySnapshot(
                        selection = IdentitySelection(),
                        identityGeneration = 0L,
                        status = IdentityStateStatus.READY,
                    )
                } else {
                    identity(
                        serverId = serverId,
                        userId = userId,
                        offlineMode = offline,
                        generation = generation,
                    )
                }
            )
        every { preferences.getString(any(), any()) } answers
            {
                if (firstArg<String>() == IDENTITY_STATE_PREFERENCE_KEY) encodedIdentity
                else serverId
            }
        every { preferences.getBoolean(any(), any()) } answers
            {
                if (firstArg<String>() == appPreferences.offlineMode.backendName) offline
                else secondArg()
            }
        return appPreferences
    }

    private fun user(
        id: UUID,
        accessToken: String? = "token",
    ): User =
        User(
            id = id,
            name = "user",
            serverId = SERVER_ID,
            accessToken = accessToken,
        )

    private fun address(value: String): ServerAddress =
        ServerAddress(
            id = ADDRESS_ID,
            serverId = SERVER_ID,
            address = value,
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

    private fun movie(dto: FindroidMovieDto): FindroidMovie =
        FindroidMovie(
            id = dto.id,
            name = dto.name,
            originalTitle = dto.originalTitle,
            overview = dto.overview,
            sources = emptyList(),
            played = true,
            favorite = true,
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

    private companion object {
        const val SERVER_ID = "server-a"
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val OTHER_USER_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val ADDRESS_ID: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val OFFLINE_SCOPE =
            CatalogScope(
                origin = JellyfinOrigin(SERVER_ID, USER_ID),
                principalContext = CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT,
                identityGeneration = 1L,
            )
    }
}
