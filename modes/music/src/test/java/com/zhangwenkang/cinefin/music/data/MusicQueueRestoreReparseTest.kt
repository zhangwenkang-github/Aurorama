package com.zhangwenkang.cinefin.music.data

import android.content.Context
import android.content.SharedPreferences
import com.zhangwenkang.cinefin.database.music.MusicStorage
import com.zhangwenkang.cinefin.database.music.StoredQueue
import com.zhangwenkang.cinefin.database.music.StoredQueueItem
import com.zhangwenkang.cinefin.database.music.StoredQueueKind
import com.zhangwenkang.cinefin.database.music.StoredQueueState
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import com.zhangwenkang.cinefin.player.core.domain.models.RepeatMode
import com.zhangwenkang.cinefin.player.core.domain.models.TrackSourceType
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackController
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W9-T5（F4b）契约：队列快照里的 http(s) 流地址只当上个会话的地址提示，恢复 / 起播时按当前解析链重解析。
 *
 * 场景来自 K60 断网实证（`F:\Develop\code_data\qa\w9-t5\`）：快照里是旧主机（jellyfins）的流地址， 恢复后按播放键直接把旧地址交给 ExoPlayer
 * → UnknownHost / BUFFERING 卡死。修复后同一快照必须命中本机已下载文件。
 */
class MusicQueueRestoreReparseTest {

    private val downloadedId = UUID.fromString("354cc62f-e51d-ae66-693d-6e12d747e56b")
    private val missingId = UUID.fromString("7a2b1e55-3f5a-4d7e-9a6b-0c1d2e3f4a5b")
    private val upNextDownloadedId = UUID.fromString("c1d2e3f4-a5b6-4c7d-8e9f-0a1b2c3d4e5f")
    private val localPath =
        "/storage/emulated/0/Android/data/io.github.zhangwenkang.aurorama/files/downloads/song.g1"
    private val upNextLocalPath =
        "/storage/emulated/0/Android/data/io.github.zhangwenkang.aurorama/files/downloads/next.g1"

    /** 上个会话的地址：旧主机 + 旧 UserId / DeviceId，当前 jellyfin-test 会话不可用。 */
    private val staleUri =
        "https://jellyfins.zhangwenkang.com/Audio/$downloadedId/universal" +
            "?Container=flac&UserId=old-user&DeviceId=old-device&EnableRedirection=true"
    private val staleMissingUri =
        "https://jellyfins.zhangwenkang.com/Audio/$missingId/universal" +
            "?Container=mp3&AudioCodec=mp3&AudioBitRate=128000&EnableRedirection=true"

    private fun localSource(sourceId: UUID, path: String) =
        FindroidSource(
            id = "local-$sourceId",
            name = "本地文件",
            type = FindroidSourceType.LOCAL,
            path = path,
            size = 28_367_204L,
            mediaStreams = emptyList(),
            downloadTaskStatus = "COMPLETED",
        )

    private fun storedItem(
        position: Int,
        itemId: UUID,
        name: String,
        uri: String,
        playbackPositionMs: Long = 0L,
        kind: String = StoredQueueKind.MAIN,
    ) =
        StoredQueueItem(
            position = position,
            itemId = itemId.toString(),
            name = name,
            mediaSourceId = "old-source-$position",
            playbackPositionMs = playbackPositionMs,
            mediaSourceUri = uri,
            thumbnailUri = null,
            indexNumber = position + 1,
            kind = kind,
        )

    /** 上个会话留下的快照：当前曲已下载、下一首未下载、接下来播放条目已下载。 */
    private fun storedQueue() =
        StoredQueue(
            items =
                listOf(
                    storedItem(0, downloadedId, "一路生花", staleUri, playbackPositionMs = 12_000L),
                    storedItem(1, missingId, "佛系少女", staleMissingUri),
                    storedItem(
                        position = 2,
                        itemId = upNextDownloadedId,
                        name = "夜曲",
                        uri =
                            "https://jellyfins.zhangwenkang.com/Audio/$upNextDownloadedId/universal",
                        kind = StoredQueueKind.UP_NEXT,
                    ),
                ),
            state =
                StoredQueueState(
                    currentIndex = 0,
                    positionMs = 12_000L,
                    repeatMode = RepeatMode.OFF.name,
                    shuffleEnabled = false,
                    source = QueueSource.MANUAL.name,
                    sourceId = null,
                    savedAt = 5L,
                ),
        )

    private fun appPreferences(): AppPreferences {
        val preferences =
            mockk<SharedPreferences>(relaxed = true).also { preferences ->
                every { preferences.getBoolean(any(), any()) } answers { secondArg() }
                every { preferences.getInt(any(), any()) } answers { secondArg() }
                every { preferences.getLong(any(), any()) } answers { secondArg() }
                every { preferences.getFloat(any(), any()) } answers { secondArg() }
                every { preferences.getString(any(), any()) } answers { secondArg() }
            }
        return AppPreferences(preferences)
    }

    private fun resolver(repository: JellyfinRepository) =
        MusicTrackResolver(
            repository = repository,
            appPreferences = appPreferences(),
            context = mockk<Context>(relaxed = true),
        )

    private fun repositoryWithDownloads(): JellyfinRepository =
        mockk<JellyfinRepository>().also { repository ->
            coEvery { repository.getDownloadedLocalSource(downloadedId) } returns
                localSource(downloadedId, localPath)
            coEvery { repository.getDownloadedLocalSource(upNextDownloadedId) } returns
                localSource(upNextDownloadedId, upNextLocalPath)
            coEvery { repository.getDownloadedLocalSource(missingId) } returns null
            coEvery { repository.getMediaSources(any(), any(), any()) } throws
                IllegalStateException("恢复已下载曲目不得查 PlaybackInfo")
            coEvery { repository.getMusicStreamUrl(any(), any()) } throws
                IllegalStateException("恢复已下载曲目不得构造流地址")
        }

    /** 恢复入口（`MusicQueuePersister.load`）：断网冷启动后底栏「播放」用的就是这份快照。 */
    @Test
    fun `恢复快照时过期跨服务器地址命中本机已下载文件 未下载条目保留原地址`() = runBlocking {
        val storage = mockk<MusicStorage>()
        coEvery { storage.loadQueue() } returns storedQueue()
        val repository = repositoryWithDownloads()
        val persister =
            MusicQueuePersister(
                store = MusicQueueStore(storage),
                playbackController = mockk<MusicPlaybackController>(relaxed = true),
                appPreferences = appPreferences(),
                repository = repository,
            )

        val snapshot = persister.load()!!

        val current = snapshot.queue.items[0]
        assertEquals(localPath, current.mediaSourceUri)
        assertEquals("local-$downloadedId", current.mediaSourceId)
        assertEquals(TrackSourceType.LOCAL, current.trackSource?.sourceType)
        assertEquals(12_000L, current.playbackPosition)
        // 未下载的曲目不在恢复阶段打网络：保留原地址，交给起播路径重解析
        assertEquals(staleMissingUri, snapshot.queue.items[1].mediaSourceUri)
        // 「接下来播放」条目同样重解析
        assertEquals(upNextLocalPath, snapshot.queue.upNext.single().mediaSourceUri)
        coVerify(exactly = 0) { repository.getMediaSources(any(), any(), any()) }
        coVerify(exactly = 0) { repository.getMusicStreamUrl(any(), any()) }
    }

    /** 按播放键路径：恢复态起播前按当前解析链重解析当前曲（下载曲在解析链内本地短路）。 */
    @Test
    fun `按播放键起播时当前曲按当前解析链命中本地文件`() = runBlocking {
        val repository = repositoryWithDownloads()
        val focus = storedQueue().toSnapshot()!!.queue.currentItem!!

        val played = focus.reparsedForPlayback { song -> resolver(repository).toPlayerItem(song) }

        assertEquals(localPath, played.mediaSourceUri)
        assertEquals(TrackSourceType.LOCAL, played.trackSource?.sourceType)
        assertEquals(12_000L, played.playbackPosition)
        assertEquals(focus.name, played.name)
        coVerify(exactly = 1) { repository.getDownloadedLocalSource(downloadedId) }
        coVerify(exactly = 0) { repository.getMediaSources(any(), any(), any()) }
        coVerify(exactly = 0) { repository.getMusicStreamUrl(any(), any()) }
    }

    /** tap 路径：点快照 / 降级曲库里的曲目也走同一解析链，命中本地文件即可离线起播。 */
    @Test
    fun `tap 直开快照曲目走同一解析链命中本地文件`() = runBlocking {
        val repository = repositoryWithDownloads()
        val tapped = storedQueue().toSnapshot()!!.queue.items[0]

        val played = resolver(repository).toPlayerItem(tapped.toRestoreMusicSong())

        assertEquals(localPath, played.mediaSourceUri)
        assertEquals(TrackSourceType.LOCAL, played.trackSource?.sourceType)
        coVerify(exactly = 1) { repository.getDownloadedLocalSource(downloadedId) }
        coVerify(exactly = 0) { repository.getMediaSources(any(), any(), any()) }
    }

    /** 重解析失败（断网且未下载）沿用快照条目，起播不因重解析整体失败。 */
    @Test
    fun `重解析失败沿用快照地址不抛出`() = runBlocking {
        val stale = storedQueue().toSnapshot()!!.queue.items[0]

        val fallback = stale.reparsedForPlayback {
            throw IllegalStateException("HTTP host unreachable")
        }

        assertEquals(stale.mediaSourceUri, fallback.mediaSourceUri)
        assertEquals(stale.mediaSourceId, fallback.mediaSourceId)
        assertEquals(stale.playbackPosition, fallback.playbackPosition)
    }

    /** 本地地址（文档 / 文件 / 绝对路径）直接复用，不再解析；http(s) 一律视为需要重解析。 */
    @Test
    fun `本地地址不重解析 服务器流地址重解析`() = runBlocking {
        var resolves = 0
        val local = storedQueue().toSnapshot()!!.queue.items[0].copy(mediaSourceUri = localPath)
        local.reparsedForPlayback {
            resolves++
            error("本地地址不得重解析")
        }
        assertEquals(0, resolves)

        assertTrue(isLocalPlaybackUri(localPath))
        assertTrue(isLocalPlaybackUri("content://media/external/audio/media/42"))
        assertTrue(isLocalPlaybackUri("file:///storage/emulated/0/Music/a.flac"))
        assertFalse(isLocalPlaybackUri(staleUri))
        assertFalse(isLocalPlaybackUri(staleMissingUri))
    }

    /** 下载文件的绝对路径在恢复时按本地来源回推（旧实现只认 content://，会误标服务器）。 */
    @Test
    fun `恢复时下载文件绝对路径按本地来源回推`() {
        val restored = restoredTrackSource(localPath)
        assertEquals(TrackSourceType.LOCAL, restored.sourceType)
        assertFalse(restored.isTranscoded)

        val server = restoredTrackSource(staleMissingUri)
        assertEquals(TrackSourceType.SERVER, server.sourceType)
        assertTrue(server.isTranscoded)
    }
}
