package com.zhangwenkang.cinefin.presentation.video

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.player.local.domain.SleepTimerController
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.models.VideoDisplayMode
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Provider
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoViewModelTest {
    private lateinit var mainDispatcher: TestDispatcher
    private var viewModelStore: ViewModelStore? = null
    private var preferences: FakeSharedPreferences? = null

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
            assertEquals(0, preferences?.listenerCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lateOnlineSuccessCannotOverwriteOfflineRepositoryResult() =
        runTest(mainDispatcher.scheduler) {
            val onlineGate = CompletableDeferred<Unit>()
            val onlineLibrary = library("online-A")
            val offlineLibrary = library("offline-B")
            val online =
                controlledRepository(backgroundScope, "online") {
                    onlineGate.await()
                    listOf(onlineLibrary)
                }
            val offline =
                controlledRepository(backgroundScope, "offline") { listOf(offlineLibrary) }
            val appPreferences = appPreferences(isOfflineMode = false)
            val provider = SwitchingRepositoryProvider(appPreferences, online, offline)
            val viewModel = installViewModel(newViewModel(provider, appPreferences))

            viewModel.load()
            runCurrent()
            assertEquals(1, online.getLibrariesCalls)

            appPreferences.setValue(appPreferences.offlineMode, true)
            runCurrent()
            assertEquals(listOf(offlineLibrary), viewModel.state.value.allLibraries)

            onlineGate.complete(Unit)
            runCurrent()

            assertEquals(VideoLibrarySource.Offline, viewModel.state.value.source)
            assertEquals(listOf(offlineLibrary), viewModel.state.value.allLibraries)
            assertEquals(listOf("online", "offline"), provider.resolvedRepositories)
            assertEquals(1, offline.getLibrariesCalls)
        }

    @Test
    fun lateOnlineFailureCannotOverwriteOfflineRepositoryResult() =
        runTest(mainDispatcher.scheduler) {
            val onlineGate = CompletableDeferred<Unit>()
            val offlineLibrary = library("offline-B")
            val online =
                controlledRepository(backgroundScope, "online") {
                    onlineGate.await()
                    throw IOException("online request failed")
                }
            val offline =
                controlledRepository(backgroundScope, "offline") { listOf(offlineLibrary) }
            val appPreferences = appPreferences(isOfflineMode = false)
            val provider = SwitchingRepositoryProvider(appPreferences, online, offline)
            val viewModel = installViewModel(newViewModel(provider, appPreferences))

            viewModel.load()
            runCurrent()
            appPreferences.setValue(appPreferences.offlineMode, true)
            runCurrent()

            onlineGate.completeExceptionally(IOException("late online failure"))
            runCurrent()

            assertEquals(VideoLibrarySource.Offline, viewModel.state.value.source)
            assertEquals(listOf(offlineLibrary), viewModel.state.value.allLibraries)
            assertNull(viewModel.state.value.error)
            assertEquals(1, online.getLibrariesCalls)
            assertEquals(1, offline.getLibrariesCalls)
        }

    @Test
    fun returningOnlineReloadsRepositoryAndRestoresStoredLibrarySelection() =
        runTest(mainDispatcher.scheduler) {
            val first = library("online-A")
            val selected = library("online-B")
            val online = controlledRepository(backgroundScope, "online") { listOf(first, selected) }
            val offline = controlledRepository(backgroundScope, "offline") { emptyList() }
            val appPreferences = appPreferences(isOfflineMode = true)
            appPreferences.setValue(
                appPreferences.uiVideoDisplayMode,
                VideoDisplayMode.Aggregated.value,
            )
            appPreferences.setValue(appPreferences.uiVideoLibraryId, selected.id.toString())
            val provider = SwitchingRepositoryProvider(appPreferences, online, offline)
            val viewModel = installViewModel(newViewModel(provider, appPreferences))

            viewModel.load()
            runCurrent()
            assertEquals(1, offline.getLibrariesCalls)
            assertEquals(0, online.getLibrariesCalls)

            appPreferences.setValue(appPreferences.offlineMode, false)
            runCurrent()

            assertEquals(1, online.getLibrariesCalls)
            assertEquals(selected.id, viewModel.state.value.selectedLibraryId)
            assertEquals(listOf(selected), viewModel.state.value.libraries)
            assertEquals(listOf(first, selected), viewModel.state.value.allLibraries)
            assertEquals(listOf("offline", "online"), provider.resolvedRepositories)
        }

    @Test
    fun offlineModeSelectsOfflineRepositoryWithoutTouchingOnlineRepository() =
        runTest(mainDispatcher.scheduler) {
            val offlineLibrary = library("offline-B")
            val online = controlledRepository(backgroundScope, "online") { error("must not load") }
            val offline =
                controlledRepository(backgroundScope, "offline") { listOf(offlineLibrary) }
            val appPreferences = appPreferences(isOfflineMode = true)
            val provider = SwitchingRepositoryProvider(appPreferences, online, offline)
            val viewModel = installViewModel(newViewModel(provider, appPreferences))

            viewModel.load()
            runCurrent()

            assertEquals(0, online.getLibrariesCalls)
            assertEquals(1, offline.getLibrariesCalls)
            assertEquals(listOf("offline"), provider.resolvedRepositories)
            assertEquals(listOf(offlineLibrary), viewModel.state.value.allLibraries)
        }

    @Test
    fun freshSameSourceCacheStillReusesWithoutSecondRepositoryCall() =
        runTest(mainDispatcher.scheduler) {
            val onlineLibrary = library("online-A")
            val online = controlledRepository(backgroundScope, "online") { listOf(onlineLibrary) }
            val offline = controlledRepository(backgroundScope, "offline") { emptyList() }
            val appPreferences = appPreferences(isOfflineMode = false)
            val provider = SwitchingRepositoryProvider(appPreferences, online, offline)
            val viewModel = installViewModel(newViewModel(provider, appPreferences))

            viewModel.load()
            runCurrent()
            viewModel.load()
            runCurrent()

            assertEquals(1, online.getLibrariesCalls)
            assertEquals(listOf(onlineLibrary), viewModel.state.value.allLibraries)
            assertEquals(listOf("online"), provider.resolvedRepositories)
        }

    @Test
    fun temporaryLibraryReturningOnlineIgnoresEarlierFullLibraryRequest() =
        runTest(mainDispatcher.scheduler) {
            val fullLibrariesGate = CompletableDeferred<Unit>()
            val temporaryLibrariesGate = CompletableDeferred<Unit>()
            val first = library("online-A")
            val selected = library("online-B")
            var onlineCall = 0
            val online =
                controlledRepository(backgroundScope, "online") {
                    when (++onlineCall) {
                        1 -> {
                            fullLibrariesGate.await()
                            listOf(first, selected)
                        }
                        2 -> {
                            temporaryLibrariesGate.await()
                            listOf(first, selected)
                        }
                        else -> error("unexpected online call")
                    }
                }
            val offline = controlledRepository(backgroundScope, "offline") { emptyList() }
            val appPreferences = appPreferences(isOfflineMode = true)
            val provider = SwitchingRepositoryProvider(appPreferences, online, offline)
            val viewModel = installViewModel(newViewModel(provider, appPreferences))

            viewModel.load(temporaryLibraryId = selected.id.toString())
            runCurrent()
            appPreferences.setValue(appPreferences.offlineMode, false)
            runCurrent()
            viewModel.load(temporaryLibraryId = selected.id.toString())
            runCurrent()

            assertEquals(2, online.getLibrariesCalls)
            assertTrue(viewModel.state.value.allLibraries.isEmpty())

            temporaryLibrariesGate.complete(Unit)
            runCurrent()
            assertEquals(selected, viewModel.state.value.temporaryLibrary)
            assertEquals(listOf(selected), viewModel.state.value.libraries)

            fullLibrariesGate.complete(Unit)
            runCurrent()
            assertEquals(selected, viewModel.state.value.temporaryLibrary)
            assertEquals(listOf(selected), viewModel.state.value.libraries)
        }

    private fun TestScope.appPreferences(isOfflineMode: Boolean): AppPreferences {
        val fake = FakeSharedPreferences()
        preferences = fake
        return AppPreferences(fake).also { preferences ->
            preferences.setValue(preferences.offlineMode, isOfflineMode)
        }
    }

    private fun controlledRepository(
        scope: CoroutineScope,
        label: String,
        loadLibraries: suspend () -> List<FindroidCollection>,
    ): ControlledRepository = ControlledRepository(scope, label, loadLibraries)

    private fun newViewModel(
        provider: Provider<JellyfinRepository>,
        appPreferences: AppPreferences,
    ): VideoViewModel =
        VideoViewModel(
            repositoryProvider = provider,
            appPreferences = appPreferences,
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
                        assertTrue(modelClass.isInstance(viewModel))
                        @Suppress("UNCHECKED_CAST")
                        return viewModel as T
                    }
                },
            )
        return provider[VideoViewModel::class.java]
    }

    private fun library(name: String): FindroidCollection =
        FindroidCollection(
            id = UUID.nameUUIDFromBytes(name.toByteArray()),
            name = name,
            type = CollectionType.Movies,
            images = FindroidImages(),
        )
}

private class SwitchingRepositoryProvider(
    private val appPreferences: AppPreferences,
    private val online: ControlledRepository,
    private val offline: ControlledRepository,
) : Provider<JellyfinRepository> {
    val resolvedRepositories = mutableListOf<String>()

    override fun get(): JellyfinRepository {
        val repository =
            if (appPreferences.getValue(appPreferences.offlineMode)) offline else online
        resolvedRepositories += repository.label
        return repository.repository
    }
}

private class ControlledRepository(
    private val scope: CoroutineScope,
    val label: String,
    private val loadLibraries: suspend () -> List<FindroidCollection>,
) {
    var getLibrariesCalls = 0
        private set

    var invalidateCacheCalls = 0
        private set

    val repository: JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getLibraries" -> {
                    getLibrariesCalls++
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args!!.last() as Continuation<List<FindroidCollection>>
                    scope.launch { continuation.resumeWith(runCatching { loadLibraries() }) }
                    COROUTINE_SUSPENDED
                }
                "invalidateMetadataCache" -> {
                    invalidateCacheCalls++
                    null
                }
                "toString" -> label
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> throw AssertionError("Unexpected repository call: ${method.name}")
            }
        } as JellyfinRepository
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
                listeners.forEach { it.onSharedPreferenceChanged(this@FakeSharedPreferences, key) }
            }
        }
    }
}
