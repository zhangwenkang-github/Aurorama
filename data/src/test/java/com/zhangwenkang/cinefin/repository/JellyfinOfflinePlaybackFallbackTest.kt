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
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.junit.Assert.assertEquals
import org.junit.Test

class JellyfinOfflinePlaybackFallbackTest {
    @Test
    fun `completed movie source skips network for detail and playback info`() = runBlocking {
        val itemId = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val userLibraryApi = mockk<UserLibraryApi>()
        coEvery { userLibraryApi.getItem(itemId, userId) } throws IOException("offline")
        val offlineMovie =
            mockk<FindroidMovie>(relaxed = true).apply { every { name } returns "offline movie" }
        val fixture = fixture(itemId, userId, userLibraryApi, offlineMovie = offlineMovie)
        coEvery { fixture.database.getCompletedSource(itemId) } returns source(itemId)
        coEvery { fixture.database.getSources(itemId) } returns listOf(source(itemId))
        coEvery { fixture.database.getMovie(itemId) } returns movie(itemId)
        coEvery { fixture.database.getUserDataOrCreateNew(itemId, userId) } returns
            userData(itemId, userId)

        val movie = fixture.repository.getMovie(itemId)
        val playbackSources = fixture.repository.getMediaSources(itemId, includePath = true)

        assertEquals("offline movie", movie.name)
        assertEquals(FindroidSourceType.LOCAL, playbackSources.single().type)
        assertEquals("/local/movie.mp4", playbackSources.single().path)
        coVerify(exactly = 0) { userLibraryApi.getItem(any(), any()) }
    }

    @Test
    fun `completed episode source skips network for detail`() = runBlocking {
        val itemId = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val userLibraryApi = mockk<UserLibraryApi>()
        coEvery { userLibraryApi.getItem(itemId, userId) } throws IOException("offline")
        val offlineEpisode =
            mockk<FindroidEpisode>(relaxed = true).apply {
                every { name } returns "offline episode"
            }
        val fixture = fixture(itemId, userId, userLibraryApi, offlineEpisode = offlineEpisode)
        coEvery { fixture.database.getCompletedSource(itemId) } returns source(itemId)
        coEvery { fixture.database.getSources(itemId) } returns listOf(source(itemId))
        coEvery { fixture.database.getEpisode(itemId) } returns episode(itemId)
        coEvery { fixture.database.getUserDataOrCreateNew(itemId, userId) } returns
            userData(itemId, userId)

        val episode = fixture.repository.getEpisode(itemId)

        assertEquals("offline episode", episode.name)
        coVerify(exactly = 0) { userLibraryApi.getItem(any(), any()) }
    }

    private fun fixture(
        itemId: UUID,
        userId: UUID,
        userLibraryApi: UserLibraryApi,
        offlineMovie: FindroidMovie = mockk(relaxed = true),
        offlineEpisode: FindroidEpisode = mockk(relaxed = true),
    ): Fixture {
        val api =
            mockk<JellyfinApi>(relaxed = true).apply {
                every { this@apply.userId } returns userId
                every { this@apply.userLibraryApi } returns userLibraryApi
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
                downloadedMovieMapper = { _, _ -> offlineMovie },
                downloadedEpisodeMapper = { _, _ -> offlineEpisode },
            )
        return Fixture(repository, database)
    }

    private fun source(itemId: UUID): FindroidSourceDto =
        FindroidSourceDto(
            id = "local-source",
            itemId = itemId,
            name = "local",
            type = FindroidSourceType.LOCAL,
            path = "/local/movie.mp4",
            taskStatus = "COMPLETED",
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

    private data class Fixture(
        val repository: JellyfinRepositoryImpl,
        val database: ServerDatabaseDao,
    )
}
