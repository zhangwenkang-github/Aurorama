package com.zhangwenkang.cinefin.music.data

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.MetadataSnapshotStore
import com.zhangwenkang.cinefin.repository.UserDataWriteFailure
import com.zhangwenkang.cinefin.repository.UserDataWriteOutcome
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
import org.jellyfin.sdk.api.operations.PlaylistsApi
import org.jellyfin.sdk.model.api.UpdatePlaylistDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W3-T19 契约：服务器歌单改名只走 `POST /Playlists/{id}`（`UpdatePlaylistDto` 仅带新名、ids 为 null）， 删除走 `DELETE
 * /Items/{id}`（复用视频域删除写边界），换位走 `POST /Playlists/{id}/Items/{itemId}/Move/{index}`。
 */
class MusicServerPlaylistEditContractTest {

    private val playlistsApi = mockk<PlaylistsApi>(relaxed = true)

    private val jellyfinRepository =
        mockk<JellyfinRepository>(relaxed = true).also { repository ->
            every { repository.getBaseUrl() } returns SERVER_URL
        }

    private val api: JellyfinApi =
        mockk<JellyfinApi>(relaxed = true).also { jellyfinApi ->
            every { jellyfinApi.userId } returns USER_ID
            every { jellyfinApi.playlistsApi } returns playlistsApi
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

    private val repository =
        MusicRepositoryImpl(
            jellyfinRepository = jellyfinRepository,
            jellyfinApi = api,
            appPreferences = AppPreferences(preferences),
            metadataSnapshotStore = mockk<MetadataSnapshotStore>(relaxed = true),
        )

    @Test
    fun `改名只提交新名称且不动歌单曲目`() = runBlocking {
        coEvery { playlistsApi.updatePlaylist(any(), any()) } returns
            Response(Unit, 204, emptyMap<String, List<String>>())
        val dto = slot<UpdatePlaylistDto>()
        val playlistId = slot<UUID>()

        val outcome = repository.renameServerPlaylist(PLAYLIST_ID, "  T19验证-新名字  ")

        assertEquals(UserDataWriteOutcome.Confirmed, outcome)
        coVerify(exactly = 1) { playlistsApi.updatePlaylist(capture(playlistId), capture(dto)) }
        assertEquals(PLAYLIST_ID, playlistId.captured)
        assertEquals("T19验证-新名字", dto.captured.name)
        assertNull(dto.captured.ids)
        assertNull(dto.captured.users)
        assertNull(dto.captured.isPublic)
    }

    @Test
    fun `空名称不发请求直接失败`() = runBlocking {
        val outcome = repository.renameServerPlaylist(PLAYLIST_ID, "   ")

        assertTrue(outcome is UserDataWriteOutcome.Failed)
        assertEquals(
            "歌单名不能为空",
            ((outcome as UserDataWriteOutcome.Failed).failure as UserDataWriteFailure.Unknown)
                .message,
        )
        coVerify(exactly = 0) { playlistsApi.updatePlaylist(any(), any()) }
    }

    @Test
    fun `删除走 Items 删除边界并透传结果`() = runBlocking {
        coEvery { jellyfinRepository.deleteItem(PLAYLIST_ID) } returns
            UserDataWriteOutcome.Confirmed

        val outcome = repository.deleteServerPlaylist(PLAYLIST_ID)

        assertEquals(UserDataWriteOutcome.Confirmed, outcome)
        coVerify(exactly = 1) { jellyfinRepository.deleteItem(PLAYLIST_ID) }
    }

    @Test
    fun `删除失败原样返回失败分类`() = runBlocking {
        val failure = UserDataWriteFailure.PermissionDenied(status = 403, message = "forbidden")
        coEvery { jellyfinRepository.deleteItem(PLAYLIST_ID) } returns
            UserDataWriteOutcome.Failed(failure)

        val outcome = repository.deleteServerPlaylist(PLAYLIST_ID)

        assertEquals(UserDataWriteOutcome.Failed(failure), outcome)
    }

    @Test
    fun `换位请求路径参数与目标下标透传`() = runBlocking {
        coEvery { playlistsApi.moveItem(any(), any(), any()) } returns
            Response(Unit, 204, emptyMap<String, List<String>>())
        val playlistArg = slot<String>()
        val itemArg = slot<String>()
        val indexArg = slot<Int>()

        val outcome = repository.moveServerPlaylistItem(PLAYLIST_ID, ENTRY_ID, newIndex = 3)

        assertEquals(UserDataWriteOutcome.Confirmed, outcome)
        coVerify(exactly = 1) {
            playlistsApi.moveItem(capture(playlistArg), capture(itemArg), capture(indexArg))
        }
        assertEquals(PLAYLIST_ID.toString(), playlistArg.captured)
        assertEquals(ENTRY_ID, itemArg.captured)
        assertEquals(3, indexArg.captured)
    }

    @Test
    fun `换位网络失败归为可重试失败`() = runBlocking {
        coEvery { playlistsApi.moveItem(any(), any(), any()) } throws IOException("offline")

        val outcome = repository.moveServerPlaylistItem(PLAYLIST_ID, ENTRY_ID, 1)

        assertTrue(outcome is UserDataWriteOutcome.Failed)
    }

    private companion object {
        val PLAYLIST_ID: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
        const val ENTRY_ID = "77777777-7777-4777-8777-777777777777"
        val USER_ID: UUID = UUID.fromString("66666666-6666-4666-8666-666666666666")
        const val SERVER_ID = "server-a"
        const val SERVER_URL = "https://server.test"
        const val CURRENT_SERVER_KEY = "pref_current_server"
    }
}
