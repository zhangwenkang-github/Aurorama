package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.0.3-A Bug 11：「该条目不是可播放的剧集」不再把条目钉死。
 *
 * 服务器把条目归成 Video（家庭视频 / 无剧集归属）时 `getEpisode` 会 fail-closed；这类条目本身可播，
 * 单集分支必须按单片回落，否则切内核失败后的兜底重启每次都重进同一个失败分支。
 */
class PlaylistManagerEpisodeFallbackTest {

    @Test
    fun `episode mapping failure falls back to the movie branch`() = runBlocking {
        var getMovieCalls = 0
        val manager =
            PlaylistManager(
                repository { method, args ->
                    when (method) {
                        "getEpisode" -> {
                            throw IllegalStateException("该条目不是可播放的剧集")
                        }
                        "getMovie" -> {
                            getMovieCalls += 1
                            movie(itemId = ITEM_ID, sourceId = SOURCE_ID)
                        }
                        "getMediaSources" -> listOf(source(SOURCE_ID))
                        else -> error("Unexpected repository call: $method")
                    }
                }
            )

        val item =
            manager.getInitialItem(
                itemId = ITEM_ID,
                itemKind = BaseItemKind.EPISODE,
            )

        assertEquals(ITEM_ID, item?.itemId)
        assertEquals(1, getMovieCalls)
        // 单片没有季集语义：不能再排「整剧补全」
        assertNull(manager.pendingSeriesQueueExpansion)
    }

    @Test
    fun `episode and movie both failing keeps the original failure`() = runBlocking {
        val manager =
            PlaylistManager(
                repository { method, _ ->
                    when (method) {
                        "getEpisode" -> throw IllegalStateException("该条目不是可播放的剧集")
                        "getMovie" -> throw IllegalStateException("movie lookup failed")
                        else -> error("Unexpected repository call: $method")
                    }
                }
            )

        val failure = runCatching {
            manager.getInitialItem(itemId = ITEM_ID, itemKind = BaseItemKind.EPISODE)
        }
            .exceptionOrNull()

        assertTrue(
            "expected IllegalStateException, got ${failure?.javaClass?.name}",
            failure is IllegalStateException,
        )
        assertEquals("该条目不是可播放的剧集", failure?.message)
    }

    @Test
    fun `non mapping failure on the episode branch does not retry as movie`() = runBlocking {
        var getMovieCalls = 0
        val manager =
            PlaylistManager(
                repository { method, _ ->
                    when (method) {
                        // 非映射失败（网络 / 鉴权等）：换解析路径救不了，原样抛给上层错误卡片
                        "getEpisode" -> throw RuntimeException("network timeout")
                        "getMovie" -> {
                            getMovieCalls += 1
                            movie(itemId = ITEM_ID, sourceId = SOURCE_ID)
                        }
                        else -> error("Unexpected repository call: $method")
                    }
                }
            )

        val failure = runCatching {
            manager.getInitialItem(itemId = ITEM_ID, itemKind = BaseItemKind.EPISODE)
        }
            .exceptionOrNull()

        assertTrue(
            "expected RuntimeException, got ${failure?.javaClass?.name}",
            failure is RuntimeException,
        )
        assertEquals(0, getMovieCalls)
    }

    @Test
    fun `explicit queue episode entry falls back to the movie branch`() = runBlocking {
        val manager =
            PlaylistManager(
                repository { method, _ ->
                    when (method) {
                        "getEpisode" -> throw IllegalStateException("该条目不是可播放的剧集")
                        "getMovie" -> movie(itemId = ITEM_ID, sourceId = SOURCE_ID)
                        "getMediaSources" -> listOf(source(SOURCE_ID))
                        else -> error("Unexpected repository call: $method")
                    }
                }
            )

        val item =
            manager.getInitialItemForQueue(
                entries = listOf(PlaybackQueueEntry(itemId = ITEM_ID, kind = BaseItemKind.EPISODE))
            )

        assertEquals(ITEM_ID, item?.itemId)
    }

    private fun repository(
        handler: (method: String, args: Array<out Any?>?) -> Any?
    ): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "PlaylistManagerEpisodeFallbackTestRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> handler(method.name, args)
            }
        } as JellyfinRepository

    private fun movie(itemId: UUID, sourceId: String): FindroidMovie =
        FindroidMovie(
            id = itemId,
            name = "home video",
            originalTitle = null,
            overview = "",
            sources = listOf(source(sourceId)),
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
            status = "Released",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = emptyMap(),
            kind = BaseItemKind.VIDEO.name,
        )

    private fun source(id: String): FindroidSource =
        FindroidSource(
            id = id,
            name = "home-video.mp4",
            type = FindroidSourceType.REMOTE,
            path = "https://example.invalid/home-video.mp4",
            size = 1L,
            mediaStreams = emptyList(),
        )

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("77777777-7777-4777-8777-777777777777")
        const val SOURCE_ID = "video-source"
    }
}
