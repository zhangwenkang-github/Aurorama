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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W8-T1r：离线实现的排队写同样触发补同步（离线写本来就是「等联网回传」）。 */
class UserDataWriteOfflineScheduleTest {
    private val scheduler = CountingOfflineSyncScheduler()
    private val database = mockk<ServerDatabaseDao>(relaxed = true)

    @Test
    fun `离线收藏落本地后调度一次补同步`() = runBlocking {
        val outcome = repository(USER_ID).markAsFavorite(ITEM_ID)

        assertEquals(UserDataWriteOutcome.QueuedForSync, outcome)
        coVerify(exactly = 1) { database.setFavorite(USER_ID, ITEM_ID, true) }
        coVerify(exactly = 1) { database.setUserDataToBeSynced(USER_ID, ITEM_ID, true) }
        assertEquals(1, scheduler.calls)
    }

    @Test
    fun `无账号离线写不调度补同步`() = runBlocking {
        val outcome = repository(null).markAsPlayed(ITEM_ID)

        assertTrue(outcome is UserDataWriteOutcome.Failed)
        coVerify(exactly = 0) { database.setPlayed(any(), any(), any()) }
        assertEquals(0, scheduler.calls)
    }

    private fun repository(userId: UUID?): JellyfinRepositoryOfflineImpl {
        val api =
            mockk<JellyfinApi>(relaxed = true).also { jellyfinApi ->
                every { jellyfinApi.userId } returns userId
            }
        return JellyfinRepositoryOfflineImpl(
            context = mockk<Context>(relaxed = true),
            jellyfinApi = api,
            database = database,
            appPreferences = AppPreferences(mockk<SharedPreferences>(relaxed = true)),
            localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
            userDataSyncScheduler = scheduler,
        )
    }

    private companion object {
        val USER_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val ITEM_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}

private class CountingOfflineSyncScheduler : UserDataSyncScheduler {
    var calls: Int = 0
        private set

    override fun schedule() {
        calls++
    }
}
