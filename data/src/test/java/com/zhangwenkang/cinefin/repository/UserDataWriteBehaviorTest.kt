package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.operations.PlayStateApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W8-T1：写行为——权限拒绝不落本地、网络 / 5xx 保留补偿并返回排队语义。 */
class UserDataWriteBehaviorTest {
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
    fun `403 收藏失败不落本地也不标待同步`() = runBlocking {
        coEvery { userLibraryApi.markFavoriteItem(ITEM_ID) } throws InvalidStatusException(403)

        val outcome = repository.markAsFavorite(ITEM_ID)

        assertTrue(outcome is UserDataWriteOutcome.Failed)
        val failure = (outcome as UserDataWriteOutcome.Failed).failure
        assertTrue(failure is UserDataWriteFailure.PermissionDenied)
        assertEquals(403, (failure as UserDataWriteFailure.PermissionDenied).status)
        coVerify(exactly = 0) { database.setFavorite(any(), any(), any()) }
        coVerify(exactly = 0) { database.setUserDataToBeSynced(any(), any(), any()) }
    }

    @Test
    fun `403 取消收藏失败不回写本地`() = runBlocking {
        coEvery { userLibraryApi.unmarkFavoriteItem(ITEM_ID) } throws InvalidStatusException(403)

        val outcome = repository.unmarkAsFavorite(ITEM_ID)

        assertTrue(outcome is UserDataWriteOutcome.Failed)
        assertTrue(
            (outcome as UserDataWriteOutcome.Failed).failure
                is UserDataWriteFailure.PermissionDenied
        )
        coVerify(exactly = 0) { database.setFavorite(any(), any(), any()) }
        coVerify(exactly = 0) { database.setUserDataToBeSynced(any(), any(), any()) }
    }

    @Test
    fun `网络失败收藏落本地并标待同步`() = runBlocking {
        coEvery { userLibraryApi.markFavoriteItem(ITEM_ID) } throws IOException("offline")

        val outcome = repository.markAsFavorite(ITEM_ID)

        assertEquals(UserDataWriteOutcome.QueuedForSync, outcome)
        coVerify(exactly = 1) { database.setFavorite(USER_ID, ITEM_ID, true) }
        coVerify(exactly = 1) { database.setUserDataToBeSynced(USER_ID, ITEM_ID, true) }
    }

    @Test
    fun `网络失败标记已看落本地并标待同步`() = runBlocking {
        coEvery { playStateApi.markPlayedItem(ITEM_ID) } throws IOException("offline")

        val outcome = repository.markAsPlayed(ITEM_ID)

        assertEquals(UserDataWriteOutcome.QueuedForSync, outcome)
        coVerify(exactly = 1) { database.setPlayed(USER_ID, ITEM_ID, true) }
        coVerify(exactly = 1) { database.setUserDataToBeSynced(USER_ID, ITEM_ID, true) }
    }

    @Test
    fun `5xx 取消已看落本地并标待同步`() = runBlocking {
        coEvery { playStateApi.markUnplayedItem(ITEM_ID) } throws InvalidStatusException(503)

        val outcome = repository.markAsUnplayed(ITEM_ID)

        assertEquals(UserDataWriteOutcome.QueuedForSync, outcome)
        coVerify(exactly = 1) { database.setPlayed(USER_ID, ITEM_ID, false) }
        coVerify(exactly = 1) { database.setUserDataToBeSynced(USER_ID, ITEM_ID, true) }
    }

    @Test
    fun `被 ApiClientException 包裹的网络失败同样走排队补偿`() = runBlocking {
        coEvery { playStateApi.markPlayedItem(ITEM_ID) } throws
            ApiClientException("wrapped", IOException("connection reset"))

        val outcome = repository.markAsPlayed(ITEM_ID)

        assertEquals(UserDataWriteOutcome.QueuedForSync, outcome)
        coVerify(exactly = 1) { database.setPlayed(USER_ID, ITEM_ID, true) }
        coVerify(exactly = 1) { database.setUserDataToBeSynced(USER_ID, ITEM_ID, true) }
    }

    @Test
    fun `服务器确认后落本地且不标待同步`() = runBlocking {
        val outcome = repository.markAsPlayed(ITEM_ID)

        assertEquals(UserDataWriteOutcome.Confirmed, outcome)
        coVerify(exactly = 1) { database.setPlayed(USER_ID, ITEM_ID, true) }
        coVerify(exactly = 1) { playStateApi.markPlayedItem(ITEM_ID) }
        coVerify(exactly = 0) { database.setUserDataToBeSynced(any(), any(), any()) }
    }

    private companion object {
        val USER_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val ITEM_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}
