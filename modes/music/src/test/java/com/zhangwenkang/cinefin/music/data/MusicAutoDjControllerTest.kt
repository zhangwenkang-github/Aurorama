package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W5-T12（MUSIC-14）：续播批次的调用形状。
 *
 * 用替身钉住「仓库 `getInstantMix(种子, limit)` 的透传」与「ENDED 后 append → 从追加批次第一首起播」的交互顺序； 不验证真实网络与播放器行为（seek +
 * play 在 `MusicQueueEditor.playFromQueueIndex` 里，下标映射由 `MusicQueueTimelineIndexTest` 覆盖）。
 */
class MusicAutoDjControllerTest {

    private val seed = UUID.fromString("55555555-5555-4555-8555-555555555555")

    private fun song(index: Int): MusicSong =
        MusicSong(
            itemId = UUID.nameUUIDFromBytes("auto-dj-song-$index".toByteArray()),
            name = "曲目 $index",
            albumName = "专辑",
            artist = "艺人",
            indexNumber = index,
            runtimeTicks = 200_000_000L,
            imageUri = null,
        )

    private fun playerItem(song: MusicSong): PlayerItem =
        PlayerItem(
            name = song.name,
            itemId = song.itemId,
            mediaSourceId = "server",
            playbackPosition = 0L,
        )

    @Test
    fun `放行时以种子与 limit 拉取 并先追加再从追加批次第一首起播`() = runBlocking {
        var requestedSeed: UUID? = null
        var requestedLimit = -1
        val events = mutableListOf<String>()
        val appendedBatches = mutableListOf<List<PlayerItem>>()
        val playedFrom = mutableListOf<Int>()

        val count =
            fetchAndAppendAutoDj(
                seedItemId = seed,
                limit = AUTO_DJ_INSTANT_MIX_LIMIT,
                fetch = { id, limit ->
                    requestedSeed = id
                    requestedLimit = limit
                    events += "fetch"
                    listOf(song(1), song(2))
                },
                gate = { size ->
                    events += "gate($size)"
                    size > 0
                },
                resolve = { song -> playerItem(song) },
                append = { items ->
                    events += "append(${items.size})"
                    appendedBatches += items
                },
                // 追加前队长 10（单曲专辑播完 → 新批次从下标 10 起）
                queueLength = {
                    events += "length(10)"
                    10
                },
                playFromIndex = { index ->
                    events += "playFrom($index)"
                    playedFrom += index
                },
            )

        assertEquals(seed, requestedSeed)
        assertEquals(AUTO_DJ_INSTANT_MIX_LIMIT, requestedLimit)
        assertEquals(2, count)
        assertEquals(1, appendedBatches.size)
        assertEquals(
            listOf(song(1).itemId, song(2).itemId),
            appendedBatches.single().map { it.itemId },
        )
        // ENDED 后不能裸 play()：必须先追加，再以「追加前队长」为起播下标
        assertEquals(listOf("fetch", "gate(2)", "length(10)", "append(2)", "playFrom(10)"), events)
        assertEquals(listOf(10), playedFrom)
    }

    @Test
    fun `队列长度不可用时只追加不起播`() = runBlocking {
        val events = mutableListOf<String>()

        val count =
            fetchAndAppendAutoDj(
                seedItemId = seed,
                limit = AUTO_DJ_INSTANT_MIX_LIMIT,
                fetch = { _, _ -> listOf(song(1)) },
                gate = { true },
                resolve = { song -> playerItem(song) },
                append = { items -> events += "append(${items.size})" },
                queueLength = { -1 },
                playFromIndex = { events += "playFrom($it)" },
            )

        assertEquals(1, count)
        assertEquals(listOf("append(1)"), events)
    }

    @Test
    fun `第 6 格未过时不追加也不起播`() = runBlocking {
        val events = mutableListOf<String>()

        val count =
            fetchAndAppendAutoDj(
                seedItemId = seed,
                limit = AUTO_DJ_INSTANT_MIX_LIMIT,
                fetch = { _, _ -> emptyList() },
                gate = { false },
                resolve = { song -> playerItem(song) },
                append = { items -> events += "append(${items.size})" },
                queueLength = { 10 },
                playFromIndex = { events += "playFrom($it)" },
            )

        assertEquals(0, count)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `全部曲目解析失败时不追加也不起播`() = runBlocking {
        val events = mutableListOf<String>()

        val count =
            fetchAndAppendAutoDj(
                seedItemId = seed,
                limit = AUTO_DJ_INSTANT_MIX_LIMIT,
                fetch = { _, _ -> listOf(song(1), song(2)) },
                gate = { true },
                resolve = { null },
                append = { items -> events += "append(${items.size})" },
                queueLength = { 10 },
                playFromIndex = { events += "playFrom($it)" },
            )

        assertEquals(0, count)
        assertTrue(events.isEmpty())
    }
}
