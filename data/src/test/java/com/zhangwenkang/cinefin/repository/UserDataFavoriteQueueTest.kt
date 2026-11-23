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
import io.mockk.verify
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W9-T4 契约：音乐收藏也走同一条写补偿队列（WRITE-05 残留）。
 *
 * 音乐条目没有 movies/episodes 投影行，本地收藏位旧实现是纯 UPDATE（静默命中 0 行），排队 `toBeSynced` 也落不了地；
 * 这里钉住「落库前先建行」「可重试失败入队」「权限拒绝零本地改动」三件事。
 */
class UserDataFavoriteQueueTest {
    private val database = mockk<ServerDatabaseDao>(relaxed = true)
    private val userLibraryApi = mockk<UserLibraryApi>(relaxed = true)
    private val scheduler = mockk<UserDataSyncScheduler>(relaxed = true)

    private val api: JellyfinApi =
        mockk<JellyfinApi>(relaxed = true).also { jellyfinApi ->
            every { jellyfinApi.userId } returns USER_ID
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
            userDataSyncScheduler = scheduler,
        )

    @Test
    fun `收藏网络失败落本地行并入队补同步`() = runBlocking {
        coEvery { userLibraryApi.markFavoriteItem(ITEM_ID) } throws IOException("offline")

        val outcome = repository.markAsFavorite(ITEM_ID)

        assertEquals(UserDataWriteOutcome.QueuedForSync, outcome)
        coVerify(exactly = 1) { database.getUserDataOrCreateNew(ITEM_ID, USER_ID) }
        coVerify(exactly = 1) { database.setFavorite(USER_ID, ITEM_ID, true) }
        coVerify(exactly = 1) { database.setUserDataToBeSynced(USER_ID, ITEM_ID, true) }
        verify(exactly = 1) { scheduler.schedule() }
    }

    @Test
    fun `收藏 5xx 同样入队补同步`() = runBlocking {
        coEvery { userLibraryApi.markFavoriteItem(ITEM_ID) } throws
            InvalidStatusException(503, null)

        val outcome = repository.markAsFavorite(ITEM_ID)

        assertEquals(UserDataWriteOutcome.QueuedForSync, outcome)
        coVerify(exactly = 1) { database.setUserDataToBeSynced(USER_ID, ITEM_ID, true) }
        verify(exactly = 1) { scheduler.schedule() }
    }

    @Test
    fun `收藏成功清旧排队标记且不调度`() = runBlocking {
        val outcome = repository.markAsFavorite(ITEM_ID)

        assertEquals(UserDataWriteOutcome.Confirmed, outcome)
        coVerify(exactly = 1) { database.getUserDataOrCreateNew(ITEM_ID, USER_ID) }
        coVerify(exactly = 1) { database.setFavorite(USER_ID, ITEM_ID, true) }
        coVerify(exactly = 1) { database.setUserDataToBeSynced(USER_ID, ITEM_ID, false) }
        coVerify(exactly = 0) { database.setUserDataToBeSynced(USER_ID, ITEM_ID, true) }
        verify(exactly = 0) { scheduler.schedule() }
    }

    @Test
    fun `取消收藏失败同样入队`() = runBlocking {
        coEvery { userLibraryApi.unmarkFavoriteItem(ITEM_ID) } throws IOException("offline")

        val outcome = repository.unmarkAsFavorite(ITEM_ID)

        assertEquals(UserDataWriteOutcome.QueuedForSync, outcome)
        coVerify(exactly = 1) { database.setFavorite(USER_ID, ITEM_ID, false) }
        coVerify(exactly = 1) { database.setUserDataToBeSynced(USER_ID, ITEM_ID, true) }
    }

    @Test
    fun `权限拒绝不改本地也不排队`() = runBlocking {
        coEvery { userLibraryApi.markFavoriteItem(ITEM_ID) } throws
            InvalidStatusException(403, null)

        val outcome = repository.markAsFavorite(ITEM_ID)

        val failure = (outcome as UserDataWriteOutcome.Failed).failure
        assertTrue(failure is UserDataWriteFailure.PermissionDenied)
        coVerify(exactly = 0) { database.getUserDataOrCreateNew(any(), any()) }
        coVerify(exactly = 0) { database.setFavorite(any(), any(), any()) }
        coVerify(exactly = 0) { database.setUserDataToBeSynced(any(), any(), any()) }
        verify(exactly = 0) { scheduler.schedule() }
    }

    private companion object {
        val USER_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val ITEM_ID: UUID = UUID.fromString("66666666-6666-4666-8666-666666666666")
    }
}
