package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.models.FindroidSourceDto
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.operations.LibraryApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W8-T5 契约：删除服务器条目只走 SDK 官方 `DELETE /Items/{itemId}`（[LibraryApi.deleteItem]，参数只有条目 id）。
 *
 * 删除不可排队补偿：服务器拒绝 / 失败时本地库必须保持原样；只有确认成功后才清理本地缓存与下载记录。
 */
class ItemDeleteContractTest {
    private val database = mockk<ServerDatabaseDao>(relaxed = true)
    private val libraryApi = mockk<LibraryApi>(relaxed = true)

    private val api: JellyfinApi =
        mockk<JellyfinApi>(relaxed = true).also { jellyfinApi ->
            every { jellyfinApi.userId } returns USER_ID
            every { jellyfinApi.libraryApi } returns libraryApi
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
    fun `删除只打 deleteItem 一次且成功后清理全部本地数据`() = runBlocking {
        coEvery { database.getSources(ITEM_ID) } returns listOf(SOURCE)

        val outcome = repository.deleteItem(ITEM_ID)

        assertEquals(UserDataWriteOutcome.Confirmed, outcome)
        coVerify(exactly = 1) { libraryApi.deleteItem(ITEM_ID) }
        coVerify(exactly = 0) { libraryApi.deleteItems(any()) }
        coVerify(exactly = 1) { database.deleteMovie(ITEM_ID) }
        coVerify(exactly = 1) { database.deleteEpisode(ITEM_ID) }
        coVerify(exactly = 1) { database.deleteUserData(ITEM_ID) }
        coVerify(exactly = 1) { database.getSources(ITEM_ID) }
        coVerify(exactly = 1) { database.deleteMediaStreamsBySourceId(SOURCE.id) }
        coVerify(exactly = 1) { database.deleteSource(SOURCE.id) }
        coVerify(exactly = 1) { database.deleteDownloadIntent(ITEM_ID) }
    }

    @Test
    fun `403 返回权限拒绝且零本地清理`() = runBlocking {
        coEvery { libraryApi.deleteItem(ITEM_ID) } throws InvalidStatusException(403)

        val outcome = repository.deleteItem(ITEM_ID)

        assertTrue(outcome is UserDataWriteOutcome.Failed)
        val failure = (outcome as UserDataWriteOutcome.Failed).failure
        assertTrue(failure is UserDataWriteFailure.PermissionDenied)
        assertEquals(403, (failure as UserDataWriteFailure.PermissionDenied).status)
        verifyNoLocalCleanup()
    }

    @Test
    fun `502 返回服务端错误且零本地清理`() = runBlocking {
        coEvery { libraryApi.deleteItem(ITEM_ID) } throws InvalidStatusException(502)

        val outcome = repository.deleteItem(ITEM_ID)

        assertTrue(outcome is UserDataWriteOutcome.Failed)
        val failure = (outcome as UserDataWriteOutcome.Failed).failure
        assertTrue(failure is UserDataWriteFailure.ServerError)
        assertEquals(502, (failure as UserDataWriteFailure.ServerError).status)
        verifyNoLocalCleanup()
    }

    @Test
    fun `网络 IO 异常返回网络失败且零本地清理`() = runBlocking {
        coEvery { libraryApi.deleteItem(ITEM_ID) } throws IOException("connection reset")

        val outcome = repository.deleteItem(ITEM_ID)

        assertEquals(
            UserDataWriteOutcome.Failed(UserDataWriteFailure.Network("connection reset")),
            outcome,
        )
        verifyNoLocalCleanup()
    }

    private fun verifyNoLocalCleanup() {
        coVerify(exactly = 0) { database.deleteMovie(any()) }
        coVerify(exactly = 0) { database.deleteEpisode(any()) }
        coVerify(exactly = 0) { database.deleteUserData(any()) }
        coVerify(exactly = 0) { database.getSources(any()) }
        coVerify(exactly = 0) { database.deleteSource(any()) }
        coVerify(exactly = 0) { database.deleteMediaStreamsBySourceId(any()) }
        coVerify(exactly = 0) { database.deleteDownloadIntent(any()) }
    }

    private companion object {
        val USER_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val ITEM_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val SOURCE =
            FindroidSourceDto(
                id = "source-1",
                itemId = ITEM_ID,
                name = "source",
                type = FindroidSourceType.LOCAL,
                path = "/tmp/source",
            )
    }
}
