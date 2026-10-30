package com.zhangwenkang.cinefin.presentation.search

import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import com.zhangwenkang.cinefin.film.presentation.search.SearchAction
import com.zhangwenkang.cinefin.film.presentation.search.SearchViewModel
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.models.FindroidFolder
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.Downloader
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
                    onServerSearch = { query, continuation ->
                        serverThread.complete(Thread.currentThread().name)
                        continuation.resume(listOf(folder(query)))
                        COROUTINE_SUSPENDED
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
            val firstCall = CompletableDeferred<Continuation<List<FindroidItem>>>()
            val secondCall = CompletableDeferred<Continuation<List<FindroidItem>>>()
            val viewModel =
                viewModel(
                    onServerSearch = { query, continuation ->
                        when (query) {
                            "first" -> firstCall.complete(continuation)
                            "second" -> secondCall.complete(continuation)
                        }
                        COROUTINE_SUSPENDED
                    }
                )

            viewModel.onAction(SearchAction.UpdateQuery("first"))
            viewModel.onAction(SearchAction.Search("first"))
            val pendingFirst = firstCall.await()
            viewModel.onAction(SearchAction.UpdateQuery("second"))
            viewModel.onAction(SearchAction.Search("second"))
            val pendingSecond = secondCall.await()

            pendingFirst.resume(listOf(folder("first")))
            pendingSecond.resume(listOf(folder("second")))
            val finalState =
                viewModel.state.first {
                    it.query == "second" && !it.loading && it.results.isNotEmpty()
                }

            assertEquals(listOf("second"), finalState.serverItems.map { it.name })
        }

    private fun viewModel(
        onServerSearch:
            (
                query: String,
                continuation: Continuation<List<FindroidItem>>,
            ) -> Any? =
            { _, continuation ->
                continuation.resume(emptyList())
                COROUTINE_SUSPENDED
            },
        onLocalSearch: () -> List<LocalSearchHit> = { emptyList() },
    ): SearchViewModel =
        SearchViewModel(
            repository =
                proxy(JellyfinRepository::class.java) { method, args ->
                    when (method.name) {
                        "getUserId" -> USER_ID
                        "getSearchItems" -> {
                            @Suppress("UNCHECKED_CAST")
                            onServerSearch(
                                args.first() as String,
                                args.last() as Continuation<List<FindroidItem>>,
                            )
                        }
                        else -> defaultValue(method.returnType)
                    }
                },
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
                        "downloadedMediaRecords" -> emptyList<Any>()
                        else -> defaultValue(method.returnType)
                    }
                },
            appPreferences = AppPreferences(sharedPreferences()),
            savedStateHandle = SavedStateHandle(),
        )

    private fun sharedPreferences(): SharedPreferences =
        proxy(SharedPreferences::class.java) { method, args ->
            when (method.name) {
                "getString" ->
                    if (args.firstOrNull() == "pref_current_server") SERVER_ID
                    else args.getOrNull(1)
                else -> defaultValue(method.returnType)
            }
        }

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

    private companion object {
        const val SERVER_ID = "server-a"
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}
