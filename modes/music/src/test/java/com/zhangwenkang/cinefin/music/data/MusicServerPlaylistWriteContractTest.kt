package com.zhangwenkang.cinefin.music.data

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.MetadataNamespace
import com.zhangwenkang.cinefin.repository.MetadataSnapshotStore
import com.zhangwenkang.cinefin.repository.UserDataWriteFailure
import com.zhangwenkang.cinefin.repository.UserDataWriteOutcome
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.operations.PlaylistsApi
import org.jellyfin.sdk.model.api.CreatePlaylistDto
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlaylistCreationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W8-T4 契约：服务器歌单新建 / 加入只走 SDK 官方写操作（`createPlaylist` / `addItemToPlaylist`），
 * 参数按服务端协议映射；成功后清歌单卡片缓存，失败沿用 W8-T1 的失败分类且不动缓存。
 */
class MusicServerPlaylistWriteContractTest {
    private val playlistsApi = mockk<PlaylistsApi>(relaxed = true)
    private val metadataSnapshotStore = mockk<MetadataSnapshotStore>(relaxed = true)

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
            jellyfinRepository = mockk<JellyfinRepository>(relaxed = true),
            jellyfinApi = api,
            appPreferences = AppPreferences(preferences),
            metadataSnapshotStore = metadataSnapshotStore,
        )

    @Test
    fun `新建歌单只打 createPlaylist 并按音频协议映射参数`() = runBlocking {
        // Jellyfin 10.11 实测返回 32 位无连字符十六进制 id（不是标准 UUID 文本）。
        coEvery { playlistsApi.createPlaylist(any()) } returns
            Response(PlaylistCreationResult(WIRE_PLAYLIST_ID_HEX), 200, emptyMap())
        val dto = slot<CreatePlaylistDto>()
        val itemIds = listOf(ITEM_ID, OTHER_ITEM_ID)

        val outcome = repository.createServerPlaylist(" 通勤歌单 ", itemIds)

        assertEquals(ServerPlaylistCreateOutcome.Created(WIRE_PLAYLIST_ID), outcome)
        coVerify(exactly = 1) { playlistsApi.createPlaylist(capture(dto)) }
        coVerify(exactly = 0) { playlistsApi.addItemToPlaylist(any(), any(), any()) }
        assertEquals(" 通勤歌单 ", dto.captured.name)
        assertEquals(itemIds, dto.captured.ids)
        assertEquals(USER_ID, dto.captured.userId)
        assertEquals(MediaType.AUDIO, dto.captured.mediaType)
        assertEquals(emptyList<Any>(), dto.captured.users)
        assertFalse(dto.captured.isPublic)
        verify(exactly = 1) { metadataSnapshotStore.delete(NAMESPACE, PLAYLIST_CARD_KEY) }
    }

    @Test
    fun `新建歌单不带曲目时 ids 为空列表`() = runBlocking {
        coEvery { playlistsApi.createPlaylist(any()) } returns
            Response(PlaylistCreationResult(WIRE_PLAYLIST_ID_HEX), 200, emptyMap())
        val dto = slot<CreatePlaylistDto>()

        repository.createServerPlaylist("新歌单")

        coVerify(exactly = 1) { playlistsApi.createPlaylist(capture(dto)) }
        assertTrue(dto.captured.ids.isEmpty())
    }

    @Test
    fun `新建歌单返回标准带连字符 id 同样兼容`() = runBlocking {
        coEvery { playlistsApi.createPlaylist(any()) } returns
            Response(PlaylistCreationResult(CREATED_PLAYLIST_ID.toString()), 200, emptyMap())

        val outcome = repository.createServerPlaylist("新歌单")

        assertEquals(ServerPlaylistCreateOutcome.Created(CREATED_PLAYLIST_ID), outcome)
        verify(exactly = 1) { metadataSnapshotStore.delete(NAMESPACE, PLAYLIST_CARD_KEY) }
    }

    @Test
    fun `新建歌单 403 返回权限拒绝且不清缓存`() = runBlocking {
        coEvery { playlistsApi.createPlaylist(any()) } throws InvalidStatusException(403, null)

        val outcome = repository.createServerPlaylist("受限歌单")

        val failure = (outcome as ServerPlaylistCreateOutcome.Failed).failure
        assertTrue(failure is UserDataWriteFailure.PermissionDenied)
        assertEquals(403, (failure as UserDataWriteFailure.PermissionDenied).status)
        verify(exactly = 0) { metadataSnapshotStore.delete(any(), any()) }
    }

    @Test
    fun `新建歌单 5xx 返回服务器错误且不清缓存`() = runBlocking {
        coEvery { playlistsApi.createPlaylist(any()) } throws InvalidStatusException(502, null)

        val outcome = repository.createServerPlaylist("新歌单")

        val failure = (outcome as ServerPlaylistCreateOutcome.Failed).failure
        assertTrue(failure is UserDataWriteFailure.ServerError)
        assertEquals(502, (failure as UserDataWriteFailure.ServerError).status)
        verify(exactly = 0) { metadataSnapshotStore.delete(any(), any()) }
    }

    @Test
    fun `新建歌单服务器返回非法 id 归为未知失败`() = runBlocking {
        coEvery { playlistsApi.createPlaylist(any()) } returns
            Response(PlaylistCreationResult("not-a-uuid"), 200, emptyMap())

        val outcome = repository.createServerPlaylist("新歌单")

        assertTrue(
            (outcome as ServerPlaylistCreateOutcome.Failed).failure is UserDataWriteFailure.Unknown
        )
        verify(exactly = 0) { metadataSnapshotStore.delete(any(), any()) }
    }

    @Test
    fun `加入歌单只打一次 addItemToPlaylist 并清缓存`() = runBlocking {
        coEvery { playlistsApi.addItemToPlaylist(any(), any(), any()) } returns
            Response(Unit, 200, emptyMap())
        val itemIds = listOf(ITEM_ID, OTHER_ITEM_ID)

        val outcome = repository.addToPlaylist(PLAYLIST_ID, itemIds)

        assertEquals(UserDataWriteOutcome.Confirmed, outcome)
        coVerify(exactly = 1) { playlistsApi.addItemToPlaylist(PLAYLIST_ID, itemIds, USER_ID) }
        coVerify(exactly = 0) { playlistsApi.createPlaylist(any()) }
        verify(exactly = 1) { metadataSnapshotStore.delete(NAMESPACE, PLAYLIST_CARD_KEY) }
    }

    @Test
    fun `加入歌单 403 返回权限拒绝且不清缓存`() = runBlocking {
        coEvery { playlistsApi.addItemToPlaylist(any(), any(), any()) } throws
            InvalidStatusException(403, null)

        val outcome = repository.addToPlaylist(PLAYLIST_ID, listOf(ITEM_ID))

        val failure = (outcome as UserDataWriteOutcome.Failed).failure
        assertTrue(failure is UserDataWriteFailure.PermissionDenied)
        verify(exactly = 0) { metadataSnapshotStore.delete(any(), any()) }
    }

    @Test
    fun `加入歌单网络中断归为可重试网络失败`() = runBlocking {
        coEvery { playlistsApi.addItemToPlaylist(any(), any(), any()) } throws
            IOException("connection reset")

        val outcome = repository.addToPlaylist(PLAYLIST_ID, listOf(ITEM_ID))

        val failure = (outcome as UserDataWriteOutcome.Failed).failure
        assertTrue(failure is UserDataWriteFailure.Network)
        verify(exactly = 0) { metadataSnapshotStore.delete(any(), any()) }
    }

    private companion object {
        const val SERVER_ID = "server-a"
        const val CURRENT_SERVER_KEY = "pref_current_server"
        const val PLAYLIST_CARD_KEY = "music-playlists:card:v1:200"
        val USER_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val ITEM_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val OTHER_ITEM_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val PLAYLIST_ID: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val CREATED_PLAYLIST_ID: UUID = UUID.fromString("55555555-5555-4555-8555-555555555555")
        /** 真实线格式：Jellyfin `POST /Playlists` 返回的 32 位无连字符十六进制 id。 */
        const val WIRE_PLAYLIST_ID_HEX = "8f3d487b0e86f69c0a841aa9aaa157ac"
        val WIRE_PLAYLIST_ID: UUID = UUID.fromString("8f3d487b-0e86-f69c-0a84-1aa9aaa157ac")
        val NAMESPACE = MetadataNamespace(serverId = SERVER_ID, userId = USER_ID.toString())
    }
}
