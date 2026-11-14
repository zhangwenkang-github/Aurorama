package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidEpisodeDto
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidMovieDto
import com.zhangwenkang.cinefin.models.FindroidSourceDto
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.FindroidUserDataDto
import com.zhangwenkang.cinefin.models.selectFindroidSource
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.operations.MediaInfoApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JellyfinOfflinePlaybackFallbackTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `completed movie source skips network for detail and playback info`() = runBlocking {
        val itemId = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val localPath =
            temporaryFolder
                .newFile("offline-movie.mp4")
                .apply { writeBytes(byteArrayOf(1, 2, 3)) }
                .path
        val userLibraryApi = mockk<UserLibraryApi>()
        coEvery { userLibraryApi.getItem(itemId, userId) } throws IOException("offline")
        val offlineMovie =
            mockk<FindroidMovie>(relaxed = true).apply { every { name } returns "offline movie" }
        val fixture = fixture(itemId, userId, userLibraryApi, offlineMovie = offlineMovie)
        coEvery { fixture.database.getCompletedSource(itemId) } returns source(itemId, localPath)
        coEvery { fixture.database.getSources(itemId) } returns listOf(source(itemId, localPath))
        coEvery { fixture.database.getMovie(itemId) } returns movie(itemId)
        coEvery { fixture.database.getUserDataOrCreateNew(itemId, userId) } returns
            userData(itemId, userId)

        val movie = fixture.repository.getMovie(itemId)
        val playbackSources = fixture.repository.getMediaSources(itemId, includePath = true)

        assertEquals("offline movie", movie.name)
        assertEquals(FindroidSourceType.LOCAL, playbackSources.single().type)
        assertEquals(localPath, playbackSources.single().path)
        coVerify(exactly = 0) { userLibraryApi.getItem(any(), any()) }
    }

    @Test
    fun `completed episode source skips network for detail`() = runBlocking {
        val itemId = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val localPath =
            temporaryFolder
                .newFile("offline-episode.mp4")
                .apply { writeBytes(byteArrayOf(1, 2, 3)) }
                .path
        val userLibraryApi = mockk<UserLibraryApi>()
        coEvery { userLibraryApi.getItem(itemId, userId) } throws IOException("offline")
        val offlineEpisode =
            mockk<FindroidEpisode>(relaxed = true).apply {
                every { name } returns "offline episode"
            }
        val fixture = fixture(itemId, userId, userLibraryApi, offlineEpisode = offlineEpisode)
        coEvery { fixture.database.getCompletedSource(itemId) } returns source(itemId, localPath)
        coEvery { fixture.database.getSources(itemId) } returns listOf(source(itemId, localPath))
        coEvery { fixture.database.getEpisode(itemId) } returns episode(itemId)
        coEvery { fixture.database.getUserDataOrCreateNew(itemId, userId) } returns
            userData(itemId, userId)

        val episode = fixture.repository.getEpisode(itemId)

        assertEquals("offline episode", episode.name)
        coVerify(exactly = 0) { userLibraryApi.getItem(any(), any()) }
    }

    @Test
    fun `stale completed local episode falls back for detail and playback`() = runBlocking {
        val itemId = UUID.fromString("66666666-6666-4666-8666-666666666666")
        val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val staleFile = File(temporaryFolder.root, "missing-episode.mkv")
        assertFalse(staleFile.exists())
        val staleSource = source(itemId, staleFile.path)
        val remoteSource = remoteMediaSource()
        val userLibraryApi = mockk<UserLibraryApi>()
        val itemResponse = mockk<Response<BaseItemDto>>(relaxed = true)
        every { itemResponse.content } returns remoteEpisode(itemId, remoteSource)
        coEvery { userLibraryApi.getItem(itemId, userId) } returns itemResponse
        val mediaInfoApi = mockk<MediaInfoApi>()
        val playbackInfoResponse = mockk<Response<PlaybackInfoResponse>>(relaxed = true)
        every { playbackInfoResponse.content } returns
            PlaybackInfoResponse(mediaSources = listOf(remoteSource), playSessionId = "session")
        coEvery { mediaInfoApi.getPostedPlaybackInfo(itemId, any()) } returns playbackInfoResponse
        var downloadedMapperCalls = 0
        val fixture =
            fixture(
                itemId = itemId,
                userId = userId,
                userLibraryApi = userLibraryApi,
                mediaInfoApi = mediaInfoApi,
                onDownloadedEpisodeMapped = { downloadedMapperCalls += 1 },
            )
        coEvery { fixture.database.getCompletedSource(itemId) } returns staleSource
        coEvery { fixture.database.getSources(itemId) } returns listOf(staleSource)
        coEvery { fixture.database.getMediaStreamsBySourceId(any()) } returns emptyList()

        val episode = fixture.repository.getEpisode(itemId)
        val playbackSources = fixture.repository.getMediaSources(itemId, includePath = true)

        assertEquals(0, downloadedMapperCalls)
        assertEquals(FindroidSourceType.REMOTE, selectFindroidSource(episode.sources)?.type)
        assertEquals(REMOTE_SOURCE_ID, selectFindroidSource(playbackSources)?.id)
        coVerify(exactly = 1) { userLibraryApi.getItem(itemId, userId) }
        coVerify(exactly = 1) { mediaInfoApi.getPostedPlaybackInfo(itemId, any()) }
        assertNoDataWrites(fixture.database)
        assertFalse(staleFile.exists())
    }

    @Test
    fun `stale completed local movie falls back for detail and playback`() = runBlocking {
        val itemId = UUID.fromString("77777777-7777-4777-8777-777777777777")
        val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val staleFile = File(temporaryFolder.root, "missing-movie.mkv")
        assertFalse(staleFile.exists())
        val staleSource = source(itemId, staleFile.path)
        val remoteSource = remoteMediaSource()
        val userLibraryApi = mockk<UserLibraryApi>()
        val itemResponse = mockk<Response<BaseItemDto>>(relaxed = true)
        every { itemResponse.content } returns remoteMovie(itemId, remoteSource)
        coEvery { userLibraryApi.getItem(itemId, userId) } returns itemResponse
        val mediaInfoApi = mockk<MediaInfoApi>()
        val playbackInfoResponse = mockk<Response<PlaybackInfoResponse>>(relaxed = true)
        every { playbackInfoResponse.content } returns
            PlaybackInfoResponse(mediaSources = listOf(remoteSource), playSessionId = "session")
        coEvery { mediaInfoApi.getPostedPlaybackInfo(itemId, any()) } returns playbackInfoResponse
        var downloadedMapperCalls = 0
        val fixture =
            fixture(
                itemId = itemId,
                userId = userId,
                userLibraryApi = userLibraryApi,
                mediaInfoApi = mediaInfoApi,
                onDownloadedMovieMapped = { downloadedMapperCalls += 1 },
            )
        coEvery { fixture.database.getCompletedSource(itemId) } returns staleSource
        coEvery { fixture.database.getSources(itemId) } returns listOf(staleSource)
        coEvery { fixture.database.getMediaStreamsBySourceId(any()) } returns emptyList()

        val movie = fixture.repository.getMovie(itemId)
        val playbackSources = fixture.repository.getMediaSources(itemId, includePath = true)

        assertEquals(0, downloadedMapperCalls)
        assertEquals(FindroidSourceType.REMOTE, selectFindroidSource(movie.sources)?.type)
        assertEquals(REMOTE_SOURCE_ID, selectFindroidSource(playbackSources)?.id)
        coVerify(exactly = 1) { userLibraryApi.getItem(itemId, userId) }
        coVerify(exactly = 1) { mediaInfoApi.getPostedPlaybackInfo(itemId, any()) }
        assertNoDataWrites(fixture.database)
        assertFalse(staleFile.exists())
    }

    @Test
    fun `stale completed local source without remote has no selectable source`() = runBlocking {
        val itemId = UUID.fromString("88888888-8888-4888-8888-888888888888")
        val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val staleFile = File(temporaryFolder.root, "missing-without-remote.mkv")
        assertFalse(staleFile.exists())
        val staleSource = source(itemId, staleFile.path)
        val mediaInfoApi = mockk<MediaInfoApi>()
        val playbackInfoResponse = mockk<Response<PlaybackInfoResponse>>(relaxed = true)
        every { playbackInfoResponse.content } returns
            PlaybackInfoResponse(mediaSources = emptyList(), playSessionId = "session")
        coEvery { mediaInfoApi.getPostedPlaybackInfo(itemId, any()) } returns playbackInfoResponse
        val fixture =
            fixture(
                itemId = itemId,
                userId = userId,
                userLibraryApi = mockk(relaxed = true),
                mediaInfoApi = mediaInfoApi,
            )
        coEvery { fixture.database.getCompletedSource(itemId) } returns staleSource
        coEvery { fixture.database.getSources(itemId) } returns listOf(staleSource)
        coEvery { fixture.database.getMediaStreamsBySourceId(any()) } returns emptyList()

        val playbackSources = fixture.repository.getMediaSources(itemId, includePath = true)

        assertTrue(playbackSources.isEmpty())
        assertNull(selectFindroidSource(playbackSources))
        coVerify(exactly = 1) { mediaInfoApi.getPostedPlaybackInfo(itemId, any()) }
        assertNoDataWrites(fixture.database)
        assertFalse(staleFile.exists())
    }

    private fun fixture(
        itemId: UUID,
        userId: UUID,
        userLibraryApi: UserLibraryApi,
        mediaInfoApi: MediaInfoApi = mockk(relaxed = true),
        offlineMovie: FindroidMovie = mockk(relaxed = true),
        offlineEpisode: FindroidEpisode = mockk(relaxed = true),
        onDownloadedMovieMapped: () -> Unit = {},
        onDownloadedEpisodeMapped: () -> Unit = {},
    ): Fixture {
        val apiClient =
            mockk<ApiClient>(relaxed = true).apply { every { baseUrl } returns BASE_URL }
        val api =
            mockk<JellyfinApi>(relaxed = true).apply {
                every { this@apply.userId } returns userId
                every { this@apply.userLibraryApi } returns userLibraryApi
                every { this@apply.mediaInfoApi } returns mediaInfoApi
                every { this@apply.api } returns apiClient
            }
        val database = mockk<ServerDatabaseDao>(relaxed = true)
        val localLibrary = mockk<LocalLibraryRepository>(relaxed = true)
        coEvery { localLibrary.syntheticMovie(itemId) } returns null
        coEvery { localLibrary.syntheticSources(itemId) } returns null
        val repository =
            JellyfinRepositoryImpl(
                context = mockk<Context>(relaxed = true),
                jellyfinApi = api,
                database = database,
                appPreferences = AppPreferences(mockk<SharedPreferences>(relaxed = true)),
                localLibrary = localLibrary,
                metadataSnapshotStore = mockk(relaxed = true),
                downloadedMovieMapper = { _, _ ->
                    onDownloadedMovieMapped()
                    offlineMovie
                },
                downloadedEpisodeMapper = { _, _ ->
                    onDownloadedEpisodeMapped()
                    offlineEpisode
                },
            )
        return Fixture(repository, database)
    }

    private fun source(itemId: UUID, path: String): FindroidSourceDto =
        FindroidSourceDto(
            id = "local-source",
            itemId = itemId,
            name = "local",
            type = FindroidSourceType.LOCAL,
            path = path,
            taskStatus = "COMPLETED",
        )

    private fun remoteMediaSource(): MediaSourceInfo =
        mockk<MediaSourceInfo>(relaxed = true).apply {
            every { protocol } returns MediaProtocol.HTTP
            every { id } returns REMOTE_SOURCE_ID
            every { path } returns REMOTE_URL
            every { name } returns "remote"
            every { size } returns 3L
            every { mediaStreams } returns emptyList()
            every { transcodingUrl } returns null
            every { bitrate } returns 8_000_000
            every { container } returns "mkv"
        }

    private fun remoteEpisode(itemId: UUID, source: MediaSourceInfo): BaseItemDto =
        BaseItemDto(
            id = itemId,
            name = "remote episode",
            type = BaseItemKind.EPISODE,
            seriesId = UUID.fromString("99999999-9999-4999-8999-999999999999"),
            seasonId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            mediaSources = listOf(source),
        )

    private fun remoteMovie(itemId: UUID, source: MediaSourceInfo): BaseItemDto =
        BaseItemDto(
            id = itemId,
            name = "remote movie",
            type = BaseItemKind.MOVIE,
            mediaSources = listOf(source),
        )

    private fun movie(itemId: UUID): FindroidMovieDto =
        FindroidMovieDto(
            id = itemId,
            serverId = null,
            name = "offline movie",
            originalTitle = null,
            overview = "",
            runtimeTicks = 1000L,
            premiereDate = null,
            communityRating = null,
            officialRating = null,
            status = "Released",
            productionYear = null,
            endDate = null,
            chapters = null,
        )

    private fun episode(itemId: UUID): FindroidEpisodeDto =
        FindroidEpisodeDto(
            id = itemId,
            serverId = null,
            seasonId = UUID.fromString("44444444-4444-4444-8444-444444444444"),
            seriesId = UUID.fromString("55555555-5555-4555-8555-555555555555"),
            name = "offline episode",
            seriesName = "offline show",
            overview = "",
            indexNumber = 1,
            indexNumberEnd = null,
            parentIndexNumber = 1,
            runtimeTicks = 1000L,
            premiereDate = null,
            communityRating = null,
            chapters = null,
        )

    private fun userData(itemId: UUID, userId: UUID): FindroidUserDataDto =
        FindroidUserDataDto(
            userId = userId,
            itemId = itemId,
            played = false,
            favorite = false,
            playbackPositionTicks = 0L,
        )

    private suspend fun assertNoDataWrites(database: ServerDatabaseDao) {
        coVerify(exactly = 0) { database.insertSource(any()) }
        coVerify(exactly = 0) {
            database.completeSourceIfCurrent(any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { database.deleteSource(any()) }
        coVerify(exactly = 0) { database.setSourceAllowOffline(any(), any()) }
        coVerify(exactly = 0) { database.deleteMovie(any()) }
        coVerify(exactly = 0) { database.deleteEpisode(any()) }
        coVerify(exactly = 0) { database.setPlaybackPositionTicks(any(), any(), any()) }
    }

    private data class Fixture(
        val repository: JellyfinRepositoryImpl,
        val database: ServerDatabaseDao,
    )

    private companion object {
        const val BASE_URL = "https://example.test/jellyfin"
        const val REMOTE_SOURCE_ID = "remote-source"
        const val REMOTE_URL = "https://example.test/jellyfin/Videos/item/stream"
    }
}
