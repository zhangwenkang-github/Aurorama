package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.database.music.StoredRecentSong
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 最近播放持久化映射（W21-R2）。 */
class MusicRecentStoreTest {

    @Test
    fun `记录映射回曲目模型`() {
        val id = UUID.nameUUIDFromBytes("recent-song".toByteArray())
        val song =
            StoredRecentSong(
                    itemId = id.toString(),
                    name = "最近一首",
                    albumName = "专辑",
                    artist = "歌手",
                    imageUri = "https://example.test/a.jpg",
                    runtimeTicks = 20_000_000L,
                    playedAt = 123L,
                )
                .toMusicSongOrNull()!!

        assertEquals(id, song.itemId)
        assertEquals("最近一首", song.name)
        assertEquals("专辑", song.albumName)
        assertEquals("歌手", song.artist)
        assertEquals(20_000_000L, song.runtimeTicks)
        assertEquals(0L, song.resumePositionMs)
    }

    @Test
    fun `非法 itemId 返回 null`() {
        val entry =
            StoredRecentSong(
                itemId = "not-a-uuid",
                name = "坏数据",
                albumName = "",
                artist = null,
                imageUri = null,
                runtimeTicks = 0L,
                playedAt = 0L,
            )

        assertNull(entry.toMusicSongOrNull())
    }
}
