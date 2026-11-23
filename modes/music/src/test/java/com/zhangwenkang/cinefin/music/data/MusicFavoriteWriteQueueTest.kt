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
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W9-T4 契约：音乐收藏复用影视详情路径的写模型（`JellyfinRepository.markAsFavorite`），不再单独直连 SDK。
 *
 * 只有走同一入口，网络 / 5xx 的 `QueuedForSync` 补偿语义才与影视一致（WRITE-05 残留收口）。
 */
class MusicFavoriteWriteQueueTest {
    private val jellyfinRepository = mockk<JellyfinRepository>(relaxed = true)
    private val userLibraryApi = mockk<UserLibraryApi>(relaxed = true)

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
        MusicRepositoryImpl(
            jellyfinRepository = jellyfinRepository,
            jellyfinApi = api,
            appPreferences = AppPreferences(preferences),
            metadataSnapshotStore = mockk<MetadataSnapshotStore>(relaxed = true),
        )

    @Test
    fun `收藏走统一写入口并透传排队结果`() = runBlocking {
        coEvery { jellyfinRepository.markAsFavorite(ITEM_ID) } returns
            UserDataWriteOutcome.QueuedForSync

        val outcome = repository.setFavorite(ITEM_ID, true)

        assertEquals(UserDataWriteOutcome.QueuedForSync, outcome)
        coVerify(exactly = 1) { jellyfinRepository.markAsFavorite(ITEM_ID) }
        coVerify(exactly = 0) { jellyfinRepository.unmarkAsFavorite(any()) }
        // 不再直连 SDK：排队语义只能由统一写入口提供。
        coVerify(exactly = 0) { userLibraryApi.markFavoriteItem(any()) }
    }

    @Test
    fun `取消收藏走统一写入口`() = runBlocking {
        coEvery { jellyfinRepository.unmarkAsFavorite(ITEM_ID) } returns
            UserDataWriteOutcome.Confirmed

        val outcome = repository.setFavorite(ITEM_ID, false)

        assertEquals(UserDataWriteOutcome.Confirmed, outcome)
        coVerify(exactly = 1) { jellyfinRepository.unmarkAsFavorite(ITEM_ID) }
        coVerify(exactly = 0) { jellyfinRepository.markAsFavorite(any()) }
        coVerify(exactly = 0) { userLibraryApi.unmarkFavoriteItem(any()) }
    }

    @Test
    fun `沿用统一失败分类`() = runBlocking {
        val failure = UserDataWriteFailure.PermissionDenied(403, "forbidden")
        coEvery { jellyfinRepository.markAsFavorite(ITEM_ID) } returns
            UserDataWriteOutcome.Failed(failure)

        val outcome = repository.setFavorite(ITEM_ID, true)

        assertEquals(UserDataWriteOutcome.Failed(failure), outcome)
    }

    @Test
    fun `未登录时不伪造排队`() = runBlocking {
        every { api.userId } returns null

        val outcome = repository.setFavorite(ITEM_ID, true)

        val failure = (outcome as UserDataWriteOutcome.Failed).failure
        assertTrue(failure is UserDataWriteFailure.Unknown)
        coVerify(exactly = 0) { jellyfinRepository.markAsFavorite(any()) }
    }

    private companion object {
        val USER_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val ITEM_ID: UUID = UUID.fromString("66666666-6666-4666-8666-666666666666")
    }
}
