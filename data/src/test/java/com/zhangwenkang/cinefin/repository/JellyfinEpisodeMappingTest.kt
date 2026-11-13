package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinEpisodeMappingTest {
    @Test
    fun `video dto requested as episode fails with illegal state instead of npe`() = runBlocking {
        val itemId = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val item =
            BaseItemDto(
                id = itemId,
                name = "home video",
                type = BaseItemKind.VIDEO,
            )
        val response = mockk<Response<BaseItemDto>>(relaxed = true)
        every { response.content } returns item
        val userLibraryApi = mockk<UserLibraryApi>()
        coEvery { userLibraryApi.getItem(itemId, userId) } returns response
        val api =
            mockk<JellyfinApi>(relaxed = true).apply {
                every { this@apply.userId } returns userId
                every { this@apply.userLibraryApi } returns userLibraryApi
            }
        val repository =
            JellyfinRepositoryImpl(
                context = mockk<Context>(relaxed = true),
                jellyfinApi = api,
                database = mockk<ServerDatabaseDao>(relaxed = true),
                appPreferences = AppPreferences(mockk<SharedPreferences>(relaxed = true)),
                localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
                metadataSnapshotStore = mockk(relaxed = true),
            )

        val failure = runCatching { repository.getEpisode(itemId) }.exceptionOrNull()

        assertTrue(
            "expected IllegalStateException, got ${failure?.javaClass?.name}: ${failure?.message}",
            failure is IllegalStateException,
        )
        assertFalse(failure is NullPointerException)
        assertEquals("该条目不是可播放的剧集", failure?.message)
    }
}
