package com.zhangwenkang.cinefin.work

import com.zhangwenkang.cinefin.models.FindroidUserDataDto
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W9-T4 契约：补同步扫描集与推送语义。
 *
 * 覆盖 W8-T1r 遗留（只扫 movies/episodes → 季/剧排队写不补传，WRITE-08）与音乐收藏排队（WRITE-05）： 只有服务端确认才清
 * `toBeSynced`，失败保留；无投影条目只推收藏位。
 */
class UserDataSyncQueueTest {

    @Test
    fun `扫描集包含季剧投影行与音乐排队行且去重`() {
        val ids =
            userDataSyncItemIds(
                listOf(MOVIE_ID),
                listOf(EPISODE_ID),
                listOf(SHOW_ID, MOVIE_ID),
                listOf(SEASON_ID),
                listOf(AUDIO_ID, SEASON_ID),
            )

        assertEquals(
            listOf(MOVIE_ID, EPISODE_ID, SHOW_ID, SEASON_ID, AUDIO_ID),
            ids,
        )
    }

    @Test
    fun `季剧排队行补传成功后清标记`() = runBlocking {
        val store = FakeQueue(SHOW_ID, SEASON_ID)
        val pushed = mutableListOf<UUID>()

        pushQueuedUserData(
            itemIds = listOf(SHOW_ID, SEASON_ID),
            loadQueued = store::load,
            mirrored = { true },
            push = { userData, _ -> pushed += userData.itemId },
            clear = store::clear,
        )

        assertEquals(listOf(SHOW_ID, SEASON_ID), pushed)
        assertTrue(store.cleared.containsAll(listOf(SHOW_ID, SEASON_ID)))
        assertTrue(store.load(SHOW_ID) == null)
        assertTrue(store.load(SEASON_ID) == null)
    }

    @Test
    fun `补传失败的条目保留排队行且不阻断后续条目`() = runBlocking {
        val store = FakeQueue(SHOW_ID, SEASON_ID)

        pushQueuedUserData(
            itemIds = listOf(SHOW_ID, SEASON_ID),
            loadQueued = store::load,
            mirrored = { true },
            push = { userData, _ ->
                if (userData.itemId == SHOW_ID) throw IOException("connection reset")
            },
            clear = store::clear,
        )

        assertEquals(setOf(SEASON_ID), store.cleared)
        assertTrue(store.load(SHOW_ID) != null)
        assertTrue(store.load(SEASON_ID) == null)
    }

    @Test
    fun `没有排队的条目不发请求也不清标记`() = runBlocking {
        val store = FakeQueue(SHOW_ID)
        var pushCount = 0

        pushQueuedUserData(
            itemIds = listOf(SHOW_ID, MOVIE_ID),
            loadQueued = store::load,
            mirrored = { true },
            push = { _, _ -> pushCount++ },
            clear = store::clear,
        )

        assertEquals(1, pushCount)
        assertFalse(store.cleared.contains(MOVIE_ID))
    }

    @Test
    fun `有投影的条目推送完整用户数据`() {
        val dto = queuedUserDataUpdateDto(USER_DATA, mirrored = true)

        assertEquals(PLAYBACK_TICKS, dto.playbackPositionTicks)
        assertEquals(true, dto.isFavorite)
        assertEquals(true, dto.played)
    }

    @Test
    fun `没有投影的音乐条目只推收藏位`() {
        val dto = queuedUserDataUpdateDto(USER_DATA, mirrored = false)

        assertEquals(true, dto.isFavorite)
        assertNull(dto.played)
        assertNull(dto.playbackPositionTicks)
    }

    @Test
    fun `补传传播协程取消且不清标记`() = runBlocking {
        val store = FakeQueue(SHOW_ID)
        var cancelled = false

        try {
            pushQueuedUserData(
                itemIds = listOf(SHOW_ID),
                loadQueued = store::load,
                mirrored = { true },
                push = { _, _ -> throw CancellationException("worker stopped") },
                clear = store::clear,
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertTrue(store.cleared.isEmpty())
        assertTrue(store.load(SHOW_ID) != null)
    }

    private class FakeQueue(vararg initial: UUID) {
        private val rows = initial.associateWith { userData(it) }.toMutableMap()
        val cleared = mutableSetOf<UUID>()

        suspend fun load(itemId: UUID): FindroidUserDataDto? = rows[itemId]

        suspend fun clear(itemId: UUID) {
            cleared += itemId
            rows.remove(itemId)
        }
    }

    private companion object {
        const val PLAYBACK_TICKS = 12_345_678L
        val USER_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val MOVIE_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val EPISODE_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val SHOW_ID: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val SEASON_ID: UUID = UUID.fromString("55555555-5555-4555-8555-555555555555")
        val AUDIO_ID: UUID = UUID.fromString("66666666-6666-4666-8666-666666666666")
        val USER_DATA =
            FindroidUserDataDto(
                userId = USER_ID,
                itemId = AUDIO_ID,
                played = true,
                favorite = true,
                playbackPositionTicks = PLAYBACK_TICKS,
                toBeSynced = true,
            )

        fun userData(itemId: UUID): FindroidUserDataDto = USER_DATA.copy(itemId = itemId)
    }
}
