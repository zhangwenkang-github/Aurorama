package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.models.FindroidSourceDto
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.selectFindroidSource
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.operations.MediaInfoApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * W9-T5（F4）：已下载条目的本地播放源解析契约。
 *
 * 口径：按 itemId 直查下载行，**不依赖当前会话身份**，也不发任何网络请求；文件不在 / 仍是 `.download` 残片才算未命中。
 */
class DownloadedLocalSourceContractTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val itemId = UUID.fromString("cc3b8c4b-9169-53e0-d230-1b581ad5b214")
    private val sessionUserId = UUID.fromString("22222222-2222-4222-8222-222222222222")

    @Test
    fun `downloaded file from another session identity is still playable locally`() = runBlocking {
        val localFile =
            temporaryFolder.newFile("night-song.g1").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val userLibraryApi = mockk<UserLibraryApi>(relaxed = true)
        val mediaInfoApi = mockk<MediaInfoApi>(relaxed = true)
        val fixture = fixture(userLibraryApi, mediaInfoApi)
        coEvery { fixture.database.getSources(itemId) } returns
            listOf(
                downloadedSource(
                    path = localFile.path,
                    taskStatus = "COMPLETED",
                    originServerId = "server-production",
                    originUserId = "33333333-3333-4333-8333-333333333333",
                )
            )

        val source = fixture.repository.getDownloadedLocalSource(itemId)

        assertEquals(localFile.path, source?.path)
        assertEquals(FindroidSourceType.LOCAL, source?.type)
        assertNoNetwork(userLibraryApi, mediaInfoApi)
    }

    @Test
    fun `legacy status text no longer blocks the finished local file`() = runBlocking {
        val localFile =
            temporaryFolder.newFile("legacy-status.g1").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val userLibraryApi = mockk<UserLibraryApi>(relaxed = true)
        val mediaInfoApi = mockk<MediaInfoApi>(relaxed = true)
        val fixture = fixture(userLibraryApi, mediaInfoApi)
        coEvery { fixture.database.getSources(itemId) } returns
            listOf(downloadedSource(path = localFile.path, taskStatus = "Completed"))

        val sources = fixture.repository.getMediaSources(itemId, includePath = true)

        assertEquals(localFile.path, selectFindroidSource(sources)?.path)
        assertEquals(FindroidSourceType.LOCAL, sources.single().type)
        // 已完成下载绝不产出服务器流地址（PlaybackInfo / Items 都不该发）。
        assertNoNetwork(userLibraryApi, mediaInfoApi)
    }

    @Test
    fun `partial download and missing file stay unresolved`() = runBlocking {
        val partialFile =
            temporaryFolder.newFile("partial.m4a.download").apply { writeBytes(byteArrayOf(1)) }
        val userLibraryApi = mockk<UserLibraryApi>(relaxed = true)
        val mediaInfoApi = mockk<MediaInfoApi>(relaxed = true)
        val fixture = fixture(userLibraryApi, mediaInfoApi)
        coEvery { fixture.database.getSources(itemId) } returns
            listOf(
                downloadedSource(path = partialFile.path, taskStatus = "PAUSED"),
                downloadedSource(
                    id = "missing-source",
                    path = File(temporaryFolder.root, "missing.g1").path,
                    taskStatus = "COMPLETED",
                ),
            )

        assertNull(fixture.repository.getDownloadedLocalSource(itemId))
        assertNoNetwork(userLibraryApi, mediaInfoApi)
    }

    @Test
    fun `remote rows are never treated as downloaded files`() = runBlocking {
        val userLibraryApi = mockk<UserLibraryApi>(relaxed = true)
        val mediaInfoApi = mockk<MediaInfoApi>(relaxed = true)
        val fixture = fixture(userLibraryApi, mediaInfoApi)
        coEvery { fixture.database.getSources(itemId) } returns
            listOf(
                downloadedSource(
                        path = "https://jellyfins.zhangwenkang.com/Audio/$itemId/stream.m4a",
                        taskStatus = "COMPLETED",
                    )
                    .copy(type = FindroidSourceType.REMOTE)
            )

        assertNull(fixture.repository.getDownloadedLocalSource(itemId))
        assertNoNetwork(userLibraryApi, mediaInfoApi)
    }

    @Test
    fun `no download rows resolve to nothing without network`() = runBlocking {
        val userLibraryApi = mockk<UserLibraryApi>(relaxed = true)
        val mediaInfoApi = mockk<MediaInfoApi>(relaxed = true)
        val fixture = fixture(userLibraryApi, mediaInfoApi)
        coEvery { fixture.database.getSources(itemId) } returns emptyList()

        assertNull(fixture.repository.getDownloadedLocalSource(itemId))
        assertNoNetwork(userLibraryApi, mediaInfoApi)
    }

    private fun assertNoNetwork(
        userLibraryApi: UserLibraryApi,
        mediaInfoApi: MediaInfoApi,
    ) {
        coVerify(exactly = 0) { mediaInfoApi.getPostedPlaybackInfo(any(), any()) }
        coVerify(exactly = 0) { userLibraryApi.getItem(any(), any()) }
    }

    private fun downloadedSource(
        id: String = "download-source",
        path: String,
        taskStatus: String?,
        originServerId: String? = "server-test",
        originUserId: String? = sessionUserId.toString(),
    ): FindroidSourceDto =
        FindroidSourceDto(
            id = id,
            itemId = itemId,
            name = "夜曲",
            type = FindroidSourceType.LOCAL,
            path = path,
            taskStatus = taskStatus,
            originServerId = originServerId,
            originUserId = originUserId,
        )

    private fun fixture(
        userLibraryApi: UserLibraryApi,
        mediaInfoApi: MediaInfoApi,
    ): Fixture {
        val apiClient =
            mockk<ApiClient>(relaxed = true).apply { every { baseUrl } returns BASE_URL }
        val api =
            mockk<JellyfinApi>(relaxed = true).apply {
                every { this@apply.userId } returns sessionUserId
                every { this@apply.userLibraryApi } returns userLibraryApi
                every { this@apply.mediaInfoApi } returns mediaInfoApi
                every { this@apply.api } returns apiClient
            }
        val database = mockk<ServerDatabaseDao>(relaxed = true)
        val localLibrary = mockk<LocalLibraryRepository>(relaxed = true)
        coEvery { localLibrary.syntheticSources(any()) } returns null
        coEvery { database.getMediaStreamsBySourceId(any()) } returns emptyList()
        val repository =
            JellyfinRepositoryImpl(
                context = mockk<Context>(relaxed = true),
                jellyfinApi = api,
                database = database,
                appPreferences = AppPreferences(mockk<SharedPreferences>(relaxed = true)),
                localLibrary = localLibrary,
                metadataSnapshotStore = mockk(relaxed = true),
            )
        return Fixture(repository, database)
    }

    private data class Fixture(
        val repository: JellyfinRepositoryImpl,
        val database: ServerDatabaseDao,
    )

    private companion object {
        const val BASE_URL = "https://jellyfin-test.zhangwenkang.com/"
    }
}
