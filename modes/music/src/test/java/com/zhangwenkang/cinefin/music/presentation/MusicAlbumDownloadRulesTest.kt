package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicSong
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W59 专辑下载计划（只补齐缺失 + 100 首上限 + 多专辑合并去重）。 */
class MusicAlbumDownloadRulesTest {

    private fun song(index: Int, name: String = "track$index") =
        MusicSong(
            itemId = UUID.randomUUID(),
            name = name,
            albumName = "示例专辑",
            artist = "示例艺人",
            indexNumber = index,
            runtimeTicks = 0L,
            imageUri = null,
        )

    private fun album(name: String, songs: List<MusicSong>) =
        MusicAlbum(key = name, name = name, artist = "示例艺人", imageUri = null, songs = songs)

    @Test
    fun `只补齐缺失 已下载与队列内跳过`() {
        val downloaded = song(1)
        val active = song(2)
        val missingA = song(3)
        val missingB = song(4)
        val target = album("示例专辑", listOf(downloaded, active, missingA, missingB))

        val plan =
            MusicAlbumDownloadRules.plan(
                album = target,
                downloadedItemIds = setOf(downloaded.itemId),
                activeItemIds = setOf(active.itemId),
            )

        assertEquals(listOf(missingA.itemId, missingB.itemId), plan.targets.map { it.itemId })
        assertEquals(1, plan.skippedDownloaded)
        assertEquals(1, plan.skippedActive)
        assertEquals(0, plan.skippedByLimit)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `单次上限 100 首 其余标记为推迟`() {
        val songs = (1..120).map { index -> song(index) }
        val target = album("大专辑", songs)

        val plan =
            MusicAlbumDownloadRules.plan(
                album = target,
                downloadedItemIds = emptySet(),
                activeItemIds = emptySet(),
            )

        assertEquals(100, plan.targets.size)
        assertEquals(20, plan.skippedByLimit)
        // 按音轨序取前 100 首（1..100）。
        assertEquals((1..100).map { it }, plan.targets.map { it.indexNumber })
    }

    @Test
    fun `全部已下载时计划为空`() {
        val songs = (1..3).map { index -> song(index) }
        val target = album("已完成专辑", songs)
        val plan =
            MusicAlbumDownloadRules.plan(
                album = target,
                downloadedItemIds = songs.map { it.itemId }.toSet(),
                activeItemIds = emptySet(),
            )
        assertTrue(plan.isEmpty)
        assertEquals(3, plan.skippedDownloaded)
    }

    @Test
    fun `多专辑合并按专辑顺序 同一曲目去重 整批上限`() {
        val first = album("专辑一", (1..3).map { song(it, "A$it") })
        val second = (1..3).map { song(it, "B$it") }
        // 第二张专辑含一首与第一张重复的曲目。
        val duplicated = album("专辑二", listOf(first.songs[0], second[1], second[2]))

        val plan =
            MusicAlbumDownloadRules.planAlbums(
                albums = listOf(first, duplicated),
                downloadedItemIds = emptySet(),
                activeItemIds = emptySet(),
                maxTracks = 4,
            )

        assertEquals(listOf("A1", "A2", "A3", "B2"), plan.targets.map { it.name })
        assertEquals(1, plan.skippedByLimit)
    }
}
