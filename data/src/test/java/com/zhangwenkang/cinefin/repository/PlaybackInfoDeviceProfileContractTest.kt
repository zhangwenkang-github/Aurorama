package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.operations.MediaInfoApi
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackInfoDeviceProfileContractTest {
    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `playback info posts capability profile and preserves explicit user id`() = runBlocking {
        val itemId = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")

        val sharedPreferences = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { sharedPreferences.contains(any()) } returns false
        every { sharedPreferences.getBoolean(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getInt(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getLong(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getFloat(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getString(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getStringSet(any(), any()) } answers { secondArg() }
        every { sharedPreferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.putLong(any(), any()) } returns editor
        every { editor.putFloat(any(), any()) } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putStringSet(any(), any()) } returns editor

        val mediaInfoApi = mockk<MediaInfoApi>()
        val request = slot<PlaybackInfoDto>()
        val playbackInfoResponse = mockk<Response<PlaybackInfoResponse>>(relaxed = true)
        every { playbackInfoResponse.content } returns
            PlaybackInfoResponse(mediaSources = emptyList(), playSessionId = "session")
        coEvery { mediaInfoApi.getPostedPlaybackInfo(itemId, capture(request)) } returns
            playbackInfoResponse

        val jellyfinApi =
            mockk<JellyfinApi>(relaxed = true).apply {
                every { this@apply.userId } returns userId
                every { this@apply.mediaInfoApi } returns mediaInfoApi
            }
        val database = mockk<ServerDatabaseDao>(relaxed = true)
        coEvery { database.getCompletedSource(itemId) } returns null
        coEvery { database.getSources(itemId) } returns emptyList()
        val localLibrary = mockk<LocalLibraryRepository>(relaxed = true)
        coEvery { localLibrary.syntheticSources(itemId) } returns null
        val repository =
            JellyfinRepositoryImpl(
                context = mockk<Context>(relaxed = true),
                jellyfinApi = jellyfinApi,
                database = database,
                appPreferences = AppPreferences(sharedPreferences),
                localLibrary = localLibrary,
                metadataSnapshotStore = mockk(relaxed = true),
                downloadedMovieMapper = { _, _ -> error("unused") },
                downloadedEpisodeMapper = { _, _ -> error("unused") },
            )

        repository.getMediaSources(itemId, includePath = true)

        val playbackInfo = request.captured
        val deviceProfile = playbackInfo.deviceProfile
        assertEquals(userId, playbackInfo.userId)
        assertNotNull(deviceProfile)
        requireNotNull(deviceProfile)
        assertFalse(deviceProfile.name.orEmpty().isBlank())
        assertTrue(deviceProfile.directPlayProfiles.isNotEmpty())
        assertTrue(deviceProfile.containerProfiles.isNotEmpty())
        assertTrue(deviceProfile.codecProfiles.isNotEmpty())
        assertTrue(deviceProfile.transcodingProfiles.isNotEmpty())
        assertTrue(
            deviceProfile.directPlayProfiles.any { profile ->
                profile.type == DlnaProfileType.VIDEO &&
                    profile.videoCodec.orEmpty().split(",").contains("h264") &&
                    profile.audioCodec.orEmpty().split(",").contains("aac")
            }
        )
        assertTrue(
            deviceProfile.directPlayProfiles.any { profile ->
                profile.type == DlnaProfileType.AUDIO &&
                    profile.audioCodec.orEmpty().split(",").contains("aac")
            }
        )
        assertTrue(
            deviceProfile.codecProfiles.any { profile ->
                profile.codec == "h264" && profile.container == "mp4"
            }
        )
        assertTrue(
            deviceProfile.transcodingProfiles.any { profile ->
                profile.type == DlnaProfileType.VIDEO && profile.protocol == MediaStreamProtocol.HLS
            }
        )
        assertFalse(playbackInfo.enableDirectPlay == false)
        assertFalse(playbackInfo.enableDirectStream == false)
    }

    @Test
    fun `device profile follows supplied codec capabilities without fixed h264 claim`() {
        val profile =
            PlaybackDeviceProfileFactory.create(
                maxStreamingBitrate = 20_000_000,
                capabilities =
                    PlaybackCodecCapabilities(
                        videoCodecs = setOf("hevc", "vp9"),
                        audioCodecs = setOf("opus"),
                    ),
            )

        assertTrue(
            profile.directPlayProfiles.any { directPlay ->
                directPlay.type == DlnaProfileType.VIDEO &&
                    directPlay.container == "mkv" &&
                    directPlay.videoCodec.orEmpty().split(",").contains("hevc") &&
                    directPlay.videoCodec.orEmpty().split(",").contains("vp9") &&
                    directPlay.audioCodec.orEmpty().split(",").contains("opus")
            }
        )
        assertTrue(profile.codecProfiles.any { it.codec == "hevc" && it.container == "mkv" })
        assertFalse(profile.codecProfiles.any { it.codec == "h264" })
    }
}
