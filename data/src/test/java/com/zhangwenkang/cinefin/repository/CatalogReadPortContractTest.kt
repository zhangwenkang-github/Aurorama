package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.di.CatalogReadModule
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogReadPortContractTest {
    @Test
    fun `scope snapshot and query expose the frozen catalog contract`() {
        val scope =
            CatalogScope(
                origin = JellyfinOrigin(SERVER_ID, USER_ID),
                principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
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
        val resolver =
            CatalogScopeResolver(
                appPreferences = preferences(SERVER_ID),
                database = database,
                principalContext = CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT,
            )

        val scope = resolver.resolve()

        assertEquals(SERVER_ID, scope?.origin?.serverId)
        assertEquals(USER_ID, scope?.origin?.userId)
        assertEquals(CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT, scope?.principalContext)
    }

    @Test
    fun `scope resolver fails closed without a complete saved identity`() = runBlocking {
        val database = mockk<ServerDatabaseDao>(relaxed = true)
        val missingServer =
            CatalogScopeResolver(
                appPreferences = preferences(null),
                database = database,
                principalContext = CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT,
            )
        val missingUser =
            CatalogScopeResolver(
                appPreferences = preferences(SERVER_ID),
                database = database,
                principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
            )

        assertNull(missingServer.resolve())
        assertNull(missingUser.resolve())
    }

    @Test
    fun `online scope resolver rejects blank token or address`() = runBlocking {
        val database = mockk<ServerDatabaseDao>(relaxed = true)
        val resolver =
            CatalogScopeResolver(
                appPreferences = preferences(SERVER_ID),
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
    fun `catalog module selects the port for both offline preference values`() {
        val online = fakePort()
        val offline = fakePort()

        assertSame(
            online,
            CatalogReadModule.selectCatalogReadPort(false, online, offline),
        )
        assertSame(
            offline,
            CatalogReadModule.selectCatalogReadPort(true, online, offline),
        )
    }

    @Test
    fun `catalog module provider reads offline preference for both branches`() {
        val online = mockk<JellyfinRepositoryImpl>(relaxed = true)
        val offline = mockk<JellyfinRepositoryOfflineImpl>(relaxed = true)

        assertSame(
            online,
            CatalogReadModule.provideCatalogReadPort(
                online = online,
                offline = offline,
                appPreferences = preferencesWithOffline(false),
            ),
        )
        assertSame(
            offline,
            CatalogReadModule.provideCatalogReadPort(
                online = online,
                offline = offline,
                appPreferences = preferencesWithOffline(true),
            ),
        )
    }

    @Test
    fun `offline search binds saved scope and user data without singleton user`() = runBlocking {
        val database =
            mockk<ServerDatabaseDao>(relaxed = true).apply {
                coEvery { getServerCurrentUser(SERVER_ID) } returns user(USER_ID)
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
                appPreferences = preferences(SERVER_ID),
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
            }
        val repository =
            JellyfinRepositoryOfflineImpl(
                context = mockk<Context>(relaxed = true),
                jellyfinApi = mockk<JellyfinApi>(relaxed = true),
                database = database,
                appPreferences = preferences(SERVER_ID),
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
            }
        val repository =
            JellyfinRepositoryOfflineImpl(
                context = mockk<Context>(relaxed = true),
                jellyfinApi = mockk<JellyfinApi>(relaxed = true),
                database = database,
                appPreferences = preferences(SERVER_ID),
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

    private fun fakePort(): CatalogReadPort =
        object : CatalogReadPort {
            override suspend fun currentScope(): CatalogScope? = null

            override suspend fun search(
                query: CatalogSearchQuery
            ): CatalogReadOutcome<List<FindroidItem>> = CatalogReadOutcome.IdentityUnavailable
        }

    private fun preferences(serverId: String?): AppPreferences {
        val preferences = mockk<SharedPreferences>(relaxed = true)
        every { preferences.getString(any(), any()) } returns serverId
        return AppPreferences(preferences)
    }

    private fun preferencesWithOffline(offline: Boolean): AppPreferences {
        val preferences = mockk<SharedPreferences>(relaxed = true)
        every { preferences.getBoolean(any(), any()) } returns offline
        return AppPreferences(preferences)
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
            )
    }
}
