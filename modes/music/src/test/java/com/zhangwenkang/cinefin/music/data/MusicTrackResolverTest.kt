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

    @Test
    fun `downloaded song routes to the local file without server item or stream requests`() =
        runBlocking {
            val localPath =
                "/storage/emulated/0/Android/data/io.github.zhangwenkang.aurorama/files/downloads/song.g1"
            val repository = mockk<JellyfinRepository>()
            coEvery {
                repository.getMediaSources(itemId, includePath = true, startPositionTicks = 0L)
            } returns
                listOf(
                    FindroidSource(
                        id = "local-source",
                        name = "本地文件",
                        type = FindroidSourceType.LOCAL,
                        path = localPath,
                        size = 28_367_204L,
                        mediaStreams = emptyList(),
                        downloadTaskStatus = "COMPLETED",
                    )
                )
            val resolver =
                MusicTrackResolver(
                    repository = repository,
                    appPreferences = mockk<AppPreferences>(relaxed = true),
                    context = mockk<Context>(relaxed = true),
                )

            val item = resolver.toPlayerItem(downloadedSong())

            assertEquals(localPath, item.mediaSourceUri)
            assertEquals(TrackSourceType.LOCAL, item.trackSource?.sourceType)
            coVerify(exactly = 1) {
                repository.getMediaSources(itemId, includePath = true, startPositionTicks = 0L)
            }
            coVerify(exactly = 0) { repository.getItem(any()) }
            coVerify(exactly = 0) { repository.getStreamUrl(any(), any()) }
            coVerify(exactly = 0) { repository.getMusicStreamUrl(any(), any()) }
        }

    @Test
    fun `local media library song never asks the repository for sources`() = runBlocking {
        val repository = mockk<JellyfinRepository>()
        val resolver =
            MusicTrackResolver(
                repository = repository,
                appPreferences = mockk<AppPreferences>(relaxed = true),
                context = mockk<Context>(relaxed = true),
            )

        resolver.toPlayerItem(
            downloadedSong().copy(localUri = "content://media/external/audio/media/42")
        )

        coVerify(exactly = 0) { repository.getMediaSources(any(), any(), any()) }
        coVerify(exactly = 0) { repository.getItem(any()) }
    }
}
