package com.zhangwenkang.cinefin.presentation.film

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.film.domain.VideoMetadataParser
import com.zhangwenkang.cinefin.film.presentation.detail.DetailWriteEvent
import com.zhangwenkang.cinefin.film.presentation.episode.EpisodeViewModel
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.repository.ItemMetadataSnapshot
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.UserDataWriteFailure
import com.zhangwenkang.cinefin.repository.UserDataWriteOutcome
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `optional episode enrichment failure keeps core episode visible`() = runBlocking {
        val episodeId = UUID.randomUUID()
        val viewModel =
            EpisodeViewModel(
                repository = repository(episode(episodeId)),
                appPreferences = failingPreferences(),
                videoMetadataParser = VideoMetadataParser,
            )

        viewModel.loadEpisode(episodeId)
        val settled = withTimeout(5_000) { viewModel.state.first { state -> state.error != null } }

        assertEquals(episodeId, settled.episode?.id)
        assertEquals("prefs failed", settled.error?.message)
    }

    @Test
    fun `删除成功映射为 Confirmed 供屏幕关闭详情页`() = runBlocking {
        val episodeId = UUID.randomUUID()
        val viewModel = deletingViewModel(episodeId, UserDataWriteOutcome.Confirmed)

        viewModel.deleteItem()
        val event = withTimeout(5_000) { viewModel.deleteEvents.first() }

        assertEquals(DetailWriteEvent.Confirmed, event)
    }

    @Test
    fun `删除权限拒绝映射为 PermissionDenied`() = runBlocking {
        val episodeId = UUID.randomUUID()
        val viewModel =
            deletingViewModel(
                episodeId,
                UserDataWriteOutcome.Failed(UserDataWriteFailure.PermissionDenied(403, null)),
            )

        viewModel.deleteItem()
        val event = withTimeout(5_000) { viewModel.deleteEvents.first() }

        assertEquals(DetailWriteEvent.PermissionDenied, event)
    }

    @Test
    fun `删除其他失败映射为 Failed`() = runBlocking {
        val episodeId = UUID.randomUUID()
        val viewModel =
            deletingViewModel(
                episodeId,
                UserDataWriteOutcome.Failed(UserDataWriteFailure.Unknown(null, "boom")),
            )

        viewModel.deleteItem()
        val event = withTimeout(5_000) { viewModel.deleteEvents.first() }

        assertEquals(DetailWriteEvent.Failed, event)
    }

    @Test
    fun `保存元数据成功映射为 Confirmed 并重载详情`() = runBlocking {
        val episodeId = UUID.randomUUID()
        val loadCount = AtomicInteger(0)
        val viewModel =
            metadataViewModel(
                episodeId = episodeId,
                updateOutcome = UserDataWriteOutcome.Confirmed,
                loadCount = loadCount,
            )

        viewModel.saveMetadata(name = "新名称", overview = "新简介", sortName = null)
        val event = withTimeout(5_000) { viewModel.metadataEvents.first() }

        assertEquals(DetailWriteEvent.Confirmed, event)
        assertEquals(1, loadCount.get())
    }

    @Test
    fun `保存元数据权限拒绝映射为 PermissionDenied 且不重载详情`() = runBlocking {
        val episodeId = UUID.randomUUID()
        val loadCount = AtomicInteger(0)
        val viewModel =
            metadataViewModel(
                episodeId = episodeId,
                updateOutcome =
                    UserDataWriteOutcome.Failed(UserDataWriteFailure.PermissionDenied(403, null)),
                loadCount = loadCount,
            )

        viewModel.saveMetadata(name = "新名称", overview = null, sortName = null)
        val event = withTimeout(5_000) { viewModel.metadataEvents.first() }

        assertEquals(DetailWriteEvent.PermissionDenied, event)
        assertEquals(0, loadCount.get())
    }

    @Test
    fun `保存元数据其他失败映射为 Failed`() = runBlocking {
        val episodeId = UUID.randomUUID()
        val viewModel =
            metadataViewModel(
                episodeId = episodeId,
                updateOutcome =
                    UserDataWriteOutcome.Failed(UserDataWriteFailure.Unknown(null, "boom")),
                loadCount = AtomicInteger(0),
            )

        viewModel.saveMetadata(name = "新名称", overview = null, sortName = null)
        val event = withTimeout(5_000) { viewModel.metadataEvents.first() }

        assertEquals(DetailWriteEvent.Failed, event)
    }

    private fun metadataViewModel(
        episodeId: UUID,
        updateOutcome: UserDataWriteOutcome,
        loadCount: AtomicInteger,
    ): EpisodeViewModel =
        EpisodeViewModel(
                repository = metadataRepository(episode(episodeId), updateOutcome, loadCount),
                appPreferences = failingPreferences(),
                videoMetadataParser = VideoMetadataParser,
            )
            .also { viewModel -> viewModel.episodeId = episodeId }

    private fun metadataRepository(
        episode: FindroidEpisode,
        updateOutcome: UserDataWriteOutcome,
        loadCount: AtomicInteger,
    ): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getEpisode" -> {
                    loadCount.incrementAndGet()
                    episode
                }
                "updateItemMetadata" -> updateOutcome
                "fetchItemMetadataSnapshot" ->
                    ItemMetadataSnapshot(
                        itemId = episode.id,
                        name = episode.name,
                        overview = episode.overview,
                        sortName = null,
                    )
                "toString" -> "EpisodeViewModelMetadataTestRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw AssertionError("Unexpected repository call: ${method.name}")
            }
        } as JellyfinRepository

    private fun deletingViewModel(
        episodeId: UUID,
        outcome: UserDataWriteOutcome,
    ): EpisodeViewModel =
        EpisodeViewModel(
                repository = repository(episode(episodeId), deleteOutcome = outcome),
                appPreferences = failingPreferences(),
                videoMetadataParser = VideoMetadataParser,
            )
            .also { viewModel -> viewModel.episodeId = episodeId }

    private fun repository(
        episode: FindroidEpisode,
        deleteOutcome: UserDataWriteOutcome? = null,
    ): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getEpisode" -> episode
                "deleteItem" -> deleteOutcome ?: throw AssertionError("Unexpected deleteItem call")
                "toString" -> "EpisodeViewModelTestRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw AssertionError("Unexpected repository call: ${method.name}")
            }
        } as JellyfinRepository

    private fun failingPreferences(): AppPreferences {
        val preferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "getBoolean",
                    "edit" -> throw IllegalStateException("prefs failed")
                    "toString" -> "EpisodeViewModelTestPreferences"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> throw AssertionError("Unexpected preference call: ${method.name}")
                }
            } as SharedPreferences
        return AppPreferences(preferences)
    }

    private fun episode(id: UUID): FindroidEpisode =
        FindroidEpisode(
            id = id,
            name = "core episode",
            originalTitle = null,
            overview = "",
            indexNumber = 1,
            indexNumberEnd = null,
            parentIndexNumber = 1,
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesId = UUID.randomUUID(),
            seriesName = "show",
            seasonId = UUID.randomUUID(),
            seasonName = null,
            communityRating = null,
            people = emptyList(),
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )
}
