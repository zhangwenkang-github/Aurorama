package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicSong
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicRouteQueueTest {

    private fun song(id: UUID, name: String) =
        MusicSong(
            itemId = id,
            name = name,
            albumName = "专辑",
            artist = " artist ",
            indexNumber = 1,
            runtimeTicks = 0L,
            imageUri = null,
        )

    @Test
    fun `下载页传入的曲目 id 按选择顺序恢复且忽略重复`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        val ids = parseMusicQueueIds("$second,$first,$second", first)

        assertEquals(listOf(second, first), ids)
    }

    @Test
    fun `非法队列参数回落到首个曲目`() {
        val first = UUID.randomUUID()

        assertEquals(listOf(first), parseMusicQueueIds("bad-id", first))
        assertEquals(listOf(first), parseMusicQueueIds(null, first))
    }

    @Test
    fun `按下载页选择顺序组装歌曲而不使用曲库顺序`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val server = listOf(song(second, "第二"), song(first, "第一"))

        val ordered = orderedMusicSongs(listOf(second, first), server, emptyList())

        assertEquals(listOf("第二", "第一"), ordered.map { it.name })
    }
}
