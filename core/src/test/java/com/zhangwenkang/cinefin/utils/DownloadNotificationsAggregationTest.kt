package com.zhangwenkang.cinefin.utils

import android.app.Notification
import android.content.ContextWrapper
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.FindroidSourceDto
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.ReaderRepository
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import sun.misc.Unsafe

class DownloadNotificationsAggregationTest {
    @Test
    fun `two active tasks share the same page and foreground byte aggregate`() = runBlocking {
        val fixture = Fixture()
        val first =
            fixture.seedActiveSource(
                sourceId = "source-a",
                downloadedBytes = 500L,
                totalBytes = 2_000L,
            )
        val second =
            fixture.seedActiveSource(
                sourceId = "source-b",
                downloadedBytes = 400L,
                totalBytes = 1_000L,
            )
        fixture.markRunning(first, second)
        val gateway = fixture.installCapturingGateway()
        val pageAggregate = pageAggregate(fixture.downloader.refreshDownloadTasks())

        fixture.downloader.foregroundInfo()

        val foreground = gateway.foreground.single()
        val aggregateTask = checkNotNull(foreground.task)
        assertEquals(900L, pageAggregate.downloadedBytes)
        assertEquals(3_000L, pageAggregate.totalBytes)
        assertEquals(2, foreground.activeCount)
        assertEquals(pageAggregate.totalBytes, aggregateTask.totalBytes)
        assertEquals(pageAggregate.downloadedBytes, aggregateTask.downloadedBytes)
        assertEquals("source-a", aggregateTask.sourceId)
        assertEquals(
            30,
            downloadNotificationPercent(
                downloadedBytes = aggregateTask.downloadedBytes,
                totalBytes = aggregateTask.totalBytes,
            ),
        )
    }

    @Test
    fun `single task keeps the existing twenty five percent regression`() {
        val task = downloadTask(sourceId = "source-a", downloadedBytes = 500L, totalBytes = 2_000L)

        val aggregate = aggregateDownloadNotification(listOf(task))

        assertEquals(1, aggregate.activeCount)
        assertEquals(task, aggregate.task)
        assertEquals(500L, aggregate.task?.downloadedBytes)
        assertEquals(2_000L, aggregate.task?.totalBytes)
        assertEquals(25, downloadNotificationPercent(500L, 2_000L))
    }

    @Test
    fun `empty active set has no zero task foreground aggregate`() {
        val aggregate = aggregateDownloadNotification(emptyList())

        assertEquals(0, aggregate.activeCount)
        assertEquals(null, aggregate.task)
    }

    @Test
    fun `duplicate and reordered snapshots produce the same aggregate`() {
        val first =
            downloadTask(
                sourceId = "source-a",
                downloadedBytes = 500L,
                totalBytes = 2_000L,
                speedBytesPerSecond = 100L,
            )
        val second =
            downloadTask(
                sourceId = "source-b",
                downloadedBytes = 400L,
                totalBytes = 1_000L,
                speedBytesPerSecond = 50L,
            )

        val forward = aggregateDownloadNotification(listOf(first, second, first))
        val reversed = aggregateDownloadNotification(listOf(second, first, second))

        assertSameAggregateContent(forward, reversed)
        assertEquals(2, forward.activeCount)
        assertEquals(900L, forward.task?.downloadedBytes)
        assertEquals(3_000L, forward.task?.totalBytes)
        assertEquals("source-a", forward.task?.sourceId)
        assertEquals(14L, forward.task?.etaSeconds)
    }

    @Test
    fun `cancel and delete projections drop the removed task deterministically`() {
        val first = downloadTask(sourceId = "source-a", downloadedBytes = 500L, totalBytes = 2_000L)
        val second =
            downloadTask(sourceId = "source-b", downloadedBytes = 400L, totalBytes = 1_000L)

        val afterOneCancelled = aggregateDownloadNotification(listOf(first))
        val afterAllCancelled = aggregateDownloadNotification(emptyList())

        assertEquals(1, afterOneCancelled.activeCount)
        assertEquals(first, afterOneCancelled.task)
        assertEquals(0, afterAllCancelled.activeCount)
        assertEquals(null, afterAllCancelled.task)
    }

    @Test
    fun `restart rebuilds the aggregate from canonical tasks without a cached first task`() {
        val first = downloadTask(sourceId = "source-a", downloadedBytes = 500L, totalBytes = 2_000L)
        val second =
            downloadTask(sourceId = "source-b", downloadedBytes = 400L, totalBytes = 1_000L)
        val beforeRestart = aggregateDownloadNotification(listOf(first, second))
        val rebuiltSnapshot =
            listOf(
                first.copy(updatedAt = first.updatedAt + 1_000L),
                second.copy(updatedAt = second.updatedAt + 1_000L),
            )

        val afterRestart = aggregateDownloadNotification(rebuiltSnapshot)

        assertSameAggregateContent(beforeRestart, afterRestart)
        assertEquals("source-a", afterRestart.task?.sourceId)
    }

    private data class CapturedForeground(
        val task: DownloadTask?,
        val activeCount: Int,
    )

    private data class PageAggregate(
        val downloadedBytes: Long,
        val totalBytes: Long,
    )

    private fun pageAggregate(tasks: List<DownloadTask>): PageAggregate =
        PageAggregate(
            downloadedBytes =
                tasks.sumOf { task ->
                    if (task.status == DownloadTaskStatus.COMPLETED) {
                        task.totalBytes.coerceAtLeast(0L)
                    } else {
                        task.downloadedBytes.coerceAtLeast(0L)
                    }
                },
            totalBytes = tasks.sumOf { task -> task.totalBytes.coerceAtLeast(0L) },
        )

    private fun assertSameAggregateContent(
        expected: DownloadNotificationAggregate,
        actual: DownloadNotificationAggregate,
    ) {
        assertEquals(expected.activeCount, actual.activeCount)
        assertEquals(expected.task?.sourceId, actual.task?.sourceId)
        assertEquals(expected.task?.name, actual.task?.name)
        assertEquals(expected.task?.downloadedBytes, actual.task?.downloadedBytes)
        assertEquals(expected.task?.totalBytes, actual.task?.totalBytes)
        assertEquals(expected.task?.speedBytesPerSecond, actual.task?.speedBytesPerSecond)
        assertEquals(expected.task?.etaSeconds, actual.task?.etaSeconds)
    }

    private class CapturingNotificationGateway : DownloadNotificationGateway {
        val foreground = mutableListOf<CapturedForeground>()

        override fun buildForeground(task: DownloadTask?, activeCount: Int): Notification {
            foreground += CapturedForeground(task = task, activeCount = activeCount)
            return allocateNotification()
        }

        override fun notifyForeground(task: DownloadTask?, activeCount: Int) {
            foreground += CapturedForeground(task = task, activeCount = activeCount)
        }

        override fun notifyCompleted(task: DownloadTask) = Unit

        override fun notifyFailed(task: DownloadTask) = Unit
    }

    private class Fixture {
        private val root = Files.createTempDirectory("download-notification-aggregate").toFile()
        private val sources = linkedMapOf<String, FindroidSourceDto>()
        private val dao = RecordingDao(sources)
        private val reader = RecordingReader()
        private val gateway = CapturingNotificationGateway()
        val downloader =
            DownloaderImpl.createForTest(
                context = TestContext(root),
                database = dao.proxy,
                jellyfinRepository = unusedProxy(JellyfinRepository::class.java),
                readerRepository = reader.proxy,
                appPreferences = allocate(),
                imageCacheStore = allocate(),
                workManager =
                    allocateClass(Class.forName("androidx.work.impl.WorkManagerImpl"))
                        as WorkManager,
                httpClient = allocate(),
                notifications = gateway,
                registerNetworkCallback = {},
                workGateway = NoopWorkGateway(),
            )

        fun seedActiveSource(
            sourceId: String,
            downloadedBytes: Long,
            totalBytes: Long,
        ): FindroidSourceDto {
            val source =
                FindroidSourceDto(
                    id = sourceId,
                    itemId = UUID.nameUUIDFromBytes(sourceId.toByteArray()),
                    name = sourceId,
                    type = FindroidSourceType.LOCAL,
                    path = File(root, "downloads/$sourceId.download").path,
                    taskStatus = DownloadTaskStatus.RUNNING.name,
                    updatedAt = totalBytes,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    engineVersion = DownloaderImpl.ENGINE_VERSION,
                    taskGeneration = 1L,
                )
            sources[source.id] = source
            return source
        }

        fun markRunning(vararg seeded: FindroidSourceDto) {
            val field = DownloaderImpl::class.java.getDeclaredField("activeJobs")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val activeJobs = field.get(downloader) as ConcurrentHashMap<String, Any>
            seeded.forEach { source -> activeJobs[source.id] = Unit }
        }

        fun installCapturingGateway(): CapturingNotificationGateway {
            val field = DownloaderImpl::class.java.getDeclaredField("notifications")
            field.isAccessible = true
            val installed = CapturingNotificationGateway()
            field.set(downloader, installed)
            return installed
        }
    }

    private class RecordingDao(private val sources: Map<String, FindroidSourceDto>) {
        val proxy: ServerDatabaseDao =
            Proxy.newProxyInstance(
                ServerDatabaseDao::class.java.classLoader,
                arrayOf(ServerDatabaseDao::class.java),
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "getPendingSources" -> sources.values.toList()
                        "getSource" -> sources[args[0] as String]
                        "getReceivedDownloadIntents" -> emptyList<Any>()
                        "getDownloadIntents" -> emptyList<Any>()
                        "getDownloadedItemIds" -> emptyList<UUID>()
                        "getEpisodeHierarchyWithSources" -> emptyList<Any>()
                        "getMovie",
                        "getEpisode",
                        "getShow" -> null
                        "toString" -> "RecordingDao"
                        "hashCode" -> System.identityHashCode(this)
                        "equals" -> false
                        else -> error("unexpected ServerDatabaseDao call: ${method.name}")
                    }
                },
            ) as ServerDatabaseDao
    }

    private class RecordingReader {
        private val downloads = MutableStateFlow(emptyList<Any>())

        val proxy: ReaderRepository =
            Proxy.newProxyInstance(
                ReaderRepository::class.java.classLoader,
                arrayOf(ReaderRepository::class.java),
                InvocationHandler { _, method, _ ->
                    when (method.name) {
                        "getBookDownloads" -> downloads
                        "toString" -> "RecordingReader"
                        "hashCode" -> System.identityHashCode(this)
                        "equals" -> false
                        else -> error("unexpected ReaderRepository call: ${method.name}")
                    }
                },
            ) as ReaderRepository
    }

    private class NoopWorkGateway : DownloadWorkGateway {
        override suspend fun uniqueWorkInfos(name: String): List<WorkInfo> = emptyList()

        override fun enqueueUnique(
            name: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) = Unit

        override fun cancelUnique(name: String) = Unit

        override fun enqueue(request: OneTimeWorkRequest) = Unit
    }

    private class TestContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root

        override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(root)
    }

    private companion object {
        val unsafe: Unsafe =
            Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
                as Unsafe

        fun allocateNotification(): Notification =
            unsafe.allocateInstance(Notification::class.java) as Notification

        inline fun <reified T : Any> allocate(): T = unsafe.allocateInstance(T::class.java) as T

        fun allocateClass(type: Class<*>): Any = unsafe.allocateInstance(type)

        fun downloadTask(
            sourceId: String,
            downloadedBytes: Long,
            totalBytes: Long,
            speedBytesPerSecond: Long = 0L,
        ): DownloadTask =
            DownloadTask(
                itemId = UUID.nameUUIDFromBytes("item:$sourceId".toByteArray()),
                sourceId = sourceId,
                name = sourceId,
                path = "",
                downloadId = 1L,
                status = DownloadTaskStatus.RUNNING,
                failureReason = null,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                updatedAt = totalBytes,
                speedBytesPerSecond = speedBytesPerSecond,
            )

        fun <T : Any> unusedProxy(type: Class<T>): T =
            type.cast(
                Proxy.newProxyInstance(
                    type.classLoader,
                    arrayOf(type),
                    InvocationHandler { _, method, _ ->
                        error("unexpected ${type.simpleName}.${method.name}")
                    },
                )
            )!!
    }
}
