package com.zhangwenkang.cinefin.viewmodels

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.IdentityStartupProjector
import com.zhangwenkang.cinefin.StartupIdentityPublisher
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.di.IdentityHydrationGate
import com.zhangwenkang.cinefin.identity.IdentityReadPort
import com.zhangwenkang.cinefin.identity.IdentitySelection
import com.zhangwenkang.cinefin.identity.IdentitySnapshot
import com.zhangwenkang.cinefin.identity.IdentityStateStatus
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.models.Server
import com.zhangwenkang.cinefin.models.ServerAddress
import com.zhangwenkang.cinefin.models.ServerWithAddressAndUser
import com.zhangwenkang.cinefin.models.User
import com.zhangwenkang.cinefin.presentation.navigation.DrawerViewModel
import com.zhangwenkang.cinefin.repository.JellyfinOrigin
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.catalog.CatalogPrincipalContext
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadOutcome
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadPort
import com.zhangwenkang.cinefin.repository.catalog.CatalogScope
import com.zhangwenkang.cinefin.repository.catalog.CatalogSearchQuery
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelIdentityTest {
    private val dispatcher = StandardTestDispatcher()
    private val serverId = "server-a"
    private val addressId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun shellFlagsAndServerProjectionUseHydratedCanonicalSnapshotAndCurrentScope() =
        runTest(dispatcher) {
            val preferences = IdentityUiTestSharedPreferences()
            preferences.putString("pref_current_server", "stale-server")
            val database = database(relation = relation(), serverCount = 1)
            val scope = readyScope()
            val viewModel =
                MainViewModel(
                    AppPreferences(preferences),
                    database,
                    FixedCatalogReadPort(scope),
                )

            advanceUntilIdle()

            assertEquals(
                MainState(
                    isLoading = false,
                    isDynamicColors = false,
                    hasServers = true,
                    hasCurrentServer = true,
                    hasCurrentUser = true,
                    isOfflineMode = false,
                ),
                viewModel.state.value,
            )
            assertFalse("pref_current_server" in preferences.requestedKeys)
        }

    @Test
    fun noCanonicalIdentityClearsOldApiTokenAndShellFlags() =
        runTest(dispatcher) {
            val preferences = IdentityUiTestSharedPreferences()
            preferences.putString("pref_current_server", "stale-server")
            val database = database(serverCount = 2)
            val reader =
                MutableIdentityReadPort(
                    IdentitySnapshot(
                        selection =
                            IdentitySelection(
                                serverId = "stale-server",
                                userId = userId.toString(),
                                addressId = addressId.toString(),
                            ),
                        identityGeneration = 19L,
                        status = IdentityStateStatus.RECOVERING,
                    )
                )
            val publisher =
                RecordingStartupIdentityPublisher(
                    PublishedIdentity(
                        baseUrl = "https://stale.example",
                        accessToken = "stale-token",
                        userId = userId,
                    )
                )
            val projector = projector(reader, database, publisher)
            val viewModel =
                MainViewModel(
                    AppPreferences(preferences),
                    database,
                    FixedCatalogReadPort(null),
                )

            advanceUntilIdle()
            projector.hydrate()

            assertFalse(viewModel.state.value.hasCurrentServer)
            assertFalse(viewModel.state.value.hasCurrentUser)
            assertFalse(viewModel.state.value.isOfflineMode)
            assertNull(publisher.published)
            assertFalse("pref_current_server" in preferences.requestedKeys)
        }

    @Test
    fun readyCanonicalPublishesOnlyDatabaseValidatedIdentity() =
        runTest(dispatcher) {
            val relation = relation()
            val reader = MutableIdentityReadPort(readySnapshot())
            val publisher = RecordingStartupIdentityPublisher()

            val hydrated = projector(reader, database(relation = relation), publisher).hydrate()

            assertEquals(readySnapshot(), hydrated)
            assertEquals(
                PublishedIdentity(
                    baseUrl = relation.address?.address,
                    accessToken = relation.user?.accessToken,
                    userId = relation.user?.id,
                ),
                publisher.published,
            )
        }

    @Test
    fun partialCanonicalSelectionFailsClosedAndClearsOldApiToken() =
        runTest(dispatcher) {
            val reader =
                MutableIdentityReadPort(
                    IdentitySnapshot(
                        selection = IdentitySelection(serverId = serverId),
                        identityGeneration = 21L,
                        status = IdentityStateStatus.READY,
                    )
                )
            val publisher =
                RecordingStartupIdentityPublisher(
                    PublishedIdentity(
                        baseUrl = "https://stale.example",
                        accessToken = "stale-token",
                        userId = userId,
                    )
                )

            val hydrated = projector(reader, database(), publisher).hydrate()

            assertEquals(IdentitySelection(), hydrated.selection)
            assertEquals(IdentityStateStatus.RECOVERING, hydrated.status)
            assertNull(publisher.published)
        }

    @Test
    fun danglingCanonicalReferencesFailClosedAndClearOldApiToken() =
        runTest(dispatcher) {
            val reader = MutableIdentityReadPort(readySnapshot())
            val publisher =
                RecordingStartupIdentityPublisher(
                    PublishedIdentity(
                        baseUrl = "https://stale.example",
                        accessToken = "stale-token",
                        userId = userId,
                    )
                )

            val hydrated = projector(reader, database(relation = null), publisher).hydrate()

            assertEquals(IdentitySelection(), hydrated.selection)
            assertEquals(IdentityStateStatus.RECOVERING, hydrated.status)
            assertNull(publisher.published)
        }

    @Test
    fun drawerClearsOldServerAndUserWhenCanonicalScopeDisappears() =
        runTest(dispatcher) {
            val preferences = IdentityUiTestSharedPreferences()
            preferences.putString("pref_current_server", "stale-server")
            val relation = relation()
            val database = database(relation = relation)
            val reader = MutableIdentityReadPort(readySnapshot())
            val gate = IdentityHydrationGate(reader)
            val catalog = MutableCatalogReadPort(readyScope())
            val localLibraryRepository =
                interfaceProxy<LocalLibraryRepository> { method, _ ->
                    when (method.name) {
                        "libraries" -> emptyList<Any>()
                        "toString" -> "LocalLibraryRepositoryTestDouble"
                        "hashCode" -> 0
                        "equals" -> false
                        else -> null
                    }
                }
            val repository =
                interfaceProxy<JellyfinRepository> { method, _ ->
                    when (method.name) {
                        "isCurrentUserAdministrator" -> false
                        "getLibraries" -> emptyList<Any>()
                        "toString" -> "JellyfinRepositoryTestDouble"
                        "hashCode" -> 0
                        "equals" -> false
                        else -> null
                    }
                }
            val viewModel =
                DrawerViewModel(
                    database,
                    AppPreferences(preferences),
                    localLibraryRepository,
                    Provider { repository },
                    gate,
                    catalog,
                )

            advanceUntilIdle()
            viewModel.load()
            advanceUntilIdle()
            assertEquals("Server A", viewModel.state.value.serverName)
            assertEquals("User A", viewModel.state.value.userName)

            reader.snapshot =
                IdentitySnapshot(
                    selection = IdentitySelection(),
                    identityGeneration = 20L,
                    status = IdentityStateStatus.RECOVERING,
                )
            catalog.scope = null
            viewModel.load()
            advanceUntilIdle()

            assertNull(viewModel.state.value.serverName)
            assertNull(viewModel.state.value.serverAddress)
            assertNull(viewModel.state.value.userName)
            assertFalse(viewModel.state.value.isAdministrator)
            assertTrue(viewModel.state.value.libraries.isEmpty())
            assertFalse("pref_current_server" in preferences.requestedKeys)
        }

    private fun readySnapshot(): IdentitySnapshot =
        IdentitySnapshot(
            selection =
                IdentitySelection(
                    serverId = serverId,
                    userId = userId.toString(),
                    addressId = addressId.toString(),
                ),
            identityGeneration = 7L,
            status = IdentityStateStatus.READY,
        )

    private fun readyScope(): CatalogScope =
        CatalogScope(
            origin = JellyfinOrigin(serverId = serverId, userId = userId),
            principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
            identityGeneration = 7L,
        )

    private fun relation(): ServerWithAddressAndUser =
        ServerWithAddressAndUser(
            server =
                Server(
                    id = serverId,
                    name = "Server A",
                    currentServerAddressId = addressId,
                    currentUserId = userId,
                ),
            address =
                ServerAddress(
                    id = addressId,
                    serverId = serverId,
                    address = "https://server-a.example",
                ),
            user =
                User(
                    id = userId,
                    name = "User A",
                    serverId = serverId,
                    accessToken = "canonical-token",
                ),
        )

    private fun projector(
        reader: IdentityReadPort,
        database: ServerDatabaseDao,
        publisher: StartupIdentityPublisher,
    ): IdentityStartupProjector =
        IdentityStartupProjector(IdentityHydrationGate(reader), reader, database, publisher)

    private fun database(
        relation: ServerWithAddressAndUser? = null,
        serverCount: Int = 0,
    ): ServerDatabaseDao = interfaceProxy { method, args ->
        val serverId = args?.firstOrNull() as? String
        when (method.name) {
            "getServersCount" -> serverCount
            "getServerWithAddressAndUser" -> relation?.takeIf { it.server.id == serverId }
            "getServer" -> relation?.server?.takeIf { it.id == serverId }
            "getServerCurrentAddress" -> relation?.address?.takeIf { it.serverId == serverId }
            "getServerCurrentUser" -> relation?.user?.takeIf { it.serverId == serverId }
            "toString" -> "ServerDatabaseDaoTestDouble"
            "hashCode" -> 0
            "equals" -> false
            else -> null
        }
    }
}

private class MutableIdentityReadPort(var snapshot: IdentitySnapshot) : IdentityReadPort {
    override suspend fun hydrate(): IdentitySnapshot = snapshot

    override suspend fun current(): IdentitySnapshot = snapshot
}

private class FixedCatalogReadPort(private val scope: CatalogScope?) : CatalogReadPort {
    override suspend fun currentScope(): CatalogScope? = scope

    override suspend fun search(query: CatalogSearchQuery): CatalogReadOutcome<List<Nothing>> =
        CatalogReadOutcome.IdentityUnavailable
}

private class MutableCatalogReadPort(var scope: CatalogScope?) : CatalogReadPort {
    override suspend fun currentScope(): CatalogScope? = scope

    override suspend fun search(query: CatalogSearchQuery): CatalogReadOutcome<List<Nothing>> =
        CatalogReadOutcome.IdentityUnavailable
}

private data class PublishedIdentity(
    val baseUrl: String?,
    val accessToken: String?,
    val userId: UUID?,
)

private class RecordingStartupIdentityPublisher(initial: PublishedIdentity? = null) :
    StartupIdentityPublisher {
    var published: PublishedIdentity? = initial
        private set

    override fun publish(baseUrl: String?, accessToken: String?, userId: UUID?) {
        published =
            if (baseUrl == null && accessToken == null && userId == null) {
                null
            } else {
                PublishedIdentity(baseUrl, accessToken, userId)
            }
    }
}

private inline fun <reified T> interfaceProxy(
    noinline handler: (Method, Array<out Any?>?) -> Any?
): T =
    Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
        InvocationHandler { _, method, args -> handler(method, args) },
    ) as T

private class IdentityUiTestSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    private val listeners =
        CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    val requestedKeys = mutableListOf<String>()

    fun putString(key: String, value: String?) {
        values[key] = value
    }

    override fun getAll(): MutableMap<String, *> = Collections.unmodifiableMap(values.toMap())

    override fun getString(key: String, defValue: String?): String? {
        requestedKeys += key
        return values[key] as String? ?: defValue
    }

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    }

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) listeners -= listener
    }

    private inner class Editor : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            if (value == null) removals += key else updates[key] = value
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply {
            if (values == null) removals += key else updates[key] = values.toSet()
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }

        override fun clear(): SharedPreferences.Editor = apply { removals += values.keys }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() {
            applyChanges()
        }

        private fun applyChanges() {
            removals.forEach(values::remove)
            updates.forEach { (key, value) -> values[key] = value }
        }
    }
}
