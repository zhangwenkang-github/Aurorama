package com.zhangwenkang.cinefin.presentation.search

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import com.zhangwenkang.cinefin.film.presentation.search.SearchAction
import com.zhangwenkang.cinefin.film.presentation.search.SearchResult
import com.zhangwenkang.cinefin.film.presentation.search.SearchViewModel
import com.zhangwenkang.cinefin.local.LocalLibraryEntry
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.local.LocalMediaKind
import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.models.FindroidFolder
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.offline.OfflineModeViewModel
import com.zhangwenkang.cinefin.repository.JellyfinOrigin
import com.zhangwenkang.cinefin.repository.catalog.CatalogFreshness
import com.zhangwenkang.cinefin.repository.catalog.CatalogPrincipalContext
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadOutcome
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadPort
import com.zhangwenkang.cinefin.repository.catalog.CatalogScope
import com.zhangwenkang.cinefin.repository.catalog.CatalogSearchQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogSnapshot
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.OfflineModeWritePort
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceSwitch
import com.zhangwenkang.cinefin.settings.presentation.settings.SettingsAction
import com.zhangwenkang.cinefin.settings.presentation.settings.SettingsViewModel
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadMediaRecord
import com.zhangwenkang.cinefin.utils.Downloader
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `搜索请求与响应后处理不在 Main 调用线程执行`() =
        runTest(mainDispatcher.scheduler) {
            val callerThread = Thread.currentThread().name
            val serverThread = CompletableDeferred<String>()
            val localThread = CompletableDeferred<String>()
            val viewModel =
                viewModel(
                    onServerSearch = { query ->
                        serverThread.complete(Thread.currentThread().name)
                        available(listOf(folder(query.term)))
                    },
                    onLocalSearch = {
                        localThread.complete(Thread.currentThread().name)
                        emptyList()
                    },
                )

            viewModel.onAction(SearchAction.UpdateQuery("sample"))
            viewModel.onAction(SearchAction.Search("sample"))

            val finalState =
                viewModel.state.first {
                    it.query == "sample" && !it.loading && it.results.isNotEmpty()
                }

            assertNotEquals(callerThread, serverThread.await())
            assertNotEquals(callerThread, localThread.await())
            assertEquals(listOf("sample"), finalState.serverItems.map { it.name })
        }

    @Test
    fun `新查询取消旧查询后旧结果不会覆盖新状态`() =
        runTest(mainDispatcher.scheduler) {
            val firstStarted = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val firstResponse = CompletableDeferred<CatalogReadOutcome<List<FindroidItem>>>()
            val secondResponse = CompletableDeferred<CatalogReadOutcome<List<FindroidItem>>>()
            val viewModel =
                viewModel(
                    onServerSearch = { query ->
                        when (query.term) {
                            "first" -> {
                                firstStarted.complete(Unit)
                                firstResponse.await()
                            }
                            "second" -> {
                                secondStarted.complete(Unit)
                                secondResponse.await()
                            }
                            else -> error("unexpected query: ${query.term}")
                        }
                    }
                )
            val acceptedSequences = mutableListOf<List<String>>()
            val collector =
                launch(mainDispatcher) {
                    viewModel.state.collect { state ->
                        if (!state.loading && state.serverItems.isNotEmpty()) {
                            acceptedSequences += state.serverItems.map { it.name }
                        }
                    }
                }

            viewModel.onAction(SearchAction.UpdateQuery("first"))
            viewModel.onAction(SearchAction.Search("first"))
            firstStarted.await()
            viewModel.onAction(SearchAction.UpdateQuery("second"))
            viewModel.onAction(SearchAction.Search("second"))
            secondStarted.await()

            firstResponse.complete(available(listOf(folder("first")), contentGeneration = 1L))
            secondResponse.complete(available(listOf(folder("second")), contentGeneration = 2L))
            val finalState =
                viewModel.state.first {
                    it.query == "second" && !it.loading && it.results.isNotEmpty()
                }
            mainDispatcher.scheduler.runCurrent()
            collector.cancel()

            assertEquals(listOf("second"), finalState.serverItems.map { it.name })
            assertEquals(listOf(listOf("second")), acceptedSequences)
            assertFalse(
                "first must never enter a non-loading accepted sequence: $acceptedSequences",
                acceptedSequences.any { sequence -> sequence.contains("first") },
            )
        }

    @Test
    fun `请求开始后切号时冻结 scope 不改变且 consumer 不提交结果`() =
        runTest(mainDispatcher.scheduler) {
            val currentScope = AtomicReference<CatalogScope?>(ALICE_SCOPE)
            val requestStarted = CompletableDeferred<Unit>()
            val response = CompletableDeferred<CatalogReadOutcome<List<FindroidItem>>>()
            var frozenScope: CatalogScope? = null
            val viewModel =
                viewModel(
                    currentScope = currentScope::get,
                    onServerSearch = { query ->
                        frozenScope = query.frozenScope
                        requestStarted.complete(Unit)
                        response.await()
                    },
                )
            viewModel.onAction(SearchAction.UpdateQuery("sample"))
            viewModel.onAction(SearchAction.Search("sample"))
            requestStarted.await()
            currentScope.set(BOB_SCOPE)
            response.complete(available(listOf(folder("alice-result"))))
            val finalState =
                viewModel.state.first { it.query == "sample" && !it.loading && it.serverFailed }

            assertEquals(ALICE_SCOPE, frozenScope)
            assertTrue(finalState.serverItems.isEmpty())
            assertTrue(finalState.results.isEmpty())
        }

    @Test
    fun `同 query 完成态重触发跨 scope 读取时失败并清空旧结果`() =
        runTest(mainDispatcher.scheduler) {
            val queuedScopes = mutableListOf<CatalogScope?>()
            var useQueuedScopes = false
            var scopeReads = 0
            var serverSearchCalls = 0
            var localSearchCalls = 0
            var downloadCalls = 0
            val viewModel =
                viewModel(
                    currentScope = {
                        scopeReads += 1
                        if (useQueuedScopes) queuedScopes.removeAt(0) else ALICE_SCOPE
                    },
                    onServerSearch = {
                        serverSearchCalls += 1
                        available(listOf(folder("sample-server")))
                    },
                    onLocalSearch = {
                        localSearchCalls += 1
                        listOf(localHit("sample-local"))
                    },
                    onDownloads = {
                        downloadCalls += 1
                        listOf(downloadRecord("sample-download"))
                    },
                )

            viewModel.onAction(SearchAction.UpdateQuery("sample"))
            viewModel.onAction(SearchAction.Search("sample"))
            val completedState =
                viewModel.state.first {
                    it.query == "sample" && !it.loading && it.results.size == 3
                }
            viewModel.currentJob?.join()

            assertEquals(
                listOf("sample-server"),
                completedState.serverItems.map { it.name },
            )
            assertEquals(
                listOf("sample-local"),
                completedState.localItems.map { it.entry.name },
            )
            assertEquals(
                1,
                completedState.results.filterIsInstance<SearchResult.Download>().size,
            )

            queuedScopes += ALICE_SCOPE
            queuedScopes += BOB_SCOPE
            useQueuedScopes = true
            viewModel.onAction(SearchAction.Search("sample"))
            viewModel.currentJob?.join()
            val finalState = viewModel.state.value

            assertEquals(5, scopeReads)
            assertEquals(1, serverSearchCalls)
            assertEquals(1, localSearchCalls)
            assertEquals(1, downloadCalls)
            assertTrue(finalState.serverItems.isEmpty())
            assertTrue(finalState.localItems.isEmpty())
            assertTrue(finalState.results.filterIsInstance<SearchResult.Download>().isEmpty())
            assertTrue(finalState.results.isEmpty())
            assertTrue(finalState.serverFailed)
            assertTrue(finalState.localFailed)
        }

    @Test
    fun `completed marker includes identity generation`() =
        runTest(mainDispatcher.scheduler) {
            var scope = ALICE_SCOPE
            var serverSearchCalls = 0
            val viewModel =
                viewModel(
                    currentScope = { scope },
                    onServerSearch = {
                        serverSearchCalls += 1
                        available(
                            items = listOf(folder("generation-${scope.identityGeneration}")),
                            scope = scope,
                        )
                    },
                )

            viewModel.onAction(SearchAction.UpdateQuery("sample"))
            viewModel.onAction(SearchAction.Search("sample"))
            viewModel.state.first { it.query == "sample" && !it.loading && it.results.isNotEmpty() }
            viewModel.currentJob?.join()

            scope = ALICE_SCOPE.copy(identityGeneration = 2L)
            viewModel.onAction(SearchAction.Search("sample"))
            viewModel.currentJob?.join()

            assertEquals(2, serverSearchCalls)
            assertEquals(
                listOf("generation-2"),
                viewModel.state.value.serverItems.map { it.name },
            )
        }

    @Test
    fun `settings and offline UI write offline preference only through the port`() =
        runTest(mainDispatcher.scheduler) {
            val writes = mutableListOf<Boolean>()
            val writePort =
                object : OfflineModeWritePort {
                    override suspend fun setOfflineMode(enabled: Boolean) {
                        writes += enabled
                    }
                }
            var editCalls = 0
            val rawPreferences =
                Proxy.newProxyInstance(
                    SharedPreferences::class.java.classLoader,
                    arrayOf(SharedPreferences::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "getString" -> null
                        "getBoolean" -> false
                        "edit" -> {
                            editCalls += 1
                            editorProxy()
                        }
                        else -> defaultValue(method.returnType)
                    }
                } as SharedPreferences
            val appPreferences = AppPreferences(rawPreferences)

            val offlineViewModel = OfflineModeViewModel(writePort)
            offlineViewModel.enterOfflineMode()
            offlineViewModel.exitOfflineMode()

            val settingsViewModel =
                SettingsViewModel(
                    appPreferences = appPreferences,
                    context = ContextWrapper(null),
                    offlineModeWritePort = writePort,
                )
            editCalls = 0
            settingsViewModel.onAction(
                SettingsAction.OnUpdate(
                    PreferenceSwitch(
                        nameStringResource = 0,
                        backendPreference = appPreferences.offlineMode,
                        value = true,
                    )
                )
            )
            mainDispatcher.scheduler.runCurrent()

            assertEquals(listOf(true, false, true), writes)
            assertEquals(0, editCalls)
        }

    @Test
    fun `W1A-P3-COMPLETED-MARKER 跨 scope 失败后返回原 scope 必须重新搜索`() =
        runTest(mainDispatcher.scheduler) {
            val queuedScopes = mutableListOf<CatalogScope>()
            var scopeReads = 0
            var serverSearchCalls = 0
            var localSearchCalls = 0
            var downloadCalls = 0
            val viewModel =
                viewModel(
                    currentScope = {
                        scopeReads += 1
                        if (queuedScopes.isNotEmpty()) {
                            queuedScopes.removeAt(0)
                        } else {
                            ALICE_SCOPE
                        }
                    },
                    onServerSearch = {
                        serverSearchCalls += 1
                        available(listOf(folder("sample-server")))
                    },
                    onLocalSearch = {
                        localSearchCalls += 1
                        listOf(localHit("sample-local"))
                    },
                    onDownloads = {
                        downloadCalls += 1
                        listOf(downloadRecord("sample-download"))
                    },
                )

            viewModel.onAction(SearchAction.UpdateQuery("sample"))
            viewModel.onAction(SearchAction.Search("sample"))
            viewModel.state.first { it.query == "sample" && !it.loading && it.results.size == 3 }
            viewModel.currentJob?.join()

            queuedScopes += ALICE_SCOPE
            queuedScopes += BOB_SCOPE
            viewModel.onAction(SearchAction.Search("sample"))
            viewModel.currentJob?.join()
            val crossScopeFailure = viewModel.state.value

            assertEquals(5, scopeReads)
            assertEquals(1, serverSearchCalls)
            assertEquals(1, localSearchCalls)
            assertEquals(1, downloadCalls)
            assertTrue(crossScopeFailure.serverFailed)
            assertTrue(crossScopeFailure.localFailed)
            assertTrue(crossScopeFailure.results.isEmpty())

            viewModel.onAction(SearchAction.Search("sample"))
            viewModel.currentJob?.join()
            val recoveredState = viewModel.state.value

            assertEquals(8, scopeReads)
            assertEquals(2, serverSearchCalls)
            assertEquals(2, localSearchCalls)
            assertEquals(2, downloadCalls)
            assertFalse(recoveredState.serverFailed)
            assertFalse(recoveredState.localFailed)
            assertEquals(
                listOf("sample-server"),
                recoveredState.serverItems.map { it.name },
            )
            assertEquals(
                listOf("sample-local"),
                recoveredState.localItems.map { it.entry.name },
            )
        }

    @Test
    fun `同 scope 同 query 完成态重触发仍走 early return`() =
        runTest(mainDispatcher.scheduler) {
            var scopeReads = 0
            var serverSearchCalls = 0
            var localSearchCalls = 0
            var downloadCalls = 0
            val viewModel =
                viewModel(
                    currentScope = {
                        scopeReads += 1
                        ALICE_SCOPE
                    },
                    onServerSearch = {
                        serverSearchCalls += 1
                        available(listOf(folder("sample-server")))
                    },
                    onLocalSearch = {
                        localSearchCalls += 1
                        emptyList()
                    },
                    onDownloads = {
                        downloadCalls += 1
                        emptyList()
                    },
                )

            viewModel.onAction(SearchAction.UpdateQuery("sample"))
            viewModel.onAction(SearchAction.Search("sample"))
            viewModel.state.first { it.query == "sample" && !it.loading && it.results.isNotEmpty() }
            viewModel.currentJob?.join()

            viewModel.onAction(SearchAction.Search("sample"))
            viewModel.currentJob?.join()

            assertEquals(5, scopeReads)
            assertEquals(1, serverSearchCalls)
            assertEquals(1, localSearchCalls)
            assertEquals(1, downloadCalls)
            assertEquals(
                listOf("sample-server"),
                viewModel.state.value.serverItems.map { it.name },
            )
        }

    @Test
    fun `无身份时不得发起搜索并以可解释失败结束`() =
        runTest(mainDispatcher.scheduler) {
            var searchCalls = 0
            val viewModel =
                viewModel(
                    scope = null,
                    onServerSearch = {
                        searchCalls += 1
                        available(emptyList())
                    },
                )

            viewModel.onAction(SearchAction.UpdateQuery("sample"))
            viewModel.onAction(SearchAction.Search("sample"))
            val state =
                viewModel.state.first {
                    it.query == "sample" && !it.loading && it.serverFailed && it.localFailed
                }

            assertEquals(0, searchCalls)
            assertTrue(state.serverFailed)
            assertTrue(state.localFailed)
            assertTrue(state.results.isEmpty())
        }

    private fun viewModel(
        scope: CatalogScope? = ALICE_SCOPE,
        currentScope: () -> CatalogScope? = { scope },
        onServerSearch: suspend (CatalogSearchQuery) -> CatalogReadOutcome<List<FindroidItem>> = {
            available(emptyList())
        },
        onLocalSearch: () -> List<LocalSearchHit> = { emptyList() },
        onDownloads: () -> List<DownloadMediaRecord> = { emptyList() },
    ): SearchViewModel =
        SearchViewModel(
            catalogReadPort =
                FakeCatalogReadPort(
                    resolveCurrentScope = currentScope,
                    onSearch = onServerSearch,
                ),
            localLibraryRepository =
                proxy(LocalLibraryRepository::class.java) { method, _ ->
                    when (method.name) {
                        "search" -> onLocalSearch()
                        else -> defaultValue(method.returnType)
                    }
                },
            downloader =
                proxy(Downloader::class.java) { method, _ ->
                    when (method.name) {
                        "downloadedMediaRecords" -> onDownloads()
                        else -> defaultValue(method.returnType)
                    }
                },
            savedStateHandle = SavedStateHandle(),
        )

    private fun available(
        items: List<FindroidItem>,
        contentGeneration: Long = 1L,
        scope: CatalogScope = ALICE_SCOPE,
    ): CatalogReadOutcome<List<FindroidItem>> =
        CatalogReadOutcome.Available(
            CatalogSnapshot(
                scope = scope,
                contentGeneration = contentGeneration,
                freshness = CatalogFreshness.FRESH,
                observedAtEpochMs = 1L,
                value = items,
            )
        )

    private fun localHit(name: String): LocalSearchHit =
        LocalSearchHit(
            entry =
                LocalLibraryEntry(
                    itemId = UUID.nameUUIDFromBytes(name.toByteArray()),
                    folderId = 1L,
                    name = name,
                    kind = LocalMediaKind.VIDEO,
                    documentUri = "content://test/$name",
                    relativePath = name,
                    sizeBytes = 1L,
                    lastModified = 0L,
                ),
            folderLabel = "Test",
            libraryId = 1L,
        )

    private fun downloadRecord(name: String): DownloadMediaRecord =
        DownloadMediaRecord(
            itemId = UUID.nameUUIDFromBytes(name.toByteArray()).toString(),
            kind = DownloadMediaKind.VIDEO,
            serverId = SERVER_ID,
            userId = USER_ID.toString(),
            sourceId = "source-a",
            displayName = name,
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

    private inline fun <reified T> proxy(
        type: Class<T>,
        crossinline result: (Method, Array<out Any?>) -> Any?,
    ): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
            when (method.name) {
                "equals" -> instance === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "Test${type.simpleName}"
                else -> result(method, args.orEmpty())
            }
        } as T

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

    private fun editorProxy(): SharedPreferences.Editor {
        lateinit var editor: SharedPreferences.Editor
        editor =
            Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, _ ->
                if (method.returnType.isAssignableFrom(SharedPreferences.Editor::class.java)) {
                    editor
                } else {
                    defaultValue(method.returnType)
                }
            } as SharedPreferences.Editor
        return editor
    }

    private class FakeCatalogReadPort(
        private val resolveCurrentScope: () -> CatalogScope?,
        private val onSearch:
            suspend (CatalogSearchQuery) -> CatalogReadOutcome<List<FindroidItem>>,
    ) : CatalogReadPort {
        override suspend fun currentScope(): CatalogScope? = resolveCurrentScope()

        override suspend fun search(
            query: CatalogSearchQuery
        ): CatalogReadOutcome<List<FindroidItem>> = onSearch(query)
    }

    private companion object {
        const val SERVER_ID = "server-a"
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val ALICE_SCOPE =
            CatalogScope(
                origin = JellyfinOrigin(SERVER_ID, USER_ID),
                principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
                identityGeneration = 1L,
            )
        val BOB_SCOPE =
            CatalogScope(
                origin =
                    JellyfinOrigin(
                        SERVER_ID,
                        UUID.fromString("44444444-4444-4444-8444-444444444444"),
                    ),
                principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
                identityGeneration = 2L,
            )
    }
}
