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
import io.mockk.slot
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W8-T3 契约 / 行为：评分写端点映射（写 = `ItemsApi.updateItemUserData`；清除 =
 * `UserLibraryApi.deleteUserItemRating`， 实测 `Rating=null` 不清除）与失败分类（无本地镜像 → 不排队，直接 Failed）。
 */
class PersonalRatingWriteTest {
    private val database = mockk<ServerDatabaseDao>(relaxed = true)
    private val itemsApi = mockk<ItemsApi>(relaxed = true)
    private val userLibraryApi = mockk<UserLibraryApi>(relaxed = true)

    private val api: JellyfinApi =
        mockk<JellyfinApi>(relaxed = true).also { jellyfinApi ->
            every { jellyfinApi.userId } returns USER_ID
            every { jellyfinApi.itemsApi } returns itemsApi
            every { jellyfinApi.userLibraryApi } returns userLibraryApi
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
    fun `写评分只打 updateItemUserData 且透传 7点5`() = runBlocking {
        val data = slot<UpdateUserItemDataDto>()
        coEvery { itemsApi.updateItemUserData(ITEM_ID, USER_ID, capture(data)) } returns response

        val outcome = repository.setPersonalRating(ITEM_ID, 7.5)

        assertEquals(UserDataWriteOutcome.Confirmed, outcome)
        assertEquals(7.5, data.captured.rating)
        coVerify(exactly = 1) { itemsApi.updateItemUserData(ITEM_ID, USER_ID, any()) }
        coVerify(exactly = 0) { userLibraryApi.deleteUserItemRating(any(), any()) }
    }

    @Test
    fun `清除评分走 deleteUserItemRating 而不是 updateItemUserData`() = runBlocking {
        coEvery { userLibraryApi.deleteUserItemRating(ITEM_ID, USER_ID) } returns response

        val outcome = repository.setPersonalRating(ITEM_ID, null)

        assertEquals(UserDataWriteOutcome.Confirmed, outcome)
        coVerify(exactly = 1) { userLibraryApi.deleteUserItemRating(ITEM_ID, USER_ID) }
        coVerify(exactly = 0) { itemsApi.updateItemUserData(any(), any(), any()) }
    }

    @Test
    fun `网络失败不排队直接可重试失败`() = runBlocking {
        coEvery { itemsApi.updateItemUserData(ITEM_ID, USER_ID, any()) } throws
            IOException("offline")

        val outcome = repository.setPersonalRating(ITEM_ID, 8.0)

        assertTrue(outcome is UserDataWriteOutcome.Failed)
        val failure = (outcome as UserDataWriteOutcome.Failed).failure
        assertTrue(failure is UserDataWriteFailure.Network)
        assertTrue(failure.retryable)
        coVerify(exactly = 0) { database.setUserDataToBeSynced(any(), any(), any()) }
    }

    @Test
    fun `403 归为权限拒绝`() = runBlocking {
        coEvery { itemsApi.updateItemUserData(ITEM_ID, USER_ID, any()) } throws
            org.jellyfin.sdk.api.client.exception.InvalidStatusException(403)

        val outcome = repository.setPersonalRating(ITEM_ID, 8.0)

        assertTrue(outcome is UserDataWriteOutcome.Failed)
        assertTrue(
            (outcome as UserDataWriteOutcome.Failed).failure
                is UserDataWriteFailure.PermissionDenied
        )
    }

    private val response: org.jellyfin.sdk.api.client.Response<UserItemDataDto> =
        mockk(relaxed = true)

    private companion object {
        val USER_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val ITEM_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}
