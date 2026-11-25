package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.LocalPlaylistTrack
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.music.data.PlaylistTrackSnapshot
import com.zhangwenkang.cinefin.music.data.TrackRef
import com.zhangwenkang.cinefin.music.data.projectLocalPlaylistTrack
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
                listOf(
                    MusicBatchTargetCapability(songs[0], caps()),
                    MusicBatchTargetCapability(songs[1], caps(downloaded = true)),
                    MusicBatchTargetCapability(songs[2], caps(active = true)),
                )
            )
        assertEquals(listOf(songs[0]), targets)
    }

    @Test
    fun `delete targets keep only downloaded server items`() {
        val songs = listOf(song("1"), song("2"), song("3"))
        val targets =
            batchDeleteTargets(
                listOf(
                    MusicBatchTargetCapability(songs[0], caps()),
                    MusicBatchTargetCapability(songs[1], caps(downloaded = true)),
                    MusicBatchTargetCapability(
                        songs[2],
                        caps(downloaded = true, local = true),
                    ),
                )
            )
        assertEquals(listOf(songs[1]), targets)
    }

    @Test
    fun `download targets explicitly exclude local library rows`() {
        val local = song("1", localUri = "content://local/song")

        assertTrue(
            batchDownloadTargets(listOf(MusicBatchTargetCapability(local, caps(local = true))))
                .isEmpty()
        )
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

    @Test
    fun `同 UUID 的下载态只跳过对应 server 的批量目标`() {
        val itemId = UUID.randomUUID()
        val serverA = song("1", serverId = "server-a", userId = "user-a").copy(itemId = itemId)
        val serverB = song("1", serverId = "server-b", userId = "user-a").copy(itemId = itemId)

        assertEquals(
            listOf(serverB),
            batchDownloadTargets(
                listOf(
                    MusicBatchTargetCapability(serverA, caps(downloaded = true)),
                    MusicBatchTargetCapability(serverB, caps()),
                )
            ),
        )
    }

    @Test
    fun `intent 请求保留同 UUID 不同 server 的完整 provenance`() {
        val itemId = UUID.randomUUID()
        val serverA = song("1", serverId = "server-a", userId = "user-a").copy(itemId = itemId)
        val serverB = song("1", serverId = "server-b", userId = "user-b").copy(itemId = itemId)

        val requests = musicIntentRequests(listOf(serverA, serverB))

        assertEquals(2, requests.map { it.canonicalIdentity }.distinct().size)
        assertEquals("server-a", requests[0].serverId)
        assertEquals("server-b", requests[1].serverId)
        assertEquals("user-a", requests[0].userId)
        assertEquals("user-b", requests[1].userId)
    }

    @Test
    fun `同 UUID 两条 unknown 经真实 caps 计算不共享下载状态`() {
        val itemId = UUID.randomUUID()
        val unknownA = song("1").copy(itemId = itemId, name = "A")
        val unknownB = song("2").copy(itemId = itemId, name = "B")
        val completed =
            DownloadMediaRecord(
                itemId = itemId.toString(),
                kind = DownloadMediaKind.MUSIC,
                serverId = "server-a",
                userId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                sourceId = "source-a",
            )
        val capabilities =
            listOf(
                MusicBatchTargetCapability(
                    unknownA,
                    musicSongBatchCaps(
                        song = unknownA,
                        downloadedRecords = listOf(completed),
                        activeTasks = emptyList(),
                    ),
                ),
                MusicBatchTargetCapability(
                    unknownB,
                    musicSongBatchCaps(
                        song = unknownB,
                        downloadedRecords = listOf(completed),
                        activeTasks = emptyList(),
                    ),
                ),
            )

        assertEquals(
            listOf(unknownA, unknownB),
            batchDownloadTargets(capabilities),
        )
    }

    @Test
    fun `unknown 不靠 complete 或全局集生成 downloaded 与 active caps`() {
        val itemId = UUID.randomUUID()
        val unknown = song("1").copy(itemId = itemId, serverId = "server-a")
        val complete =
            DownloadMediaRecord(
                itemId = itemId.toString(),
                kind = DownloadMediaKind.MUSIC,
                serverId = "server-a",
                userId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                sourceId = "source-a",
            )
        val active =
            DownloadTask(
                itemId = itemId,
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
                userId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            )

        val caps =
            musicSongBatchCaps(
                song = unknown,
                downloadedRecords = listOf(complete),
                activeTasks = listOf(active),
            )

        assertFalse(caps.downloaded)
        assertFalse(caps.activeDownload)
        assertTrue(caps.canDownload)
    }

    @Test
    fun `本地媒体库曲目不生成服务器下载 intent`() {
        val local =
            song("1")
                .copy(
                    localUri = "content://local/song",
                    source = com.zhangwenkang.cinefin.music.data.MusicItemSource.LOCAL,
                    localLibraryId = 7L,
                )

        assertTrue(musicIntentRequests(listOf(local)).isEmpty())
    }

    @Test
    fun `专辑批量与 intent request 使用同一服务器曲目身份`() {
        val serverSong = song("1", serverId = "server-a", userId = "user-a")
        val request = musicIntentRequests(listOf(serverSong)).single()

        assertEquals(serverSong.downloadIdentity(), request.canonicalIdentity)
    }

    // ---- W9-T2（F1b）：单曲下载 / 取消 / 删除三个入口的菜单判定 ----

    @Test
    fun `未下载且不在队列时只有一个下载入口`() {
        assertEquals(
            SongDownloadMenuAction.DOWNLOAD,
            songDownloadMenuAction(
                downloaded = false,
                activeStatus = null,
                fromLocalLibrary = false,
            ),
        )
    }

    @Test
    fun `队列中的曲目只给取消下载`() {
        listOf(
                DownloadTaskStatus.PENDING,
                DownloadTaskStatus.RUNNING,
                DownloadTaskStatus.PAUSED,
            )
            .forEach { status ->
                assertEquals(
                    "status=$status",
                    SongDownloadMenuAction.CANCEL,
                    songDownloadMenuAction(
                        downloaded = false,
                        activeStatus = status,
                        fromLocalLibrary = false,
                    ),
                )
            }
    }

    @Test
    fun `已下载曲目只给删除下载且不被队列状态覆盖`() {
        assertEquals(
            SongDownloadMenuAction.DELETE,
            songDownloadMenuAction(
                downloaded = true,
                activeStatus = null,
                fromLocalLibrary = false,
            ),
        )
        assertEquals(
            SongDownloadMenuAction.DELETE,
            songDownloadMenuAction(
                downloaded = true,
                activeStatus = DownloadTaskStatus.PAUSED,
                fromLocalLibrary = false,
            ),
        )
    }

    @Test
    fun `删除中与本地媒体库曲目不提供下载入口`() {
        assertEquals(
            SongDownloadMenuAction.NONE,
            songDownloadMenuAction(
                downloaded = true,
                activeStatus = DownloadTaskStatus.DELETING,
                fromLocalLibrary = false,
            ),
        )
        assertEquals(
            SongDownloadMenuAction.NONE,
            songDownloadMenuAction(
                downloaded = false,
                activeStatus = null,
                fromLocalLibrary = true,
            ),
        )
    }

    @Test
    fun `行副标题状态与动作一一对应`() {
        assertEquals(
            "已下载",
            songDownloadStatusLabel(SongDownloadMenuAction.DELETE, null),
        )
        assertEquals(
            "正在删除下载",
            songDownloadStatusLabel(SongDownloadMenuAction.NONE, DownloadTaskStatus.DELETING),
        )
        assertEquals(
            "下载已暂停",
            songDownloadStatusLabel(SongDownloadMenuAction.CANCEL, DownloadTaskStatus.PAUSED),
        )
        assertEquals(
            "下载中",
            songDownloadStatusLabel(SongDownloadMenuAction.CANCEL, DownloadTaskStatus.RUNNING),
        )
        assertNull(songDownloadStatusLabel(SongDownloadMenuAction.DOWNLOAD, null))
        assertNull(songDownloadStatusLabel(SongDownloadMenuAction.NONE, null))
    }

    @Test
    fun `长按 sheet 已下载时显示显式删除入口`() {
        assertEquals("下载 · 离线", songContextDownloadLabel(SongDownloadMenuAction.DOWNLOAD))
        assertEquals("下载 · 离线", songContextDownloadLabel(SongDownloadMenuAction.CANCEL))
        assertEquals("删除下载", songContextDownloadLabel(SongDownloadMenuAction.DELETE))
        assertNull(songContextDownloadLabel(SongDownloadMenuAction.NONE))

        assertEquals(
            "已下载",
            songContextDownloadTrailing(SongDownloadMenuAction.DELETE, null),
        )
        assertEquals(
            "下载中",
            songContextDownloadTrailing(SongDownloadMenuAction.CANCEL, DownloadTaskStatus.RUNNING),
        )
        assertEquals(
            "下载已暂停",
            songContextDownloadTrailing(SongDownloadMenuAction.CANCEL, DownloadTaskStatus.PAUSED),
        )
        assertNull(songContextDownloadTrailing(SongDownloadMenuAction.DOWNLOAD, null))
    }

    @Test
    fun `下载入口命中已完成下载时只提示不删除`() {
        assertEquals(
            SongDownloadRequestOutcome.ALREADY_DOWNLOADED,
            songDownloadRequestOutcome(
                hasCompletedSource = true,
                downloaded = false,
                queued = false,
            ),
        )
        // 下载态与 sources 双重证据任一命中都按已下载处理，绝不再入队
        assertEquals(
            SongDownloadRequestOutcome.ALREADY_DOWNLOADED,
            songDownloadRequestOutcome(
                hasCompletedSource = false,
                downloaded = true,
                queued = true,
            ),
        )
    }

    @Test
    fun `下载入口命中队列中时只提示不重复入队`() {
        assertEquals(
            SongDownloadRequestOutcome.ALREADY_QUEUED,
            songDownloadRequestOutcome(
                hasCompletedSource = false,
                downloaded = false,
                queued = true,
            ),
        )
        assertEquals(
            SongDownloadRequestOutcome.ENQUEUE,
            songDownloadRequestOutcome(
                hasCompletedSource = false,
                downloaded = false,
                queued = false,
            ),
        )
    }

    @Test
    fun `本地歌单投影带服务器身份后下载态与曲库条目同键`() {
        val itemId = UUID.fromString("00000000-0000-0000-0000-000000000801")
        val userId = UUID.fromString("00000000-0000-0000-0000-000000000802")
        val projected =
            projectLocalPlaylistTrack(
                entryId = "entry-1",
                track =
                    LocalPlaylistTrack(
                        ref =
                            TrackRef.Server(
                                serverId = "server-a",
                                userId = userId,
                                itemId = itemId,
                            ),
                        snapshot =
                            PlaylistTrackSnapshot(
                                displayName = "服务器曲目",
                                albumName = "专辑",
                                artist = null,
                                imageUri = null,
                                mediaSourceUri = null,
                                runtimeTicks = 100,
                            ),
                    ),
                isAvailable = true,
            )

        val identity = projected.downloadIdentity()
        assertEquals(DownloadMediaKind.MUSIC, identity?.mediaKind)
        assertEquals(itemId.toString(), identity?.itemId)
        assertEquals("server-a", identity?.serverId)
        assertEquals(userId.toString(), identity?.userId)
        // 与 ViewModel 的下载态判定同一条规则：投影带身份后 isDownloaded 必须为真
        val state =
            MusicModeViewModel.SongDownloadState(
                downloadedItemIds = setOf(itemId),
                downloadedRecords = listOf(completedOn("server-a", userId)),
            )
        assertTrue(state.isDownloaded(projected))
        assertTrue(state.isDownloaded(itemId))
    }

    @Test
    fun `本地曲目投影不带服务器身份`() {
        val itemId = UUID.fromString("00000000-0000-0000-0000-000000000803")
        val projected =
            projectLocalPlaylistTrack(
                entryId = "entry-2",
                track =
                    LocalPlaylistTrack(
                        ref = TrackRef.Local(libraryId = 7, itemId = itemId),
                        snapshot =
                            PlaylistTrackSnapshot(
                                displayName = "本地曲目",
                                albumName = "专辑",
                                artist = null,
                                imageUri = null,
                                mediaSourceUri = "content://music/803",
                                runtimeTicks = 100,
                            ),
                    ),
                isAvailable = true,
            )

        assertNull(projected.downloadIdentity())
    }

    private fun completedOn(serverId: String, userId: UUID): DownloadMediaRecord =
        DownloadMediaRecord(
            itemId = "00000000-0000-0000-0000-000000000801",
            kind = DownloadMediaKind.MUSIC,
            serverId = serverId,
            userId = userId.toString(),
            sourceId = "source-1",
        )

    /** 每次 `nextInt` 都取 0 的确定性随机源，用于锁死洗牌顺序。 */
    private object ZeroRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }
}
