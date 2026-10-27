package com.zhangwenkang.cinefin.film.presentation.downloads

import android.content.ContextWrapper
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.BookDownloadTask
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.utils.BookCoverProvider
import com.zhangwenkang.cinefin.utils.DownloadIntentPhase
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadStorageUsage
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import com.zhangwenkang.cinefin.utils.Downloader
import com.zhangwenkang.cinefin.utils.ImageCacheStore
import com.zhangwenkang.cinefin.utils.OfflineMediaRepository
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadsViewModelPauseSelectedTest {

    @Test
    fun `selected pause sends only real source tasks to pauseTask`() {
        val realSource =
            task(
                sourceId = "real-source",
                intentPhase = DownloadIntentPhase.SOURCE_PENDING,
                suffix = 1,
            )
        val intentTasks =
            DownloadIntentPhase.entries.mapIndexed { index, phase ->
                task(
                    sourceId = "intent:${UUID.nameUUIDFromBytes("intent-$index".toByteArray())}",
                    intentPhase = phase,
                    suffix = index + 2L,
                )
            }
        val paused = mutableListOf<DownloadTask>()
        val downloads = listOf(realSource) + intentTasks
        val context = TestContext(createTempDirectory().toFile())
        val repository = repositoryProxy()
        val viewModel =
            DownloadsViewModel(
                context = context,
                downloader = downloaderProxy(downloads, paused),
                repository = repository,
                readerRepository = readerRepositoryProxy(),
                offlineMediaRepository = offlineRepositoryProxy(),
                bookCoverProvider = bookCoverProvider(),
                imageCacheStore = imageCacheStore(context),
            )

        viewModel.actionScopeOverride = CoroutineScope(Dispatchers.Unconfined)
        viewModel.start()
        val selectedKeys = viewModel.state.value.activeTasks.map(::taskKey)

        assertEquals(4, selectedKeys.size)
        viewModel.onAction(DownloadAction.SelectKeys(selectedKeys))
        viewModel.onAction(DownloadAction.PauseSelected)

        assertEquals(listOf("real-source"), paused.map(DownloadTask::sourceId))
    }

    private fun task(sourceId: String, intentPhase: DownloadIntentPhase?, suffix: Long) =
        DownloadTask(
            itemId = UUID(0L, suffix),
            sourceId = sourceId,
            name = "Task $suffix",
            path = "",
            downloadId = null,
            status = DownloadTaskStatus.PENDING,
            failureReason = null,
            downloadedBytes = 0L,
            totalBytes = 100L,
            updatedAt = suffix,
            mediaKind = DownloadMediaKind.MUSIC,
            intentPhase = intentPhase,
        )

    private fun downloaderProxy(
        tasks: List<DownloadTask>,
        paused: MutableList<DownloadTask>,
    ): Downloader =
        proxy(Downloader::class.java) { method, args ->
            when (method.name) {
                "getQueueChanges" -> MutableSharedFlow<Unit>()
                "refreshDownloadTasks" -> tasks
                "downloadedMediaRecords" -> emptyList<Any>()
                "getStorageUsage" -> DownloadStorageUsage(0L, 0L, 0L)
                "pauseTask" -> {
                    paused += args.first() as DownloadTask
                    true
                }
                else -> defaultValue(method.returnType)
            }
        }

    private fun repositoryProxy(): JellyfinRepository =
        proxy(JellyfinRepository::class.java) { method, _ ->
            when (method.name) {
                "getDownloads" -> emptyList<FindroidItem>()
                "getEpisodeHierarchyWithSources" -> emptyList<Any>()
                "getMusicTrackMetadata" -> emptyMap<UUID, Any>()
                else -> defaultValue(method.returnType)
            }
        }

    private fun readerRepositoryProxy(): ReaderRepository =
        proxy(ReaderRepository::class.java) { method, _ ->
            when (method.name) {
                "getBookDownloads" -> MutableStateFlow(emptyList<BookDownloadTask>())
                "listLocalFiles" -> emptyList<Any>()
                else -> defaultValue(method.returnType)
            }
        }

    private fun offlineRepositoryProxy(): OfflineMediaRepository =
        proxy(OfflineMediaRepository::class.java) { method, _ ->
            when (method.name) {
                "listEntries" -> emptyList<Any>()
                else -> defaultValue(method.returnType)
            }
        }

    private fun imageCacheStore(context: TestContext): ImageCacheStore {
        val constructor =
            ImageCacheStore::class.java.declaredConstructors.first { it.parameterCount == 2 }
        constructor.isAccessible = true
        return constructor.newInstance(context, { null }) as ImageCacheStore
    }

    private fun bookCoverProvider(): BookCoverProvider {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val instanceField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = instanceField.get(null)
        return unsafeClass
            .getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, BookCoverProvider::class.java) as BookCoverProvider
    }

    private inline fun <reified T> proxy(
        type: Class<T>,
        crossinline result: (java.lang.reflect.Method, Array<out Any?>) -> Any?,
    ): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Test${type.simpleName}"
                else -> result(method, args.orEmpty())
            }
        } as T

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> '\u0000'
            Void.TYPE -> null
            Unit::class.java -> Unit
            else -> null
        }

    private class TestContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root

        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
}
