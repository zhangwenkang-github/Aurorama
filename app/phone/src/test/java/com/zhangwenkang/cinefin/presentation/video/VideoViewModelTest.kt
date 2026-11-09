package com.zhangwenkang.cinefin.presentation.video

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.player.local.domain.SleepTimerController
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
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.models.VideoDisplayMode
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoViewModelTest {
    private lateinit var mainDispatcher: TestDispatcher
    private var viewModelStore: ViewModelStore? = null
    private var fakePreferences: FakeSharedPreferences? = null

    @Before
    fun setUp() {
        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        try {
            viewModelStore?.clear()
            mainDispatcher.scheduler.runCurrent()
            assertEquals(0, fakePreferences?.listenerCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `video libraries and aggregation use the canonical scope`() =
        runTest(mainDispatcher.scheduler) {
            val catalog = FakeVideoCatalog(SCOPE)
            catalog.libraryResult = { query ->
                assertEquals(SCOPE, query.frozenScope)
                availableLibraries(SCOPE, listOf(library("A")))
            }
            val viewModel = installViewModel(newViewModel(catalog))

            viewModel.load()
            advanceUntilIdle()

            assertEquals(listOf("A"), viewModel.state.value.allLibraries.map { it.name })
            assertEquals(true, viewModel.state.value.loaded)
            assertNull(viewModel.state.value.error)
            assertEquals(SCOPE, catalog.lastLibraryQuery?.frozenScope)
        }

    @Test
    fun `fresh same generation read reuses existing libraries and flow`() =
        runTest(mainDispatcher.scheduler) {
            val catalog = FakeVideoCatalog(SCOPE)
            catalog.libraryResult = { availableLibraries(SCOPE, listOf(library("A"))) }
            val viewModel = installViewModel(newViewModel(catalog))

            viewModel.load()
            advanceUntilIdle()
            val flow = viewModel.state.value.aggregateItems
            viewModel.load()
            advanceUntilIdle()

            assertEquals(2, catalog.libraryCalls)
            assertEquals(listOf("A"), viewModel.state.value.allLibraries.map { it.name })
            assertSame(flow, viewModel.state.value.aggregateItems)
        }

    @Test
    fun `content generation advance invalidates libraries flow and loaded marker`() =
        runTest(mainDispatcher.scheduler) {
            var generation = 3L
            val catalog = FakeVideoCatalog(SCOPE)
            catalog.libraryResult = {
                availableLibraries(
                    scope = SCOPE,
                    libraries = listOf(library("generation-$generation")),
                    generation = generation,
                )
            }
            val viewModel = installViewModel(newViewModel(catalog))

            viewModel.load()
            advanceUntilIdle()
            val oldFlow = viewModel.state.value.aggregateItems
            assertEquals(listOf("generation-3"), viewModel.state.value.allLibraries.map { it.name })
            assertEquals(
                VideoLoadedMarker(SCOPE, contentGeneration = 3L),
                viewModel.loadedCatalogMarker,
            )

            generation = 4L
            viewModel.load()
            advanceUntilIdle()

            assertEquals(2, catalog.libraryCalls)
            assertEquals(listOf("generation-4"), viewModel.state.value.allLibraries.map { it.name })
            assertEquals(
                VideoLoadedMarker(SCOPE, contentGeneration = 4L),
                viewModel.loadedCatalogMarker,
            )
            assertNotSame(oldFlow, viewModel.state.value.aggregateItems)
        }

    @Test
    fun `scope changes while loading so the old result cannot overwrite new libraries`() =
        runTest(mainDispatcher.scheduler) {
            val oldGate = CompletableDeferred<Unit>()
            val nextScope = SCOPE.copy(identityGeneration = 2L)
            val catalog =
                FakeVideoCatalog(SCOPE).apply {
                    libraryResult = { query ->
                        if (query.frozenScope == SCOPE) {
                            oldGate.await()
                            availableLibraries(SCOPE, listOf(library("old")))
                        } else {
                            availableLibraries(nextScope, listOf(library("new")))
                        }
                    }
                }
            val viewModel = installViewModel(newViewModel(catalog))

            viewModel.load()
            runCurrent()
            catalog.scope = nextScope
            viewModel.load()
            advanceUntilIdle()
            oldGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("new"), viewModel.state.value.allLibraries.map { it.name })
            assertEquals(VideoLibrarySource.Online, viewModel.state.value.source)
        }

    @Test
    fun `identity unavailable clears old libraries and loaded marker`() =
        runTest(mainDispatcher.scheduler) {
            val catalog = FakeVideoCatalog(SCOPE)
            catalog.libraryResult = { availableLibraries(SCOPE, listOf(library("A"))) }
            val viewModel = installViewModel(newViewModel(catalog))
            viewModel.load()
            advanceUntilIdle()
            assertEquals(true, viewModel.state.value.loaded)

            catalog.scope = null
            viewModel.load()
            advanceUntilIdle()

            assertTrue(viewModel.state.value.libraries.isEmpty())
            assertTrue(viewModel.state.value.allLibraries.isEmpty())
            assertEquals(false, viewModel.state.value.loaded)
            assertEquals(false, viewModel.state.value.isLoading)
            assertEquals(0L, viewModel.state.value.loadedAtMs)
        }

    @Test
    fun `force refresh reaches the catalog query and stored selection is applied`() =
        runTest(mainDispatcher.scheduler) {
            val selected = library("B")
            val catalog = FakeVideoCatalog(SCOPE)
            catalog.libraryResult = { availableLibraries(SCOPE, listOf(library("A"), selected)) }
            val appPreferences = appPreferences()
            appPreferences.setValue(
                appPreferences.uiVideoDisplayMode,
                VideoDisplayMode.Aggregated.value,
            )
            appPreferences.setValue(appPreferences.uiVideoLibraryId, selected.id.toString())
            val viewModel =
                installViewModel(
                    VideoViewModel(
                        catalogReadPort = catalog,
                        appPreferences = appPreferences,
                        sleepTimerState = MutableStateFlow(SleepTimerController.State()),
                    )
                )

            viewModel.load(force = true)
            advanceUntilIdle()

            assertEquals(true, catalog.lastLibraryQuery?.forceRefresh)
            assertEquals(selected.id, viewModel.state.value.selectedLibraryId)
            assertEquals(listOf(selected), viewModel.state.value.libraries)
        }

    private fun appPreferences(): AppPreferences {
        val fake = FakeSharedPreferences()
        fakePreferences = fake
        return AppPreferences(fake)
    }

    private fun newViewModel(catalog: FakeVideoCatalog): VideoViewModel =
        VideoViewModel(
            catalogReadPort = catalog,
            appPreferences = appPreferences(),
            sleepTimerState = MutableStateFlow(SleepTimerController.State()),
        )

    private fun installViewModel(viewModel: VideoViewModel): VideoViewModel {
        val store = ViewModelStore()
        viewModelStore = store
        val provider =
            ViewModelProvider(
                store,
                object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return viewModel as T
                    }
                },
            )
        return provider[VideoViewModel::class.java]
    }

    private fun availableLibraries(
        scope: CatalogScope,
        libraries: List<FindroidCollection>,
        generation: Long = 3L,
    ): CatalogReadOutcome<List<FindroidCollection>> =
        CatalogReadOutcome.Available(
            CatalogSnapshot(
                scope = scope,
                contentGeneration = generation,
                freshness = CatalogFreshness.FRESH,
                observedAtEpochMs = 1L,
                value = libraries,
            )
        )

    private fun library(name: String): FindroidCollection =
        FindroidCollection(
            id = UUID.nameUUIDFromBytes(name.toByteArray()),
            name = name,
            type = CollectionType.Movies,
            images = FindroidImages(),
        )

    private class FakeVideoCatalog(var scope: CatalogScope?) : CatalogReadPort {
        var libraryCalls = 0
        var lastLibraryQuery: CatalogLibrariesQuery? = null
        var libraryResult:
            suspend (CatalogLibrariesQuery) -> CatalogReadOutcome<List<FindroidCollection>> =
            {
                CatalogReadOutcome.IdentityUnavailable
            }

        override suspend fun currentScope(): CatalogScope? = scope

        override suspend fun search(
            query: CatalogSearchQuery
        ): CatalogReadOutcome<List<FindroidItem>> = error("search must not run")

        override suspend fun home(query: CatalogHomeQuery): CatalogReadOutcome<CatalogHomeData> =
            error("home must not run")

        override suspend fun libraries(
            query: CatalogLibrariesQuery
        ): CatalogReadOutcome<List<FindroidCollection>> {
            libraryCalls += 1
            lastLibraryQuery = query
            return libraryResult(query)
        }

        override suspend fun itemsPage(
            query: CatalogItemsPageQuery
        ): CatalogReadOutcome<List<FindroidItem>> = error("items must not run")

        override suspend fun movie(query: CatalogMovieQuery): CatalogReadOutcome<FindroidMovie> =
            error("movie must not run")
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        private val listeners =
            CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

        val listenerCount: Int
            get() = listeners.size

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String, defValue: String?): String? =
            values[key] as String? ?: defValue

        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float =
            values[key] as? Float ?: defValue

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
            private var clearRequested = false

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

            override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

            override fun commit(): Boolean {
                applyChanges()
                return true
            }

            override fun apply() {
                applyChanges()
            }

            private fun applyChanges() {
                val changed = mutableSetOf<String>()
                if (clearRequested) {
                    changed += values.keys
                    values.clear()
                }
                removals.forEach { key ->
                    if (values.remove(key) != null || key in changed) changed += key
                }
                updates.forEach { (key, value) ->
                    if (values[key] != value) changed += key
                    values[key] = value
                }
                changed.forEach { key ->
                    listeners.forEach {
                        it.onSharedPreferenceChanged(this@FakeSharedPreferences, key)
                    }
                }
            }
        }
    }

    private companion object {
        const val SERVER_ID = "server-a"
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val SCOPE =
            CatalogScope(
                origin = JellyfinOrigin(SERVER_ID, USER_ID),
                principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
                identityGeneration = 1L,
            )
    }
}
