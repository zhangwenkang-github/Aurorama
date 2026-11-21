package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.models.DownloadPathKind
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.ServerAddress
import com.zhangwenkang.cinefin.models.User
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.operations.LibraryApi
import org.jellyfin.sdk.api.operations.MediaInfoApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JellyfinDownloadSourceContractTest {
    @Test
    fun `普通 VOD 下载源只生成官方 Download GET 且不调用 PlaybackInfo POST`() = runBlocking {
        val mediaSource = mediaSource()
        val item =
            BaseItemDto(
                id = ITEM_ID,
                name = "download-contract.mkv",
                type = BaseItemKind.MOVIE,
                canDownload = true,
                container = "mkv",
                mediaSources = listOf(mediaSource),
            )
        val harness = harness(item, mediaSource)

        val originItem = harness.repository.getItemForOrigin(ITEM_ID, ORIGIN)
        val resolved = harness.repository.getMediaSourcesForOrigin(ITEM_ID, ORIGIN)

        val itemSource = (originItem as FindroidMovie).sources.single()
        val downloadSource = resolved.single()
        listOf(itemSource, downloadSource).forEach { source ->
            assertEquals(DOWNLOAD_URL, source.path)
            assertEquals(MEDIA_SOURCE_ID, source.id)
            assertEquals(12_345L, source.size)
            assertEquals("mkv", source.container)
            assertNull(source.transcodingPath)
            assertEquals("DOWNLOAD_GET", source.downloadMarker?.protocol)
            assertEquals(DownloadPathKind.OFFICIAL_DOWNLOAD, source.downloadMarker?.pathKind)
        }
        assertNull(itemSource.path.substringAfter('?', "").takeIf(String::isNotEmpty))
        assertEquals(true, itemSource.path.endsWith("/Items/$ITEM_ID/Download"))
        verify(exactly = 2) { harness.libraryApi.getDownloadUrl(ITEM_ID) }
        coVerify(exactly = 0) { harness.mediaInfoApi.getPostedPlaybackInfo(any(), any()) }
    }

    @Test
    fun `音乐下载源使用源文件容器的 stream 端点且不带凭据参数`() = runBlocking {
        val mediaSource = mediaSource(container = "flac")
        val item =
            BaseItemDto(
                id = ITEM_ID,
                name = "download-contract.flac",
                type = BaseItemKind.AUDIO,
                canDownload = true,
                container = "flac",
                mediaSources = listOf(mediaSource),
            )
        val harness = harness(item, mediaSource)

        val originItem = harness.repository.getItemForOrigin(ITEM_ID, ORIGIN)
        val resolved = harness.repository.getMediaSourcesForOrigin(ITEM_ID, ORIGIN)

        listOf((originItem as FindroidMovie).sources.single(), resolved.single()).forEach { source
            ->
            assertEquals("$BASE_URL/Audio/$ITEM_ID/stream.flac?static=true", source.path)
            assertEquals(DownloadPathKind.OFFICIAL_DOWNLOAD, source.downloadMarker?.pathKind)
        }
        // 只带 static 一个协议参数：令牌不进 URL（鉴权靠下载引擎的 Authorization 头）
        assertEquals(setOf("static"), resolved.single().path.toHttpUrl().queryParameterNames)
    }

    @Test
    fun `音乐容器缺失时回退官方 Download 端点`() = runBlocking {
        val mediaSource = mediaSource(container = null)
        val item =
            BaseItemDto(
                id = ITEM_ID,
                name = "download-contract-no-container",
                type = BaseItemKind.AUDIO,
                canDownload = true,
                mediaSources = listOf(mediaSource),
            )
        val harness = harness(item, mediaSource)

        val resolved = harness.repository.getMediaSourcesForOrigin(ITEM_ID, ORIGIN).single()

        assertEquals(DOWNLOAD_URL, resolved.path)
        assertEquals(DownloadPathKind.OFFICIAL_DOWNLOAD, resolved.downloadMarker?.pathKind)
    }

    private data class Harness(
        val repository: JellyfinRepositoryImpl,
        val libraryApi: LibraryApi,
        val mediaInfoApi: MediaInfoApi,
    )

    private fun harness(item: BaseItemDto, mediaSource: MediaSourceInfo): Harness {
        val itemResponse = mockk<Response<BaseItemDto>>(relaxed = true)
        every { itemResponse.content } returns item

        val libraryApi = mockk<LibraryApi>()
        every { libraryApi.getDownloadUrl(ITEM_ID) } returns DOWNLOAD_URL

        val mediaInfoApi = mockk<MediaInfoApi>()
        val playbackInfoResponse = mockk<PlaybackInfoResponse>(relaxed = true)
        every { playbackInfoResponse.mediaSources } returns listOf(mediaSource)
        val playbackInfoHttpResponse = mockk<Response<PlaybackInfoResponse>>(relaxed = true)
        every { playbackInfoHttpResponse.content } returns playbackInfoResponse
        coEvery { mediaInfoApi.getPostedPlaybackInfo(ITEM_ID, any()) } returns
            playbackInfoHttpResponse

        val userLibraryApi = mockk<UserLibraryApi>()
        coEvery { userLibraryApi.getItem(ITEM_ID, USER_ID) } returns itemResponse

        val originApiClient = mockk<ApiClient>(relaxed = true)
        val originApi =
            mockk<JellyfinApi>(relaxed = true).apply { every { api } returns originApiClient }
        every { originApi.userLibraryApi } returns userLibraryApi
        every { originApi.libraryApi } returns libraryApi
        every { originApi.mediaInfoApi } returns mediaInfoApi

        val database = mockk<ServerDatabaseDao>(relaxed = true)
        coEvery { database.getUser(USER_ID) } returns
            User(
                id = USER_ID,
                name = "downloader",
                serverId = SERVER_ID,
                accessToken = ACCESS_TOKEN,
            )
        coEvery { database.getServerCurrentAddress(SERVER_ID) } returns
            ServerAddress(
                id = UUID.fromString("33333333-3333-4333-8333-333333333333"),
                serverId = SERVER_ID,
                address = BASE_URL,
            )
        coEvery { database.getSources(ITEM_ID) } returns emptyList()
        val currentApi =
            mockk<JellyfinApi>(relaxed = true).apply {
                every { userId } returns USER_ID
                every { api.baseUrl } returns BASE_URL
                every { api.accessToken } returns ACCESS_TOKEN
            }
        val preferences = mockk<SharedPreferences>(relaxed = true)
        every { preferences.getBoolean(any(), any()) } answers { secondArg() }
        every { preferences.getInt(any(), any()) } answers { secondArg() }
        every { preferences.getLong(any(), any()) } answers { secondArg() }
        every { preferences.getFloat(any(), any()) } answers { secondArg() }
        every { preferences.getString(any(), any()) } answers { secondArg() }
        val repository =
            JellyfinRepositoryImpl(
                context = mockk<Context>(relaxed = true),
                jellyfinApi = currentApi,
                database = database,
                appPreferences = AppPreferences(preferences),
                localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
                metadataSnapshotStore = mockk(relaxed = true),
                originApiFactory = { originApi },
            )
        return Harness(repository, libraryApi, mediaInfoApi)
    }

    private fun mediaSource(container: String? = "mkv"): MediaSourceInfo =
        MediaSourceInfo(
            protocol = MediaProtocol.FILE,
            id = MEDIA_SOURCE_ID,
            path = "/media/download-contract.$container",
            type = MediaSourceType.DEFAULT,
            container = container,
            size = 12_345L,
            name = "download-contract.$container",
            isRemote = false,
            readAtNativeFramerate = false,
            ignoreDts = false,
            ignoreIndex = false,
            genPtsInput = false,
            supportsTranscoding = true,
            supportsDirectStream = true,
            supportsDirectPlay = true,
            isInfiniteStream = false,
            requiresOpening = false,
            requiresClosing = false,
            requiresLooping = false,
            supportsProbing = true,
            bitrate = 8_000_000,
            transcodingUrl = "/Videos/$ITEM_ID/master.m3u8",
            transcodingSubProtocol = MediaStreamProtocol.HLS,
            hasSegments = false,
        )

    private companion object {
        const val SERVER_ID = "server-download-contract"
        const val BASE_URL = "https://origin.example/jellyfin"
        const val ACCESS_TOKEN = "token-not-logged"
        const val MEDIA_SOURCE_ID = "media-source-a"
        val USER_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val ITEM_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val ORIGIN = JellyfinOrigin(SERVER_ID, USER_ID)
        val DOWNLOAD_URL = "$BASE_URL/Items/$ITEM_ID/Download"
    }
}
