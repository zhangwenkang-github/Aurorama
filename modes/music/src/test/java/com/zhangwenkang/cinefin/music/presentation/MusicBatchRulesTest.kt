package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.utils.DownloadFailureReason
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadMediaRecord
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.util.UUID
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBatchRulesTest {

    private fun song(
        id: String,
        favorite: Boolean = false,
        localUri: String? = null,
        serverId: String? = null,
        userId: String? = null,
    ): MusicSong =
        MusicSong(
            itemId = UUID.fromString("00000000-0000-0000-0000-00000000000$id"),
            name = "song-$id",
            albumName = "album",
            artist = "artist",
            indexNumber = id.toInt(),
            runtimeTicks = 0L,
            imageUri = null,
            isFavorite = favorite,
            localUri = localUri,
            serverId = serverId,
            userId = userId,
        )

    private fun caps(
        downloaded: Boolean = false,
        active: Boolean = false,
        local: Boolean = false,
    ) = SongBatchCaps(downloaded = downloaded, activeDownload = active, fromLocalLibrary = local)

    @Test
    fun `download is blocked when downloaded or already queued`() {
        assertTrue(caps().canDownload)
        assertFalse(caps(downloaded = true).canDownload)
        assertFalse(caps(active = true).canDownload)
    }

    @Test
    fun `delete only applies to downloaded server items`() {
        assertTrue(caps(downloaded = true).canDelete)
        assertFalse(caps(downloaded = false).canDelete)
        assertFalse(caps(downloaded = true, local = true).canDelete)
    }

    @Test
    fun `caps are derived from the download state keyed by item id`() {
        val item = song("1")
        val derived =
            musicSongBatchCaps(
                item,
                downloadedItemIds = setOf(item.itemId),
                activeItemIds = emptySet(),
            )
        assertTrue(derived.downloaded)
        assertTrue(derived.canDelete)
        assertFalse(derived.canDownload)
    }

    @Test
    fun `caps mark local library items as not deletable`() {
        val item = song("2", localUri = "content://local/2")
        val derived =
            musicSongBatchCaps(
                item,
                downloadedItemIds = setOf(item.itemId),
                activeItemIds = emptySet(),
            )
        assertTrue(derived.fromLocalLibrary)
        assertFalse(derived.canDelete)
        assertFalse(derived.canDownload)
    }

    @Test
    fun `action enabled follows any selected item like the download page`() {
        val mixed = listOf(caps(), caps(downloaded = true))
        assertTrue(musicBatchActionEnabled(MusicBatchAction.DOWNLOAD, mixed, inPlaylist = false))
        assertTrue(musicBatchActionEnabled(MusicBatchAction.DELETE, mixed, inPlaylist = false))
        assertTrue(musicBatchActionEnabled(MusicBatchAction.PLAY, mixed, inPlaylist = false))
        assertTrue(musicBatchActionEnabled(MusicBatchAction.FAVORITE, mixed, inPlaylist = false))
    }

    @Test
    fun `actions are disabled without a selection`() {
        MusicBatchAction.entries.forEach { action ->
            assertFalse(
                "action $action should be disabled",
                musicBatchActionEnabled(action, emptyList(), inPlaylist = true),
            )
        }
    }

    @Test
    fun `remove from playlist requires playlist context`() {
        val selected = listOf(caps())
        assertTrue(
            musicBatchActionEnabled(
                MusicBatchAction.REMOVE_FROM_PLAYLIST,
                selected,
                inPlaylist = true,
            )
        )
        assertFalse(
            musicBatchActionEnabled(
                MusicBatchAction.REMOVE_FROM_PLAYLIST,
                selected,
                inPlaylist = false,
            )
        )
    }

    @Test
    fun `delete stays disabled when nothing is downloaded`() {
        assertFalse(
            musicBatchActionEnabled(
                MusicBatchAction.DELETE,
                listOf(caps(), caps(active = true), caps(local = true)),
                inPlaylist = false,
            )
        )
    }

    @Test
    fun `batch play order follows the visible list order not the tap order`() {
        val songs = listOf(song("1"), song("2"), song("3"))
        val order =
            batchPlayOrder(
                songs,
                selectedIds = setOf(songs[2].itemId.toString(), songs[0].itemId.toString()),
            )
        assertEquals(listOf(songs[0], songs[2]), order)
    }

    @Test
    fun `favorite target favorites the batch when any item is unfavorited`() {
        assertEquals(true, batchFavoriteTarget(listOf(song("1"), song("2", favorite = true))))
    }

    @Test
    fun `favorite target unfavorites the batch when all items are favorited`() {
        assertEquals(
            false,
            batchFavoriteTarget(listOf(song("1", favorite = true), song("2", favorite = true))),
        )
    }

    @Test
    fun `favorite target is null without a selection`() {
        assertNull(batchFavoriteTarget(emptyList()))
    }

    @Test
    fun `download targets skip downloaded and active items`() {
        val songs = listOf(song("1"), song("2"), song("3"))
        val targets =
            batchDownloadTargets(
                songs,
                mapOf(
                    songs[0].itemId to caps(),
                    songs[1].itemId to caps(downloaded = true),
                    songs[2].itemId to caps(active = true),
                ),
            )
        assertEquals(listOf(songs[0]), targets)
    }

    @Test
    fun `delete targets keep only downloaded server items`() {
        val songs = listOf(song("1"), song("2"), song("3"))
        val targets =
            batchDeleteTargets(
                songs,
                mapOf(
                    songs[0].itemId to caps(),
                    songs[1].itemId to caps(downloaded = true),
                    songs[2].itemId to caps(downloaded = true, local = true),
                ),
            )
        assertEquals(listOf(songs[1]), targets)
    }

    @Test
    fun `sequential play all starts from the first song`() {
        assertEquals(0, playAllStartIndex(size = 3, shuffle = false))
    }

    @Test
    fun `shuffle play all chooses only the first index so the natural queue stays intact`() {
        assertEquals(0, playAllStartIndex(size = 4, shuffle = true, random = ZeroRandom))
        assertEquals(0, playAllStartIndex(size = 0, shuffle = true, random = ZeroRandom))
    }

    @Test
    fun `跨页取消后的失败任务不再占用 active 状态使下载恢复可用`() {
        val item = song("1")
        val cancelled =
            DownloadTask(
                itemId = item.itemId,
                sourceId = "source-1",
                name = item.name,
                path = "/tmp/source-1.download",
                downloadId = null,
                status = DownloadTaskStatus.FAILED,
                failureReason = DownloadFailureReason.CANCELLED,
                downloadedBytes = 10L,
                totalBytes = 100L,
                updatedAt = 1L,
            )

        val active = activeSongDownloadStatuses(listOf(cancelled))
        val caps =
            musicSongBatchCaps(
                song = item,
                downloadedRecords = emptyList(),
                activeTasks = listOf(cancelled),
            )

        assertTrue(active.isEmpty())
        assertTrue(caps.canDownload)
    }

    @Test
    fun `同 UUID 不同 server 的 active 下载不折叠`() {
        val serverA = song("1", serverId = "server-a", userId = "user-a")
        val serverB = song("1", serverId = "server-b", userId = "user-a")
        val taskA =
            DownloadTask(
                itemId = serverA.itemId,
                sourceId = "source-a",
                name = "A",
                path = "/tmp/a.download",
                downloadId = null,
                status = DownloadTaskStatus.RUNNING,
                failureReason = null,
                downloadedBytes = 1L,
                totalBytes = 2L,
                updatedAt = 1L,
                mediaKind = DownloadMediaKind.MUSIC,
                serverId = "server-a",
                userId = "user-a",
            )
        val taskB =
            taskA.copy(
                sourceId = "source-b",
                serverId = "server-b",
            )

        assertEquals(2, activeSongDownloadStatuses(listOf(taskA, taskB)).size)
        assertTrue(musicSongBatchCaps(serverA, emptyList(), listOf(taskA)).activeDownload)
        assertFalse(musicSongBatchCaps(serverB, emptyList(), listOf(taskA)).activeDownload)

        val downloadedA =
            DownloadMediaRecord(
                itemId = serverA.itemId.toString(),
                kind = DownloadMediaKind.MUSIC,
                serverId = "server-a",
                userId = "user-a",
                sourceId = "source-a",
            )
        assertTrue(musicSongBatchCaps(serverA, listOf(downloadedA), emptyList()).downloaded)
        assertFalse(musicSongBatchCaps(serverB, listOf(downloadedA), emptyList()).downloaded)
    }

    /** 每次 `nextInt` 都取 0 的确定性随机源，用于锁死洗牌顺序。 */
    private object ZeroRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }
}
