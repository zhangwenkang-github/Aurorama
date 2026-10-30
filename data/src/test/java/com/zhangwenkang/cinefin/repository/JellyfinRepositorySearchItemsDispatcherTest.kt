package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class JellyfinRepositorySearchItemsDispatcherTest {
    @Test
    fun `搜索结果抓取、映射与缓存读取都不在调用线程执行`() = runBlocking {
        val directory = Files.createTempDirectory("search-items-dispatcher")
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val callerThread = Thread.currentThread()
            lateinit var fetchThread: Thread
            lateinit var materializeThread: Thread
            lateinit var cacheReadThread: Thread
            lateinit var repository: JellyfinRepositoryImpl
            val itemId = UUID.fromString("11111111-1111-4111-8111-111111111111")
            val api = mockk<JellyfinApi>(relaxed = true).apply { every { userId } returns USER_ID }
            val preferences =
                mockk<SharedPreferences>(relaxed = true).apply {
                    every { getString(any(), any()) } returns SERVER_ID
                }
            repository =
                JellyfinRepositoryImpl(
                    context = mockk<Context>(relaxed = true),
                    jellyfinApi = api,
                    database = mockk<ServerDatabaseDao>(relaxed = true),
                    appPreferences = AppPreferences(preferences),
                    localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
                    metadataSnapshotStore = MetadataSnapshotStore(directory.toFile()),
                    searchItemsIoDispatcher = dispatcher,
                    searchItemsFetch = { _, _, _ ->
                        fetchThread = Thread.currentThread()
                        listOf(
                            BaseItemDto(
                                id = itemId,
                                name = "sample",
                                type = BaseItemKind.MOVIE,
                            )
                        )
                    },
                    searchItemsMaterialize = { items, materialize ->
                        materializeThread = Thread.currentThread()
                        materialize(items)
                    },
                    searchItemsCacheRead = { read ->
                        cacheReadThread = Thread.currentThread()
                        read()
                    },
                )

            val result = repository.getSearchItems("sample", SearchScope.VIDEO)

            assertEquals(listOf("sample"), result.map { it.name })
            println(
                "caller=${callerThread.name} fetch=${fetchThread.name} " +
                    "materialize=${materializeThread.name} cacheRead=${cacheReadThread.name}"
            )
            assertNotEquals(callerThread, fetchThread)
            assertNotEquals(callerThread, materializeThread)
            assertNotEquals(callerThread, cacheReadThread)
        } finally {
            dispatcher.close()
            directory.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val SERVER_ID = "server-a"
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}
