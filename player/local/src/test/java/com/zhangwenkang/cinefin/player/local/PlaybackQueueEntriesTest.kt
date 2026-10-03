package com.zhangwenkang.cinefin.player.local

import com.zhangwenkang.cinefin.player.local.domain.parsePlaybackQueueEntries
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W58b 视频批量播放：Intent 队列 extra 解析（id + 类型并行数组）单测。 */
class PlaybackQueueEntriesTest {

    private val movieId = "11111111-1111-1111-1111-111111111111"
    private val episodeId = "22222222-2222-2222-2222-222222222222"

    @Test
    fun `pairs ids with kinds in order`() {
        val entries =
            parsePlaybackQueueEntries(
                ids = listOf(movieId, episodeId),
                kinds = listOf(BaseItemKind.MOVIE.serialName, BaseItemKind.EPISODE.serialName),
            )
        assertEquals(2, entries.size)
        assertEquals(UUID.fromString(movieId), entries[0].itemId)
        assertEquals(BaseItemKind.MOVIE, entries[0].kind)
        assertEquals(UUID.fromString(episodeId), entries[1].itemId)
        assertEquals(BaseItemKind.EPISODE, entries[1].kind)
    }

    @Test
    fun `drops invalid ids and unsupported kinds`() {
        val entries =
            parsePlaybackQueueEntries(
                ids = listOf("not-a-uuid", movieId, episodeId),
                kinds =
                    listOf(
                        BaseItemKind.MOVIE.serialName,
                        BaseItemKind.SERIES.serialName,
                        BaseItemKind.EPISODE.serialName,
                    ),
            )
        assertEquals(1, entries.size)
        assertEquals(UUID.fromString(episodeId), entries[0].itemId)
        assertEquals(BaseItemKind.EPISODE, entries[0].kind)
    }

    @Test
    fun `keeps the first occurrence of a duplicated id`() {
        val entries =
            parsePlaybackQueueEntries(
                ids = listOf(movieId, movieId),
                kinds = listOf(BaseItemKind.MOVIE.serialName, BaseItemKind.EPISODE.serialName),
            )
        assertEquals(1, entries.size)
        assertEquals(BaseItemKind.MOVIE, entries[0].kind)
    }

    @Test
    fun `stops when the kinds list is shorter`() {
        val entries =
            parsePlaybackQueueEntries(
                ids = listOf(movieId, episodeId),
                kinds = listOf(BaseItemKind.MOVIE.serialName),
            )
        assertEquals(1, entries.size)
        assertEquals(UUID.fromString(movieId), entries[0].itemId)
    }

    @Test
    fun `null or empty extras produce an empty queue`() {
        assertTrue(parsePlaybackQueueEntries(null, null).isEmpty())
        assertTrue(parsePlaybackQueueEntries(emptyList(), emptyList()).isEmpty())
        assertTrue(parsePlaybackQueueEntries(listOf(movieId), null).isEmpty())
    }
}
