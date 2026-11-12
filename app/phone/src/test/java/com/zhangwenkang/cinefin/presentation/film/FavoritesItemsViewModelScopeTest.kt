package com.zhangwenkang.cinefin.presentation.film

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.models.FindroidFolder
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Provider
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesItemsViewModelScopeTest {
    private lateinit var mainDispatcher: TestDispatcher

    @Before
    fun setUp() {
        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun userScopeChangeClearsAndReloadsFavorites() =
        runTest(mainDispatcher.scheduler) {
            val oldRefreshGate = CompletableDeferred<Unit>()
            val userA = UUID.nameUUIDFromBytes("favorites-user-A".toByteArray())
            val userB = UUID.nameUUIDFromBytes("favorites-user-B".toByteArray())
            val itemA = item("favorite-A")
            val itemB = item("favorite-B")
            var currentUser = userA
            var repositoryACalls = 0
            val repositoryA =
                favoritesRepository(backgroundScope, userA) {
                    when (++repositoryACalls) {
                        1 -> listOf(itemA)
                        else -> {
                            oldRefreshGate.await()
                            listOf(itemA)
                        }
                    }
                }
            val repositoryB = favoritesRepository(backgroundScope, userB) { listOf(itemB) }
            val preferences = AppPreferences(FavoritesFakeSharedPreferences())
            preferences.setValue(preferences.currentServer, "server-1")
            val provider =
                object : Provider<JellyfinRepository> {
                    override fun get(): JellyfinRepository =
                        if (currentUser == userA) repositoryA else repositoryB
                }
            val viewModel = FavoritesItemsViewModel(provider, preferences)

            viewModel.load()
            runCurrent()
            assertEquals(listOf(itemA), viewModel.state.value.items)

            viewModel.load()
            runCurrent()
            assertEquals(listOf(itemA), viewModel.state.value.items)

            currentUser = userB
            preferences.setValue(preferences.mediaCatalogScopeRevision, 1L)
            runCurrent()

            assertEquals(userB, viewModel.state.value.catalogScope?.userId)
            assertEquals(listOf(itemB), viewModel.state.value.items)

            oldRefreshGate.complete(Unit)
            runCurrent()

            assertEquals(userB, viewModel.state.value.catalogScope?.userId)
            assertEquals(listOf(itemB), viewModel.state.value.items)

            viewModel.viewModelScope.cancel()
            runCurrent()
        }

    private fun favoritesRepository(
        scope: CoroutineScope,
        userId: UUID,
        loadItems: suspend () -> List<FindroidItem>,
    ): JellyfinRepository =
        java.lang.reflect.Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getFavoriteItems" -> {
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args!!.last() as Continuation<List<FindroidItem>>
                    scope.launch { continuation.resumeWith(runCatching { loadItems() }) }
                    COROUTINE_SUSPENDED
                }
                "getUserId" -> userId
                "toString" -> "FavoritesItemsViewModelScopeTestRepository"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> throw AssertionError("Unexpected repository call: ${method.name}")
            }
        } as JellyfinRepository

    private fun item(name: String): FindroidItem =
        FindroidFolder(
            id = UUID.nameUUIDFromBytes(name.toByteArray()),
            name = name,
            played = false,
            favorite = true,
            unplayedItemCount = null,
            images = FindroidImages(),
        )
}

private class FavoritesFakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    private val listeners =
        CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

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
                listeners.forEach {
                    it.onSharedPreferenceChanged(this@FavoritesFakeSharedPreferences, key)
                }
            }
        }
    }
}
