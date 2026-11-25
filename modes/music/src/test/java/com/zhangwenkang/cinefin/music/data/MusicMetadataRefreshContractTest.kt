package com.zhangwenkang.cinefin.music.data

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.MetadataSnapshotStore
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.operations.ItemRefreshApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MetadataRefreshMode
import org.jellyfin.sdk.model.api.request.RefreshItemRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W3-T20 契约：单曲刷新只走 `POST /Items/{id}/Refresh`（Default 模式、不覆盖用户元数据 / 图片）； 回读走 `GET
 * /Users/{userId}/Items/{itemId}` 并映射封面。
 */
class MusicMetadataRefreshContractTest {

    private val itemRefreshApi = mockk<ItemRefreshApi>(relaxed = true)
    private val userLibraryApi = mockk<UserLibraryApi>(relaxed = true)

    private val api: JellyfinApi =
        mockk<JellyfinApi>(relaxed = true).also { jellyfinApi ->
            every { jellyfinApi.userId } returns USER_ID
            every { jellyfinApi.itemRefreshApi } returns itemRefreshApi
            every { jellyfinApi.userLibraryApi } returns userLibraryApi
        }

    private val preferences =
        mockk<SharedPreferences>(relaxed = true).also { preferences ->
            every { preferences.getString(any(), any()) } answers
                {
                    if (firstArg<String>() == CURRENT_SERVER_KEY) SERVER_ID else secondArg()
                }
            every { preferences.getBoolean(any(), any()) } answers { secondArg() }
            every { preferences.getInt(any(), any()) } answers { secondArg() }
            every { preferences.getLong(any(), any()) } answers { secondArg() }
            every { preferences.getFloat(any(), any()) } answers { secondArg() }
        }

    private val jellyfinRepository =
        mockk<JellyfinRepository>(relaxed = true).also { repository ->
            every { repository.getBaseUrl() } returns "https://server.test"
        }

    private val repository =
        MusicRepositoryImpl(
            jellyfinRepository = jellyfinRepository,
            jellyfinApi = api,
            appPreferences = AppPreferences(preferences),
            metadataSnapshotStore = mockk<MetadataSnapshotStore>(relaxed = true),
        )

    @Test
    fun `刷新请求用 Default 模式且不覆盖用户元数据与图片`() = runBlocking {
        coEvery { itemRefreshApi.refreshItem(any<RefreshItemRequest>()) } returns
            Response(Unit, 204, emptyMap<String, List<String>>())
        val request = slot<RefreshItemRequest>()

        val result = repository.refreshSongMetadata(ITEM_ID)

        assertEquals(SongMetadataRefreshResult.Refreshed, result)
        coVerify(exactly = 1) { itemRefreshApi.refreshItem(capture(request)) }
        assertEquals(ITEM_ID, request.captured.itemId)
        assertEquals(MetadataRefreshMode.DEFAULT, request.captured.metadataRefreshMode)
        assertEquals(MetadataRefreshMode.DEFAULT, request.captured.imageRefreshMode)
        assertEquals(false, request.captured.replaceAllMetadata)
        assertEquals(false, request.captured.replaceAllImages)
    }

    @Test
    fun `刷新失败归为 Failed 且不抛给调用方`() = runBlocking {
        coEvery { itemRefreshApi.refreshItem(any<RefreshItemRequest>()) } throws IOException("boom")

        val result = repository.refreshSongMetadata(ITEM_ID)

        assertTrue(result is SongMetadataRefreshResult.Failed)
        assertEquals("boom", (result as SongMetadataRefreshResult.Failed).message)
    }

    @Test
    fun `回读单曲按 id 取 Item 并映射封面`() = runBlocking {
        val dto =
            BaseItemDto(
                id = ITEM_ID,
                type = BaseItemKind.AUDIO,
                name = "曲目",
                album = "专辑",
                imageTags = mapOf(ImageType.PRIMARY to "tag-1"),
            )
        coEvery { userLibraryApi.getItem(ITEM_ID, USER_ID) } returns Response(dto, 200, emptyMap())

        val song = repository.getSong(ITEM_ID)

        coVerify(exactly = 1) { userLibraryApi.getItem(ITEM_ID, USER_ID) }
        assertEquals(ITEM_ID, song?.itemId)
        assertEquals("曲目", song?.name)
        assertTrue(song?.imageUri?.contains(ITEM_ID.toString()) == true)
    }

    @Test
    fun `回读失败返回 null`() = runBlocking {
        coEvery { userLibraryApi.getItem(any(), any()) } throws IOException("offline")

        assertNull(repository.getSong(ITEM_ID))
    }

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        const val SERVER_ID = "server-a"
        const val CURRENT_SERVER_KEY = "pref_current_server"
    }
}
