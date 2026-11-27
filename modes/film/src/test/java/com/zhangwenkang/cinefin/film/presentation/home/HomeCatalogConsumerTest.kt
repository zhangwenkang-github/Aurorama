package com.zhangwenkang.cinefin.film.presentation.home

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
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
import com.zhangwenkang.cinefin.repository.catalog.CatalogView
import com.zhangwenkang.cinefin.repository.catalog.CatalogViewItems
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.BookCoverProvider
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogConsumerTest {
    private var viewModelStore: ViewModelStore? = null

    @After
    fun tearDown() {
        viewModelStore?.clear()
    }

    @Test
    fun `one catalog home data projects all enabled corridors atomically`() {
        val projected =
            fullHome()
                .toHomeState(
                    current = HomeState(),
                    options = options(),
                )

        assertEquals(
            listOf("suggestion"),
            projected.suggestionsSection?.items?.map(FindroidItem::name),
        )
        assertEquals(
            listOf("resume"),
            projected.resumeSection?.homeSection?.items?.map(FindroidItem::name),
        )
        assertEquals(
            listOf("reading"),
            projected.resumeReadingSection?.homeSection?.items?.map(FindroidItem::name),
        )
        assertEquals(
            listOf("listening"),
            projected.resumeListeningSection?.homeSection?.items?.map(FindroidItem::name),
        )
        assertEquals(
            listOf("next"),
            projected.nextUpSection?.homeSection?.items?.map(FindroidItem::name),
        )
        assertEquals(listOf("latest"), projected.recentlyAddedVideos.map(FindroidItem::name))
        assertEquals(listOf(LIBRARY_ID), projected.views.map { it.view.id })
    }

    @Test
    fun `disabled corridors and empty legal results clear stale same reader content`() {
        val previous = fullHome().toHomeState(HomeState(), options())
        val projected =
            CatalogHomeData()
                .toHomeState(
                    current = previous,
                    options =
                        options()
                            .copy(
                                includeSuggestions = false,
                                includeContinueWatching = false,
                                includeContinueReading = false,
                                includeContinueListening = false,
                                includeNextUp = false,
                                includeUserViews = false,
                            ),
                )

        assertNull(projected.suggestionsSection)
        assertNull(projected.resumeSection)
        assertNull(projected.resumeReadingSection)
        assertNull(projected.resumeListeningSection)
        assertNull(projected.nextUpSection)
        assertTrue(projected.views.isEmpty())
        assertTrue(projected.recentlyAddedVideos.isEmpty())
    }

    @Test
    fun `home snapshot scope guard rejects server user context and generation changes`() {
        assertTrue(isHomeCatalogResultCurrent(SCOPE, SCOPE, SCOPE))
        assertFalse(
            isHomeCatalogResultCurrent(
                SCOPE,
                SCOPE.copy(identityGeneration = 2L),
                SCOPE.copy(identityGeneration = 2L),
            )
        )
        assertFalse(
            isHomeCatalogResultCurrent(
                SCOPE,
                SCOPE.copy(
                    origin = SCOPE.origin.copy(userId = OTHER_USER_ID),
                    identityGeneration = 2L,
                ),
                SCOPE.copy(identityGeneration = 2L),
            )
        )
        assertFalse(
            isHomeCatalogResultCurrent(
                SCOPE,
                SCOPE.copy(principalContext = CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT),
                SCOPE.copy(principalContext = CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT),
            )
        )
        assertFalse(isHomeCatalogResultCurrent(SCOPE, SCOPE, null))
    }

    @Test
    fun `library order and visibility are applied after one composite read`() {
        val first = CatalogViewItems(view("A", LIBRARY_A), listOf(collection("a")))
        val hidden = CatalogViewItems(view("B", LIBRARY_B), listOf(collection("b")))
        val last = CatalogViewItems(view("C", LIBRARY_C), listOf(collection("c")))
        val projected =
            CatalogHomeData(views = listOf(first, hidden, last))
                .toHomeState(
                    current = HomeState(),
                    options =
                        options()
                            .copy(
                                hiddenLibraryIds = setOf(LIBRARY_B.toString()),
                                libraryOrder = listOf(LIBRARY_C.toString(), LIBRARY_A.toString()),
                                includeRecentVideos = false,
                            ),
                )

        assertEquals(listOf(LIBRARY_C, LIBRARY_A), projected.views.map { it.view.id })
    }

    @Test
    fun `real home view model rejects an old suspended composite after scope switch`() =
        runBlocking {
            val oldGate = CompletableDeferred<Unit>()
            val nextScope = SCOPE.copy(identityGeneration = 2L)
            val catalog =
                HomeCatalogPort(scope = SCOPE).apply {
                    homeResult = { query ->
                        if (query.frozenScope == SCOPE) {
                            oldGate.await()
                            available(SCOPE, CatalogHomeData(views = listOf(view("old"))))
                        } else {
                            available(nextScope, CatalogHomeData(views = listOf(view("new"))))
                        }
                    }
                }
            val viewModel =
                installViewModel(
                    HomeViewModel(
                            catalogReadPort = catalog,
                            appPreferences = appPreferences(),
                            database = database(),
                            bookCoverProvider = bookCoverProvider(),
                        )
                        .withUnconfinedViewModelScope()
                )

            viewModel.loadData()
            catalog.scope = nextScope
            viewModel.loadData()
            assertEquals(listOf("new"), viewModel.state.value.views.map { it.view.name })

            oldGate.complete(Unit)

            assertEquals(listOf("new"), viewModel.state.value.views.map { it.view.name })
        }

    @Test
    fun `real home view model preserves catalog when dynamic resolution is cancelled`() =
        runBlocking {
            val catalog =
                HomeCatalogPort(scope = SCOPE).apply {
                    homeResult = {
                        available(SCOPE, CatalogHomeData(views = listOf(view("stable"))))
                    }
                }
            val viewModel =
                installViewModel(
                    HomeViewModel(
                            catalogReadPort = catalog,
                            appPreferences = appPreferences(),
                            database = database(),
                            bookCoverProvider = bookCoverProvider(),
                        )
                        .withUnconfinedViewModelScope()
                )
            viewModel.loadData()
            assertEquals(listOf("stable"), viewModel.state.value.views.map { it.view.name })

            catalog.scopeFailure = CancellationException("dynamic scope cancelled")
            viewModel.loadData()

            assertEquals(listOf("stable"), viewModel.state.value.views.map { it.view.name })
            assertTrue(viewModel.state.value.error == null)
        }

    @Test
    fun `returning from the player revalidates home without a full page reload`() = runBlocking {
        val catalog =
            HomeCatalogPort(scope = SCOPE).apply {
                homeResult = {
                    available(
                        SCOPE,
                        CatalogHomeData(views = listOf(view("stable"))),
                    )
                }
            }
        val viewModel =
            installViewModel(
                HomeViewModel(
                        catalogReadPort = catalog,
                        appPreferences = appPreferences(),
                        database = database(),
                        bookCoverProvider = bookCoverProvider(),
                    )
                    .withUnconfinedViewModelScope()
            )
        viewModel.loadData()
        val loaded = viewModel.state.value
        assertEquals(listOf("stable"), loaded.views.map { it.view.name })
        assertFalse(loaded.isLoading)

        val emitted = mutableListOf<HomeState>()
        val collector = launch(Dispatchers.Unconfined) { viewModel.state.collect { emitted += it } }
        try {
            // 播放停止上报会失效元数据缓存；返回首页时页面会再 loadData 一次
            viewModel.loadData()
        } finally {
            collector.cancel()
        }

        assertEquals(2, catalog.homeCalls)
        // 已有内容 + 同 scope：全程不出现整页 loading（不闪骨架），内容对象保持稳定
        assertTrue(
            "unexpected loading states: ${emitted.filter { it.isLoading } }",
            emitted.none { it.isLoading },
        )
        // 内容完全没变：整份状态对象（含 views 列表实例）都被复用，StateFlow 不再发射
        assertSame(loaded, viewModel.state.value)
        assertSame(loaded.views, viewModel.state.value.views)
    }

    @Test
    fun `first load and scope change still show full page loading`() {
        assertTrue(homeLoadShowsFullPageLoading(sameScope = false, hasRenderableContent = false))
        assertTrue(homeLoadShowsFullPageLoading(sameScope = false, hasRenderableContent = true))
        assertTrue(homeLoadShowsFullPageLoading(sameScope = true, hasRenderableContent = false))
        assertFalse(homeLoadShowsFullPageLoading(sameScope = true, hasRenderableContent = true))
    }

    @Test
    fun `progress change updates the item but keeps unchanged items and list order stable`() {
        val playing = libraryItem(name = "playing", playbackPositionTicks = 0L)
        val untouched = libraryItem(name = "untouched", playbackPositionTicks = 0L)
        val previous = homeWithLibraryItems(listOf(playing, untouched))
        val resumed = playing.copy(playbackPositionTicks = 42_000L)
        val next = homeWithLibraryItems(listOf(resumed, untouched))

        val merged = previous.withMergedContent(next)

        // 用户数据真实更新（进度条会前进）
        assertEquals(42_000L, merged.views.single().view.items.first().playbackPositionTicks)
        // 变过的条目换新实例，没变的条目沿用旧实例，顺序不变
        assertSame(resumed, merged.views.single().view.items.first())
        assertSame(untouched, merged.views.single().view.items[1])
        assertEquals(2, merged.views.single().view.items.size)
        // 内容确实变了：整份状态与旧对象不同（否则进度又会被丢弃）
        assertTrue(merged != previous)
    }

    @Test
    fun `merge reuses the previous content objects when nothing user visible changed`() {
        val item = libraryItem(name = "stable", playbackPositionTicks = 7L)
        val previous = homeWithLibraryItems(listOf(item))
        // 同一份数据的新快照（对象是新实例、字段完全相等）
        val next = homeWithLibraryItems(listOf(item.copy())).copy(isLoading = true)

        val merged = previous.withMergedContent(next)

        // 内容等价：走廊里的条目、库行、列表都沿用旧实例（滚动位置不丢）
        assertSame(item, merged.views.single().view.items.single())
        assertSame(previous.views.single(), merged.views.single())
        assertSame(previous.recentlyAddedVideos, merged.recentlyAddedVideos)
        // 非内容字段（loading）仍由新快照决定
        assertEquals(true, merged.isLoading)
        assertTrue(previous.hasRenderableContent())
        assertFalse(HomeState().hasRenderableContent())
    }

    @Test
    fun `returning from playback surfaces updated progress in the view model`() = runBlocking {
        var progressTicks = 0L
        val catalog =
            HomeCatalogPort(scope = SCOPE).apply {
                homeResult = {
                    available(
                        SCOPE,
                        CatalogHomeData(
                            views =
                                listOf(
                                    CatalogViewItems(
                                        view("Movies", LIBRARY_ID),
                                        listOf(
                                            libraryItem(
                                                name = "movie",
                                                playbackPositionTicks = progressTicks,
                                            )
                                        ),
                                    )
                                )
                        ),
                    )
                }
            }
        val viewModel =
            installViewModel(
                HomeViewModel(
                        catalogReadPort = catalog,
                        appPreferences = appPreferences(),
                        database = database(),
                        bookCoverProvider = bookCoverProvider(),
                    )
                    .withUnconfinedViewModelScope()
            )
        viewModel.loadData()
        val firstItem = viewModel.state.value.views.single().view.items.single()
        assertEquals(0L, firstItem.playbackPositionTicks)

        // 播放停止上报写回进度；返回首页重新取数
        progressTicks = 125_000L
        viewModel.loadData()

        val updated = viewModel.state.value.views.single().view.items.single()
        assertEquals(125_000L, updated.playbackPositionTicks)
        // 用户可见字段变了就必须换实例（沿用旧实例等于把进度丢掉）
        assertTrue(firstItem !== updated)
        assertFalse(viewModel.state.value.isLoading)
    }

    private fun homeWithLibraryItems(items: List<FindroidItem>): HomeState =
        CatalogHomeData(
                views =
                    listOf(CatalogViewItems(view = view("Movies", LIBRARY_ID), latestMedia = items))
            )
            .toHomeState(current = HomeState(), options = options())

    private fun libraryItem(name: String, playbackPositionTicks: Long): FindroidCollection =
        FindroidCollection(
            // 同一条目 id 稳定：进度变化只是字段变化，不能顺带换 id
            id = UUID.nameUUIDFromBytes(name.toByteArray()),
            name = name,
            type = CollectionType.Movies,
            images = FindroidImages(),
            playbackPositionTicks = playbackPositionTicks,
        )

    private fun fullHome(): CatalogHomeData =
        CatalogHomeData(
            suggestions = listOf(collection("suggestion")),
            resumeWatching = listOf(collection("resume")),
            resumeReading = listOf(collection("reading")),
            resumeListening = listOf(collection("listening")),
            nextUp = listOf(collection("next")),
            views =
                listOf(CatalogViewItems(view("Movies", LIBRARY_ID), listOf(collection("latest")))),
        )

    private fun options(): HomeCatalogOptions =
        HomeCatalogOptions(
            includeSuggestions = true,
            includeContinueWatching = true,
            includeContinueReading = true,
            includeContinueListening = true,
            includeNextUp = true,
            includeUserViews = true,
            hiddenLibraryIds = emptySet(),
            libraryOrder = emptyList(),
            includeRecentVideos = true,
            includeRecentBooks = false,
            includeRecentMusic = false,
        )

    private fun view(name: String, id: UUID): CatalogView =
        CatalogView(id = id, name = name, type = CollectionType.Movies)

    private fun collection(name: String): FindroidCollection =
        FindroidCollection(
            id = UUID.nameUUIDFromBytes(name.toByteArray()),
            name = name,
            type = CollectionType.Movies,
            images = FindroidImages(),
        )

    private fun view(name: String): CatalogViewItems =
        CatalogViewItems(
            view = view(name, UUID.nameUUIDFromBytes(name.toByteArray())),
            latestMedia = listOf(collection(name)),
        )

    private fun available(
        scope: CatalogScope,
        home: CatalogHomeData,
    ): CatalogReadOutcome<CatalogHomeData> =
        CatalogReadOutcome.Available(
            CatalogSnapshot(
                scope = scope,
                contentGeneration = 1L,
                freshness = CatalogFreshness.FRESH,
                observedAtEpochMs = 1L,
                value = home,
            )
        )

    private fun appPreferences(): AppPreferences = AppPreferences(FakeSharedPreferences())

    private fun database(): ServerDatabaseDao =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "HomeCatalogConsumerTestDatabase"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "getServer" -> null
                else -> defaultValue(method.returnType)
            }
        } as ServerDatabaseDao

    private fun bookCoverProvider(): BookCoverProvider {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, BookCoverProvider::class.java) as BookCoverProvider
    }

    private fun <T : ViewModel> T.withUnconfinedViewModelScope(): T {
        val closeableClass =
            Class.forName("androidx.lifecycle.viewmodel.internal.CloseableCoroutineScope")
        val closeable =
            closeableClass
                .getConstructor(CoroutineScope::class.java)
                .newInstance(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        ViewModel::class
            .java
            .getMethod("addCloseable", String::class.java, AutoCloseable::class.java)
            .invoke(
                this,
                "androidx.lifecycle.viewmodel.internal.ViewModelCoroutineScope.JOB_KEY",
                closeable,
            )
        return this
    }

    private fun installViewModel(viewModel: HomeViewModel): HomeViewModel {
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
        return provider[HomeViewModel::class.java]
    }

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> '\u0000'
            else -> null
        }

    private class HomeCatalogPort(
        var scope: CatalogScope?,
        var scopeFailure: CancellationException? = null,
    ) : CatalogReadPort {
        var homeCalls = 0

        var homeResult: suspend (CatalogHomeQuery) -> CatalogReadOutcome<CatalogHomeData> = {
            HOME_UNAVAILABLE
        }

        override suspend fun currentScope(): CatalogScope? {
            scopeFailure?.let { throw it }
            return scope
        }

        override suspend fun search(
            query: CatalogSearchQuery
        ): CatalogReadOutcome<List<FindroidItem>> = error("search must not run")

        override suspend fun home(query: CatalogHomeQuery): CatalogReadOutcome<CatalogHomeData> {
            homeCalls += 1
            return homeResult(query)
        }

        override suspend fun libraries(
            query: CatalogLibrariesQuery
        ): CatalogReadOutcome<List<FindroidCollection>> = error("libraries must not run")

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

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String, defValue: String?): String? =
            values[key] as String? ?: defValue

        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float =
            values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor =
            object : SharedPreferences.Editor {
                override fun putString(
                    key: String,
                    value: String?,
                ): SharedPreferences.Editor = apply { values[key] = value }

                override fun putStringSet(
                    key: String,
                    values: MutableSet<String>?,
                ): SharedPreferences.Editor = apply {
                    this@FakeSharedPreferences.values[key] = values
                }

                override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                    values[key] = value
                }

                override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                    values[key] = value
                }

                override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                    values[key] = value
                }

                override fun putBoolean(
                    key: String,
                    value: Boolean,
                ): SharedPreferences.Editor = apply { values[key] = value }

                override fun remove(key: String): SharedPreferences.Editor = apply {
                    values.remove(key)
                }

                override fun clear(): SharedPreferences.Editor = apply { values.clear() }

                override fun commit(): Boolean {
                    notifyListeners()
                    return true
                }

                override fun apply() {
                    notifyListeners()
                }

                private fun notifyListeners() {
                    listeners.forEach {
                        it.onSharedPreferenceChanged(this@FakeSharedPreferences, "")
                    }
                }
            }

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
    }

    private companion object {
        const val SERVER_ID = "server-a"
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val OTHER_USER_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val LIBRARY_ID: UUID = UUID.fromString("55555555-5555-4555-8555-555555555555")
        val LIBRARY_A: UUID = UUID.fromString("66666666-6666-4666-8666-666666666666")
        val LIBRARY_B: UUID = UUID.fromString("77777777-7777-4777-8777-777777777777")
        val LIBRARY_C: UUID = UUID.fromString("88888888-8888-4888-8888-888888888888")
        val SCOPE =
            CatalogScope(
                origin = JellyfinOrigin(SERVER_ID, USER_ID),
                principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
                identityGeneration = 1L,
            )
        val HOME_UNAVAILABLE: CatalogReadOutcome<CatalogHomeData> =
            CatalogReadOutcome.IdentityUnavailable
    }
}
