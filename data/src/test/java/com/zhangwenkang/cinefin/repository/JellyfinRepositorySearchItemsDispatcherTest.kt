package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.ServerAddress
import com.zhangwenkang.cinefin.models.User
import com.zhangwenkang.cinefin.repository.catalog.CatalogFreshness
import com.zhangwenkang.cinefin.repository.catalog.CatalogPrincipalContext
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadOutcome
import com.zhangwenkang.cinefin.repository.catalog.CatalogScope
import com.zhangwenkang.cinefin.repository.catalog.CatalogSearchQuery
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinRepositorySearchItemsDispatcherTest {
    @Test
    fun `online search uses canonical db user and bound origin client off caller thread`() =
        runBlocking {
            val directory = Files.createTempDirectory("search-items-origin")
            val executor = Executors.newSingleThreadExecutor()
            val dispatcher = executor.asCoroutineDispatcher()
            try {
                val callerThread = Thread.currentThread()
                lateinit var fetchThread: Thread
                lateinit var materializeThread: Thread
                lateinit var cacheReadThread: Thread
                var fetchedUserId: UUID? = null
                var fetchCalls = 0
                val boundApi =
                    mockk<JellyfinApi>(relaxed = true).apply {
                        every { userId } returns USER_ID
                        every { api } returns mockk<ApiClient>(relaxed = true)
                    }
                val database = database(currentUser = user(USER_ID))
                val repository =
                    JellyfinRepositoryImpl(
                        context = mockk<Context>(relaxed = true),
                        jellyfinApi =
                            mockk<JellyfinApi>(relaxed = true).apply {
                                every { userId } returns USER_ID
                            },
                        database = database,
                        appPreferences = preferences(),
                        localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
                        metadataSnapshotStore = MetadataSnapshotStore(directory.toFile()),
                        searchItemsIoDispatcher = dispatcher,
                        searchItemsFetch = { api, _, _ ->
                            fetchCalls += 1
                            fetchThread = Thread.currentThread()
                            fetchedUserId = api.userId
                            listOf(
                                BaseItemDto(
                                    id = ITEM_ID,
                                    name = "sample",
                                    type = BaseItemKind.MOVIE,
                                )
                            )
                        },
                        searchItemsMaterialize = { items, materialize ->
                            materializeThread = Thread.currentThread()
                            materialize(items)
                        },
                        searchItemsCacheRead = { read ->
                            cacheReadThread = Thread.currentThread()
                            read()
                        },
                        originApiFactory = { boundApi },
                    )

                val first =
                    repository.search(
                        CatalogSearchQuery(
                            term = "sample",
                            scope = SearchScope.VIDEO,
                            frozenScope = ONLINE_SCOPE,
                        )
                    ) as CatalogReadOutcome.Available
                val second =
                    repository.search(
                        CatalogSearchQuery(
                            term = "sample",
                            scope = SearchScope.VIDEO,
                            frozenScope = ONLINE_SCOPE,
                        )
                    ) as CatalogReadOutcome.Available

                assertEquals(USER_ID, fetchedUserId)
                assertEquals(1, fetchCalls)
                assertEquals(USER_ID, first.snapshot.scope.origin.userId)
                assertEquals(
                    CatalogPrincipalContext.ONLINE_ACCOUNT,
                    first.snapshot.scope.principalContext,
                )
                assertEquals(CatalogFreshness.FRESH, first.snapshot.freshness)
                assertEquals(USER_ID, second.snapshot.scope.origin.userId)
                assertEquals(listOf("sample"), second.snapshot.value.map(FindroidItem::name))
                assertNotEquals(callerThread, fetchThread)
                assertNotEquals(callerThread, materializeThread)
                assertNotEquals(callerThread, cacheReadThread)
            } finally {
                dispatcher.close()
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun `online search fails closed when mutable singleton user differs from canonical db user`() =
        runBlocking {
            val directory = Files.createTempDirectory("search-items-scope-mismatch")
            try {
                var fetchCalls = 0
                val repository =
                    JellyfinRepositoryImpl(
                        context = mockk<Context>(relaxed = true),
                        jellyfinApi =
                            mockk<JellyfinApi>(relaxed = true).apply {
                                every { userId } returns OTHER_USER_ID
                            },
                        database = database(currentUser = user(USER_ID)),
                        appPreferences = preferences(),
                        localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
                        metadataSnapshotStore = MetadataSnapshotStore(directory.toFile()),
                        searchItemsIoDispatcher = Dispatchers.IO,
                        searchItemsFetch = { _, _, _ ->
                            fetchCalls += 1
                            emptyList()
                        },
                        searchItemsMaterialize = { items, materialize -> materialize(items) },
                        searchItemsCacheRead = { read -> read() },
                    )

                val outcome =
                    repository.search(
                        CatalogSearchQuery(
                            term = "sample",
                            scope = SearchScope.VIDEO,
                            frozenScope = ONLINE_SCOPE,
                        )
                    )

                assertTrue(outcome is CatalogReadOutcome.IdentityUnavailable)
                assertEquals(0, fetchCalls)
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun `online search cache isolates scope server and user`() = runBlocking {
        val directory = Files.createTempDirectory("search-items-scope-isolation")
        try {
            var currentServer = SERVER_ID
            var currentUserId = USER_ID
            var singletonUserId = USER_ID
            var fetchCalls = 0
            val preferences = mockk<SharedPreferences>(relaxed = true)
            every { preferences.getString(any(), any()) } answers { currentServer }
            val database =
                mockk<ServerDatabaseDao>(relaxed = true).apply {
                    coEvery { getServerCurrentUser(any()) } answers
                        {
                            user(currentUserId, currentServer)
                        }
                    coEvery { getUser(any()) } answers { user(firstArg(), currentServer) }
                    coEvery { getServerCurrentAddress(any()) } answers
                        {
                            ServerAddress(
                                id = ADDRESS_ID,
                                serverId = firstArg(),
                                address = "https://server.example/",
                            )
                        }
                    coEvery { getSources(any()) } returns emptyList()
                }
            val boundApi =
                mockk<JellyfinApi>(relaxed = true).apply {
                    every { userId } answers { singletonUserId }
                    every { api } returns mockk<ApiClient>(relaxed = true)
                }
            val repository =
                JellyfinRepositoryImpl(
                    context = mockk<Context>(relaxed = true),
                    jellyfinApi =
                        mockk<JellyfinApi>(relaxed = true).apply {
                            every { userId } answers { singletonUserId }
                        },
                    database = database,
                    appPreferences = AppPreferences(preferences),
                    localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
                    metadataSnapshotStore = MetadataSnapshotStore(directory.toFile()),
                    searchItemsIoDispatcher = Dispatchers.IO,
                    searchItemsFetch = { _, _, _ ->
                        fetchCalls += 1
                        emptyList()
                    },
                    searchItemsMaterialize = { items, materialize -> materialize(items) },
                    searchItemsCacheRead = { read -> read() },
                    originApiFactory = { boundApi },
                )

            val video =
                repository.search(
                    CatalogSearchQuery(
                        term = "same",
                        scope = SearchScope.VIDEO,
                        frozenScope = catalogScope(USER_ID, SERVER_ID),
                    )
                ) as CatalogReadOutcome.Available
            val music =
                repository.search(
                    CatalogSearchQuery(
                        term = "same",
                        scope = SearchScope.MUSIC,
                        frozenScope = catalogScope(USER_ID, SERVER_ID),
                    )
                ) as CatalogReadOutcome.Available
            currentUserId = OTHER_USER_ID
            singletonUserId = OTHER_USER_ID
            val otherUser =
                repository.search(
                    CatalogSearchQuery(
                        term = "same",
                        scope = SearchScope.VIDEO,
                        frozenScope = catalogScope(OTHER_USER_ID, SERVER_ID),
                    )
                ) as CatalogReadOutcome.Available
            currentServer = "server-b"
            val otherServer =
                repository.search(
                    CatalogSearchQuery(
                        term = "same",
                        scope = SearchScope.VIDEO,
                        frozenScope = catalogScope(OTHER_USER_ID, currentServer),
                    )
                ) as CatalogReadOutcome.Available

            assertEquals(4, fetchCalls)
            assertEquals(USER_ID, video.snapshot.scope.origin.userId)
            assertEquals(OTHER_USER_ID, otherUser.snapshot.scope.origin.userId)
            assertEquals("server-b", otherServer.snapshot.scope.origin.serverId)
            assertEquals(USER_ID, music.snapshot.scope.origin.userId)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `online search fences a scope switch during fetch without cache writes`() = runBlocking {
        val directory = Files.createTempDirectory("search-items-midflight-switch")
        try {
            var currentUserId = USER_ID
            var fetchedUserId: UUID? = null
            var fetchCalls = 0
            val boundApi =
                mockk<JellyfinApi>(relaxed = true).apply {
                    every { userId } returns USER_ID
                    every { api } returns mockk<ApiClient>(relaxed = true)
                }
            val database =
                mockk<ServerDatabaseDao>(relaxed = true).apply {
                    coEvery { getServerCurrentUser(SERVER_ID) } answers { user(currentUserId) }
                    coEvery { getUser(USER_ID) } returns user(USER_ID)
                    coEvery { getServerCurrentAddress(SERVER_ID) } returns
                        ServerAddress(
                            id = ADDRESS_ID,
                            serverId = SERVER_ID,
                            address = "https://server.example/",
                        )
                    coEvery { getSources(any()) } returns emptyList()
                }
            val repository =
                JellyfinRepositoryImpl(
                    context = mockk<Context>(relaxed = true),
                    jellyfinApi =
                        mockk<JellyfinApi>(relaxed = true).apply {
                            every { userId } returns USER_ID
                        },
                    database = database,
                    appPreferences = preferences(),
                    localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
                    metadataSnapshotStore = MetadataSnapshotStore(directory.toFile()),
                    searchItemsIoDispatcher = Dispatchers.IO,
                    searchItemsFetch = { api, _, _ ->
                        fetchCalls += 1
                        fetchedUserId = api.userId
                        currentUserId = OTHER_USER_ID
                        listOf(
                            BaseItemDto(
                                id = ITEM_ID,
                                name = "sample",
                                type = BaseItemKind.MOVIE,
                            )
                        )
                    },
                    searchItemsMaterialize = { items, materialize -> materialize(items) },
                    searchItemsCacheRead = { read -> read() },
                    originApiFactory = { boundApi },
                )

            val outcome =
                repository.search(
                    CatalogSearchQuery(
                        term = "sample",
                        scope = SearchScope.VIDEO,
                        frozenScope = ONLINE_SCOPE,
                    )
                )

            assertTrue(outcome is CatalogReadOutcome.IdentityUnavailable)
            assertEquals(USER_ID, fetchedUserId)
            assertEquals(1, fetchCalls)
            assertTrue(directory.toFile().listFiles().orEmpty().isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `online search rejects blank token or address before fetch and cache writes`() =
        runBlocking {
            val cases =
                listOf(
                    CredentialCase(
                        label = "blank token",
                        currentUser = user(USER_ID, accessToken = " \t "),
                        address = "https://server.example/",
                    ),
                    CredentialCase(
                        label = "blank address",
                        currentUser = user(USER_ID),
                        address = " \t ",
                    ),
                )
            for (case in cases) {
                val directory = Files.createTempDirectory("search-items-blank-credentials")
                try {
                    var fetchCalls = 0
                    val repository =
                        JellyfinRepositoryImpl(
                            context = mockk<Context>(relaxed = true),
                            jellyfinApi = mockk<JellyfinApi>(relaxed = true),
                            database = database(case.currentUser, case.address),
                            appPreferences = preferences(),
                            localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
                            metadataSnapshotStore = MetadataSnapshotStore(directory.toFile()),
                            searchItemsIoDispatcher = Dispatchers.IO,
                            searchItemsFetch = { _, _, _ ->
                                fetchCalls += 1
                                emptyList()
                            },
                            searchItemsMaterialize = { items, materialize -> materialize(items) },
                            searchItemsCacheRead = { read -> read() },
                        )

                    val outcome =
                        repository.search(
                            CatalogSearchQuery(
                                term = "sample",
                                scope = SearchScope.VIDEO,
                                frozenScope = ONLINE_SCOPE,
                            )
                        )

                    assertTrue(case.label, outcome is CatalogReadOutcome.IdentityUnavailable)
                    assertEquals(case.label, 0, fetchCalls)
                    assertTrue(
                        case.label,
                        directory.toFile().listFiles().orEmpty().isEmpty(),
                    )
                } finally {
                    directory.toFile().deleteRecursively()
                }
            }
        }

    private fun database(
        currentUser: User,
        address: String = "https://server.example/",
    ): ServerDatabaseDao =
        mockk<ServerDatabaseDao>(relaxed = true).apply {
            coEvery { getServerCurrentUser(SERVER_ID) } returns currentUser
            coEvery { getUser(USER_ID) } returns user(USER_ID)
            coEvery { getServerCurrentAddress(SERVER_ID) } returns
                ServerAddress(
                    id = ADDRESS_ID,
                    serverId = SERVER_ID,
                    address = address,
                )
            coEvery { getSources(any()) } returns emptyList()
        }

    private fun user(
        id: UUID,
        accessToken: String? = "token",
    ): User = user(id, SERVER_ID, accessToken)

    private fun user(
        id: UUID,
        serverId: String,
        accessToken: String? = "token",
    ): User =
        User(
            id = id,
            name = "user",
            serverId = serverId,
            accessToken = accessToken,
        )

    private fun catalogScope(
        userId: UUID,
        serverId: String,
    ): CatalogScope =
        CatalogScope(
            origin = JellyfinOrigin(serverId, userId),
            principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
        )

    private fun preferences(): AppPreferences {
        val preferences = mockk<SharedPreferences>(relaxed = true)
        every { preferences.getString(any(), any()) } returns SERVER_ID
        every { preferences.getInt(any(), any()) } answers { secondArg() }
        return AppPreferences(preferences)
    }

    private companion object {
        const val SERVER_ID = "server-a"
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val OTHER_USER_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val ADDRESS_ID: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val ONLINE_SCOPE =
            CatalogScope(
                origin = JellyfinOrigin(SERVER_ID, USER_ID),
                principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
            )
    }

    private data class CredentialCase(
        val label: String,
        val currentUser: User,
        val address: String,
    )
}
