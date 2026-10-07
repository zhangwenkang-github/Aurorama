package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W76-Q6：后台补队列的「请求瘦身」保底——播放信息解析的请求去重 / 结果复用。
 *
 * 回归口径：去重过头（把不同起播位置的请求并成一次）会把「从 0 起播」接到「从 25 分钟起播」的转码地址上；去重不足 （同键并发各打一次）就是本任务要消掉的重复请求。
 */
class PlaybackRequestCacheTest {

    private val episodeId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val otherEpisodeId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun playerItem(itemId: UUID, positionMs: Long = 0L): PlayerItem =
        PlayerItem(
            name = "E01",
            itemId = itemId,
            mediaSourceId = itemId.toString(),
            playbackPosition = positionMs,
            mediaSourceUri = "https://host/Videos/$itemId/stream",
        )

    @Test
    fun `同一条目同一位置只解析一次`() = runBlocking {
        val cache = PlaybackRequestCache()
        var loads = 0
        val key = playbackRequestKey(episodeId, mediaSourceIndex = null, startPositionTicks = 0L)
        val loader: suspend () -> PlayerItem = {
            loads++
            playerItem(episodeId)
        }

        val first = cache.resolve(key, loader = loader)
        val second = cache.resolve(key, loader = loader)

        assertEquals("同键第二次必须命中缓存，不再请求 PlaybackInfo", 1, loads)
        assertSame("复用同一份已解析结果", first, second)
        assertEquals(PlaybackRequestStats(requests = 1, hits = 1), cache.stats())
    }

    @Test
    fun `同键并发请求合并成一次解析`() = runBlocking {
        val cache = PlaybackRequestCache()
        var loads = 0
        val gate = CompletableDeferred<Unit>()
        val key = playbackRequestKey(episodeId, mediaSourceIndex = null, startPositionTicks = 0L)
        val loader: suspend () -> PlayerItem = {
            loads++
            // 卡住第一个调用者：模拟「后台补片」与「切集预取」同时解析同一条目的窗口
            gate.await()
            playerItem(episodeId)
        }

        val first = async { cache.resolve(key, loader = loader) }
        yield()
        val second = async { cache.resolve(key, loader = loader) }
        yield()
        gate.complete(Unit)

        val results = listOf(first, second).awaitAll()
        assertEquals("同键并发只允许一次真实请求", 1, loads)
        assertSame(results[0], results[1])
    }

    @Test
    fun `不同条目各解析一次`() = runBlocking {
        val cache = PlaybackRequestCache()
        var loads = 0
        val loader: suspend (UUID) -> PlayerItem = { id ->
            loads++
            playerItem(id)
        }

        cache.resolve(
            playbackRequestKey(episodeId, null, 0L),
            loader = { loader(episodeId) },
        )
        cache.resolve(
            playbackRequestKey(otherEpisodeId, null, 0L),
            loader = { loader(otherEpisodeId) },
        )

        assertEquals("不同条目不得互相顶替", 2, loads)
        assertEquals(PlaybackRequestStats(requests = 2, hits = 0), cache.stats())
    }

    @Test
    fun `同一条目不同起播位置不共用结果`() = runBlocking {
        val cache = PlaybackRequestCache()
        var loads = 0
        val loader: suspend () -> PlayerItem = {
            loads++
            playerItem(episodeId)
        }

        cache.resolve(
            playbackRequestKey(episodeId, null, startPositionTicks = 0L),
            loader = loader,
        )
        cache.resolve(
            playbackRequestKey(episodeId, null, startPositionTicks = 25L * 60L * 10_000_000L),
            loader = loader,
        )

        assertEquals("转码会话地址与 startTimeTicks 绑定：不同位置必须各请求一次", 2, loads)
        assertNotEquals(
            playbackRequestKey(episodeId, null, 0L),
            playbackRequestKey(episodeId, null, 25L * 60L * 10_000_000L),
        )
    }

    @Test
    fun `缓存的键把负位置归一为从零起播`() {
        assertEquals(
            playbackRequestKey(episodeId, null, 0L),
            playbackRequestKey(episodeId, null, -1L),
        )
    }

    @Test
    fun `强制刷新绕过缓存并覆盖旧结果`() = runBlocking {
        val cache = PlaybackRequestCache()
        var loads = 0
        val key = playbackRequestKey(episodeId, null, 0L)
        val loader: suspend () -> PlayerItem = {
            loads++
            playerItem(episodeId, positionMs = loads.toLong())
        }

        val cached = cache.resolve(key, loader = loader)
        val rebuilt = cache.resolve(key, forceRefresh = true, loader = loader)
        val reused = cache.resolve(key, loader = loader)

        assertEquals("转码 seek 重开会话必须重新请求", 2, loads)
        assertNotSame(cached, rebuilt)
        assertSame("覆盖后的新结果才是后续复用的那一份", rebuilt, reused)
    }

    @Test
    fun `解析失败不进缓存可以重试`() = runBlocking {
        val cache = PlaybackRequestCache()
        var loads = 0
        val key = playbackRequestKey(episodeId, null, 0L)
        val failing: suspend () -> PlayerItem = {
            loads++
            throw IllegalStateException("PlaybackInfo 失败")
        }

        val failure = runCatching { cache.resolve(key, loader = failing) }
        val recovered =
            cache.resolve(key) {
                loads++
                playerItem(episodeId)
            }

        assertTrue("解析失败必须原样抛出，由调用方降级", failure.isFailure)
        assertEquals("失败不落缓存：下一次仍会重新解析", 2, loads)
        assertEquals(episodeId, recovered.itemId)
        assertEquals(1, cache.stats().requests)
    }
}
