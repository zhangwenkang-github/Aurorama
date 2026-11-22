package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicItemSource
import com.zhangwenkang.cinefin.music.data.MusicPlaylist
import com.zhangwenkang.cinefin.music.data.MusicPlaylistKind
import com.zhangwenkang.cinefin.music.data.MusicSong
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W8-T4：服务器歌单新建 / 加入的纯规则（名称校验、服务器曲目过滤、行可用性）。 */
class ServerPlaylistRulesTest {

    private fun song(id: String, source: MusicItemSource): MusicSong =
        MusicSong(
            itemId = UUID.fromString("00000000-0000-0000-0000-00000000000$id"),
            name = "song-$id",
            albumName = "album",
            artist = "artist",
            indexNumber = null,
            runtimeTicks = 0L,
            imageUri = null,
            source = source,
            localUri = "content://local/$id".takeIf { source == MusicItemSource.LOCAL },
        )

    private fun playlist(kind: MusicPlaylistKind): MusicPlaylist =
        MusicPlaylist(
            id = UUID.fromString("00000000-0000-0000-0000-000000000900"),
            name = "歌单",
            imageUri = null,
            songCount = null,
            kind = kind,
        )

    @Test
    fun `歌单名去首尾空白后非空才算有效`() {
        assertEquals("通勤歌单", normalizedServerPlaylistName("  通勤歌单 "))
        assertNull(normalizedServerPlaylistName("   "))
        assertNull(normalizedServerPlaylistName(""))
    }

    @Test
    fun `服务器歌单只收服务器来源曲目且保持原序`() {
        val serverA = song("1", MusicItemSource.SERVER)
        val local = song("2", MusicItemSource.LOCAL)
        val serverB = song("3", MusicItemSource.SERVER)

        assertEquals(
            listOf(serverA.itemId, serverB.itemId),
            serverPlaylistItemIds(listOf(serverA, local, serverB)),
        )
        assertTrue(serverPlaylistItemIds(listOf(local)).isEmpty())
    }

    @Test
    fun `服务器歌单行只在待加入曲目全部来自服务器时可点`() {
        val server = playlist(MusicPlaylistKind.SERVER)
        val allServer = listOf(song("1", MusicItemSource.SERVER), song("2", MusicItemSource.SERVER))
        val mixed = allServer + song("3", MusicItemSource.LOCAL)

        assertTrue(canAddToServerPlaylist(server, allServer))
        assertFalse(canAddToServerPlaylist(server, mixed))
        assertFalse(canAddToServerPlaylist(server, emptyList()))
    }

    @Test
    fun `本地歌单不受服务器来源限制`() {
        val local = playlist(MusicPlaylistKind.LOCAL)
        val mixed = listOf(song("1", MusicItemSource.SERVER), song("2", MusicItemSource.LOCAL))

        assertFalse(canAddToServerPlaylist(local, mixed))
        assertEquals("歌单", playlistAddRowLabel(local))
    }

    @Test
    fun `服务器歌单行文案带纯文本后缀`() {
        assertEquals("歌单 · 服务器", playlistAddRowLabel(playlist(MusicPlaylistKind.SERVER)))
    }

    @Test
    fun `批量加入歌单只要有选中条目就可用`() {
        assertTrue(
            musicBatchActionEnabled(
                MusicBatchAction.ADD_TO_PLAYLIST,
                listOf(SongBatchCaps(downloaded = true, fromLocalLibrary = true)),
                inPlaylist = false,
            )
        )
        assertTrue(
            musicBatchActionEnabled(
                MusicBatchAction.ADD_TO_PLAYLIST,
                listOf(SongBatchCaps()),
                inPlaylist = true,
            )
        )
        assertFalse(
            musicBatchActionEnabled(
                MusicBatchAction.ADD_TO_PLAYLIST,
                emptyList(),
                inPlaylist = true,
            )
        )
    }
}
