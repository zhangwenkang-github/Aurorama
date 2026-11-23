package com.zhangwenkang.cinefin.music.data

import android.content.Context
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.player.core.domain.models.TrackSourceType
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** W9-T5（F4）：已下载曲目点击 → 直接走本地文件，不经服务器条目 / 音频流协商。 */
class MusicTrackResolverTest {

    private val itemId = UUID.fromString("354cc62f-e51d-ae66-693d-6e12d747e56b")
    private val localPath =
        "/storage/emulated/0/Android/data/io.github.zhangwenkang.aurorama/files/downloads/song.g1"

    private fun downloadedSong() =
        MusicSong(
            itemId = itemId,
            name = "一路生花",
            albumName = "一路生花",
            artist = "测试艺人",
            indexNumber = 1,
            runtimeTicks = 0L,
            imageUri = null,
        )

    private fun localSource() =
        FindroidSource(
            id = "local-source",
            name = "本地文件",
            type = FindroidSourceType.LOCAL,
            path = localPath,
            size = 28_367_204L,
            mediaStreams = emptyList(),
            downloadTaskStatus = "COMPLETED",
        )

    private fun resolver(repository: JellyfinRepository) =
        MusicTrackResolver(
            repository = repository,
            appPreferences = mockk<AppPreferences>(relaxed = true),
            context = mockk<Context>(relaxed = true),
        )

    @Test
    fun `downloaded song resolves the local file without any server source request`() =
        runBlocking {
            val repository = mockk<JellyfinRepository>()
            coEvery { repository.getDownloadedLocalSource(itemId) } returns localSource()
            coEvery { repository.getMediaSources(any(), any(), any()) } throws
                IllegalStateException("下载曲目不得再查 PlaybackInfo")
            coEvery { repository.getMusicStreamUrl(any(), any()) } throws
                IllegalStateException("下载曲目不得构造流地址")

            val item = resolver(repository).toPlayerItem(downloadedSong())

            assertEquals(localPath, item.mediaSourceUri)
            assertEquals(TrackSourceType.LOCAL, item.trackSource?.sourceType)
            assertEquals("local-source", item.mediaSourceId)
            coVerify(exactly = 1) { repository.getDownloadedLocalSource(itemId) }
            coVerify(exactly = 0) { repository.getMediaSources(any(), any(), any()) }
            coVerify(exactly = 0) { repository.getMusicStreamUrl(any(), any()) }
            coVerify(exactly = 0) { repository.getItem(any()) }
            coVerify(exactly = 0) { repository.getStreamUrl(any(), any()) }
        }

    /** 非下载来源（合成 LOCAL 源）仍走既有 PlaybackInfo 分支，不回归。 */
    @Test
    fun `local synthetic source keeps the existing source selection path`() = runBlocking {
        val repository = mockk<JellyfinRepository>()
        coEvery { repository.getDownloadedLocalSource(itemId) } returns null
        coEvery {
            repository.getMediaSources(itemId, includePath = true, startPositionTicks = 0L)
        } returns listOf(localSource())

        val item = resolver(repository).toPlayerItem(downloadedSong())

        assertEquals(localPath, item.mediaSourceUri)
        assertEquals(TrackSourceType.LOCAL, item.trackSource?.sourceType)
        coVerify(exactly = 1) {
            repository.getMediaSources(itemId, includePath = true, startPositionTicks = 0L)
        }
        coVerify(exactly = 0) { repository.getMusicStreamUrl(any(), any()) }
    }

    @Test
    fun `local media library song never asks the repository for sources`() = runBlocking {
        val repository = mockk<JellyfinRepository>()

        resolver(repository)
            .toPlayerItem(
                downloadedSong().copy(localUri = "content://media/external/audio/media/42")
            )

        coVerify(exactly = 0) { repository.getDownloadedLocalSource(any()) }
        coVerify(exactly = 0) { repository.getMediaSources(any(), any(), any()) }
        coVerify(exactly = 0) { repository.getItem(any()) }
    }
}
