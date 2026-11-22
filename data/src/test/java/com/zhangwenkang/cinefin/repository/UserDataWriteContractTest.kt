package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.operations.PlayStateApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W8-T1 契约：收藏 / 已看只走 SDK 官方写操作（`markFavoriteItem` 系 / `markPlayedItem` 系，V2-WRITE-02）， 参数只有条目
 * id；成功后本地库按同一状态落库。
 */
class UserDataWriteContractTest {
    private val database = mockk<ServerDatabaseDao>(relaxed = true)
    private val userLibraryApi = mockk<UserLibraryApi>(relaxed = true)
    private val playStateApi = mockk<PlayStateApi>(relaxed = true)

    private val api: JellyfinApi =
        mockk<JellyfinApi>(relaxed = true).also { jellyfinApi ->
            every { jellyfinApi.userId } returns USER_ID
            every { jellyfinApi.userLibraryApi } returns userLibraryApi
            every { jellyfinApi.playStateApi } returns playStateApi
        }

    private val preferences =
        mockk<SharedPreferences>(relaxed = true).also { preferences ->
            every { preferences.getBoolean(any(), any()) } answers { secondArg() }
            every { preferences.getInt(any(), any()) } answers { secondArg() }
            every { preferences.getLong(any(), any()) } answers { secondArg() }
            every { preferences.getFloat(any(), any()) } answers { secondArg() }
            every { preferences.getString(any(), any()) } answers { secondArg() }
        }

    private val repository =
        JellyfinRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            jellyfinApi = api,
            database = database,
            appPreferences = AppPreferences(preferences),
            localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
            metadataSnapshotStore = mockk(relaxed = true),
        )

    @Test
    fun `收藏只打 markFavoriteItem 端点`() = runBlocking {
        val outcome = repository.markAsFavorite(ITEM_ID)

        assertEquals(UserDataWriteOutcome.Confirmed, outcome)
        coVerify(exactly = 1) { userLibraryApi.markFavoriteItem(ITEM_ID) }
        coVerify(exactly = 0) { userLibraryApi.unmarkFavoriteItem(any()) }
        coVerify(exactly = 0) { playStateApi.markPlayedItem(any()) }
        coVerify(exactly = 0) { playStateApi.markUnplayedItem(any()) }
        coVerify(exactly = 1) { database.setFavorite(USER_ID, ITEM_ID, true) }
    }

    @Test
    fun `取消收藏只打 unmarkFavoriteItem 端点`() = runBlocking {
        val outcome = repository.unmarkAsFavorite(ITEM_ID)

        assertEquals(UserDataWriteOutcome.Confirmed, outcome)
        coVerify(exactly = 1) { userLibraryApi.unmarkFavoriteItem(ITEM_ID) }
        coVerify(exactly = 0) { userLibraryApi.markFavoriteItem(any()) }
        coVerify(exactly = 1) { database.setFavorite(USER_ID, ITEM_ID, false) }
    }

    @Test
    fun `标记已看只打 markPlayedItem 端点`() = runBlocking {
        val outcome = repository.markAsPlayed(ITEM_ID)

        assertEquals(UserDataWriteOutcome.Confirmed, outcome)
        coVerify(exactly = 1) { playStateApi.markPlayedItem(ITEM_ID) }
        coVerify(exactly = 0) { playStateApi.markUnplayedItem(any()) }
        coVerify(exactly = 0) { userLibraryApi.markFavoriteItem(any()) }
        coVerify(exactly = 1) { database.setPlayed(USER_ID, ITEM_ID, true) }
    }

    @Test
    fun `取消已看只打 markUnplayedItem 端点`() = runBlocking {
        val outcome = repository.markAsUnplayed(ITEM_ID)

        assertEquals(UserDataWriteOutcome.Confirmed, outcome)
        coVerify(exactly = 1) { playStateApi.markUnplayedItem(ITEM_ID) }
        coVerify(exactly = 0) { playStateApi.markPlayedItem(any()) }
        coVerify(exactly = 1) { database.setPlayed(USER_ID, ITEM_ID, false) }
    }

    private companion object {
        val USER_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val ITEM_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}
