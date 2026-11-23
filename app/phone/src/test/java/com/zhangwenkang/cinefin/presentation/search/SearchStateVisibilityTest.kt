package com.zhangwenkang.cinefin.presentation.search

import androidx.lifecycle.SavedStateHandle
import com.zhangwenkang.cinefin.film.presentation.search.SearchAction
import com.zhangwenkang.cinefin.film.presentation.search.SearchCategory
import com.zhangwenkang.cinefin.film.presentation.search.SearchViewModel
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.repository.JellyfinOrigin
import com.zhangwenkang.cinefin.repository.catalog.CatalogFreshness
import com.zhangwenkang.cinefin.repository.catalog.CatalogPrincipalContext
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadOutcome
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadPort
import com.zhangwenkang.cinefin.repository.catalog.CatalogScope
import com.zhangwenkang.cinefin.repository.catalog.CatalogSearchQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogSnapshot
import com.zhangwenkang.cinefin.utils.Downloader
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * W9-T3 回归：状态层不是「结果区空白」的原因——服务器返回数据时，终态 results 必须非空， 且落在入口 scope 可见的分区里（音乐入口 = MUSIC scope + 服务器
 * Audio 条目）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchStateVisibilityTest {
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
    fun `音乐入口搜索 Audio 结果非空且在当前 scope 可见`() =
        runTest(mainDispatcher.scheduler) {
            val viewModel =
                viewModel(
                    initialScope = "MUSIC",
                    onServerSearch = { available(listOf(audioItem("Nirvana - Lithium"))) },
                )

            viewModel.onAction(SearchAction.UpdateQuery("Nirvana"))
            viewModel.onAction(SearchAction.Search("Nirvana"))
            val state =
                viewModel.state.first {
                    it.query == "Nirvana" && !it.loading && it.results.isNotEmpty()
                }

            assertFalse(state.hasFailure)
            assertEquals(1, state.results.size)
            assertEquals(SearchCategory.MUSIC, state.results.single().category)
            assertEquals(listOf(SearchCategory.MUSIC), state.visibleCategories)
            assertEquals(1, state.resultsIn(SearchCategory.MUSIC).size)
            assertEquals(
                listOf("Nirvana - Lithium"),
                state.serverItemsIn(SearchCategory.MUSIC).map { it.name },
            )
        }

    private fun viewModel(
        initialScope: String,
        onServerSearch: suspend (CatalogSearchQuery) -> CatalogReadOutcome<List<FindroidItem>>,
    ): SearchViewModel =
        SearchViewModel(
            catalogReadPort =
                object : CatalogReadPort {
                    override suspend fun currentScope(): CatalogScope? = ALICE_SCOPE

                    override suspend fun search(
                        query: CatalogSearchQuery
                    ): CatalogReadOutcome<List<FindroidItem>> = onServerSearch(query)
                },
            localLibraryRepository = proxy(LocalLibraryRepository::class.java) { _, _ -> null },
            downloader = proxy(Downloader::class.java) { _, _ -> null },
            savedStateHandle = SavedStateHandle(mapOf("initialScope" to initialScope)),
        )

    private fun available(items: List<FindroidItem>): CatalogReadOutcome<List<FindroidItem>> =
        CatalogReadOutcome.Available(
            CatalogSnapshot(
                scope = ALICE_SCOPE,
                contentGeneration = 1L,
                freshness = CatalogFreshness.FRESH,
                observedAtEpochMs = 1L,
                value = items,
            )
        )

    private fun audioItem(name: String): FindroidMovie =
        FindroidMovie(
            id = UUID.nameUUIDFromBytes(name.toByteArray()),
            name = name,
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            people = emptyList(),
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            status = "Ended",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
            kind = "AUDIO",
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
                "downloadedMediaRecords",
                "search" -> emptyList<Any>()
                else -> result(method, args.orEmpty())
            }
        } as T

    private companion object {
        const val SERVER_ID = "server-a"
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val ALICE_SCOPE =
            CatalogScope(
                origin = JellyfinOrigin(SERVER_ID, USER_ID),
                principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
                identityGeneration = 1L,
            )
    }
}
