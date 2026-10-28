package com.zhangwenkang.cinefin.presentation.downloads

import com.zhangwenkang.cinefin.presentation.film.components.DownloadBadgeState
import com.zhangwenkang.cinefin.repository.BookDownloadStatus
import com.zhangwenkang.cinefin.repository.BookDownloadTask
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import com.zhangwenkang.cinefin.utils.Downloader
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** W60b 下载角标快照（任务 + 已下载集合 → itemId → 徽标）纯函数单测。 */
class DownloadStatusMonitorTest {

    @Test
    fun `latest failed task beats stale running duplicate`() {
        val itemId = UUID.randomUUID()
        val map =
            badgeMapFor(
                tasks =
                    listOf(
                        task(
                            itemId,
                            DownloadTaskStatus.FAILED,
                            updatedAt = 200L,
                            taskGeneration = 2L,
                        ),
                        task(
                            itemId,
                            DownloadTaskStatus.RUNNING,
                            downloadedBytes = 50,
                            total = 100,
                            updatedAt = 100L,
                            taskGeneration = 1L,
                        ),
                    ),
                downloaded = emptySet(),
            )

        val badge = map.getValue(itemId)
        assertEquals(DownloadBadgeState.FAILED, badge.state)
    }

    @Test
    fun `downloaded items without tasks still get a badge`() {
        val itemId = UUID.randomUUID()
        val map = badgeMapFor(tasks = emptyList(), downloaded = setOf(itemId))
        assertEquals(DownloadBadgeState.DOWNLOADED, map.getValue(itemId).state)
    }

    @Test
    fun `downloaded beats running task for the same item`() {
        val itemId = UUID.randomUUID()
        val map =
            badgeMapFor(
                tasks = listOf(task(itemId, DownloadTaskStatus.RUNNING)),
                downloaded = setOf(itemId),
            )
        assertEquals(DownloadBadgeState.DOWNLOADED, map.getValue(itemId).state)
    }

    @Test
    fun `paused task maps to paused badge`() {
        val itemId = UUID.randomUUID()
        val map =
            badgeMapFor(
                tasks = listOf(task(itemId, DownloadTaskStatus.PAUSED)),
                downloaded = emptySet(),
            )
        assertEquals(DownloadBadgeState.PAUSED, map.getValue(itemId).state)
    }

    @Test
    fun `queue event refreshes badges without waiting for poll interval`() = runBlocking {
        val itemId = UUID.randomUUID()
        val queue = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val tasks = MutableStateFlow(emptyList<DownloadTask>())
        val downloads = MutableStateFlow(emptySet<UUID>())
        val books = MutableStateFlow(emptyList<BookDownloadTask>())
        val refreshes = AtomicInteger()
        val downloader =
            downloaderProxy(
                queue = queue,
                tasks = tasks,
                downloads = downloads,
                onRefresh = { refreshes.incrementAndGet() },
            )
        val monitor = DownloadStatusMonitor(downloader, readerRepositoryProxy(books))
        try {
            monitor.acquire()
            waitUntil { refreshes.get() >= 1 }

            tasks.value = listOf(task(itemId, DownloadTaskStatus.FAILED, updatedAt = 2L))
            queue.emit(Unit)

            waitUntil(timeoutMs = 1_200L) {
                monitor.badges.value[itemId]?.state == DownloadBadgeState.FAILED
            }
            assertEquals(2, refreshes.get())
        } finally {
            monitor.release()
        }
    }

    @Test
    fun `book flow refreshes status and hides superseded delete receipts`() = runBlocking {
        val itemId = UUID.randomUUID()
        val queue = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val tasks = MutableStateFlow(emptyList<DownloadTask>())
        val downloads = MutableStateFlow(emptySet<UUID>())
        val books = MutableStateFlow(emptyList<BookDownloadTask>())
        val refreshes = AtomicInteger()
        val monitor =
            DownloadStatusMonitor(
                downloaderProxy(queue, tasks, downloads) { refreshes.incrementAndGet() },
                readerRepositoryProxy(books),
            )
        try {
            monitor.acquire()
            waitUntil { refreshes.get() >= 1 }

            books.value =
                listOf(book(itemId, BookDownloadStatus.RUNNING, generation = 1L, updatedAt = 1L))
            waitUntil(timeoutMs = 1_200L) {
                monitor.badges.value[itemId]?.state == DownloadBadgeState.IN_PROGRESS
            }

            books.value =
                listOf(book(itemId, BookDownloadStatus.FAILED, generation = 2L, updatedAt = 2L))
            waitUntil(timeoutMs = 1_200L) {
                monitor.badges.value[itemId]?.state == DownloadBadgeState.FAILED
            }

            books.value =
                listOf(
                    book(
                        itemId = itemId,
                        status = BookDownloadStatus.FAILED,
                        generation = 3L,
                        updatedAt = 3L,
                        failureReason = "DELETE_SUPERSEDED",
                    )
                )
            waitUntil(timeoutMs = 1_200L) { itemId !in monitor.badges.value }
        } finally {
            monitor.release()
        }
    }

    @Test
    fun `polling remains a fallback when event is missed`() = runBlocking {
        val itemId = UUID.randomUUID()
        val queue = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val tasks = MutableStateFlow(emptyList<DownloadTask>())
        val downloads = MutableStateFlow(emptySet<UUID>())
        val books = MutableStateFlow(emptyList<BookDownloadTask>())
        val refreshes = AtomicInteger()
        val monitor =
            DownloadStatusMonitor(
                downloaderProxy(queue, tasks, downloads) { refreshes.incrementAndGet() },
                readerRepositoryProxy(books),
            )
        try {
            monitor.acquire()
            waitUntil { refreshes.get() >= 1 }

            tasks.value = listOf(task(itemId, DownloadTaskStatus.FAILED, updatedAt = 2L))

            waitUntil(timeoutMs = 2_200L) {
                monitor.badges.value[itemId]?.state == DownloadBadgeState.FAILED
            }
        } finally {
            monitor.release()
        }
    }

    @Test
    fun `status projection combines latest engine and visible book state`() {
        val engineItem = UUID.randomUUID()
        val bookItem = UUID.randomUUID()
        val projection =
            downloadStatusProjectionFor(
                tasks =
                    listOf(
                        task(
                            engineItem,
                            DownloadTaskStatus.FAILED,
                            updatedAt = 2L,
                            taskGeneration = 2L,
                        ),
                        task(
                            engineItem,
                            DownloadTaskStatus.RUNNING,
                            updatedAt = 1L,
                            taskGeneration = 1L,
                        ),
                    ),
                downloaded = emptySet(),
                bookTasks =
                    listOf(
                        book(
                            bookItem,
                            BookDownloadStatus.RUNNING,
                            generation = 1L,
                            updatedAt = 1L,
                        )
                    ),
            )

        assertEquals(setOf(bookItem), projection.activeItemIds)
        assertEquals(setOf(engineItem), projection.failedItemIds)
        assertEquals(DownloadBadgeState.FAILED, projection.badges.getValue(engineItem).state)
        assertEquals(
            DownloadBadgeState.IN_PROGRESS,
            projection.badges.getValue(bookItem).state,
        )
    }

    @Test
    fun `stop and restart cancels and recreates exactly one event collector`() = runBlocking {
        val itemId = UUID.randomUUID()
        val queue = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val tasks = MutableStateFlow(emptyList<DownloadTask>())
        val downloads = MutableStateFlow(emptySet<UUID>())
        val books = MutableStateFlow(emptyList<BookDownloadTask>())
        val refreshes = AtomicInteger()
        val monitor =
            DownloadStatusMonitor(
                downloaderProxy(queue, tasks, downloads) { refreshes.incrementAndGet() },
                readerRepositoryProxy(books),
            )

        monitor.acquire()
        waitUntil { refreshes.get() == 1 }
        waitUntil { queue.subscriptionCount.value == 1 }

        monitor.release()
        waitUntil { queue.subscriptionCount.value == 0 }
        queue.emit(Unit)
        delay(100L)
        assertEquals(1, refreshes.get())

        monitor.acquire()
        waitUntil { refreshes.get() == 2 }
        waitUntil { queue.subscriptionCount.value == 1 }
        tasks.value = listOf(task(itemId, DownloadTaskStatus.FAILED, updatedAt = 2L))
        queue.emit(Unit)
        waitUntil { refreshes.get() == 3 }
        delay(100L)
        assertEquals(3, refreshes.get())
        assertEquals(1, queue.subscriptionCount.value)

        monitor.release()
        waitUntil { queue.subscriptionCount.value == 0 }
    }

    private fun task(
        itemId: UUID,
        status: DownloadTaskStatus,
        downloadedBytes: Long = 0L,
        total: Long = 0L,
        updatedAt: Long = 0L,
        taskGeneration: Long = 0L,
    ) =
        DownloadTask(
            itemId = itemId,
            sourceId = "source-${itemId}",
            name = "测试条目",
            path = "/tmp/test.download",
            downloadId = null,
            status = status,
            failureReason = null,
            downloadedBytes = downloadedBytes,
            totalBytes = total,
            updatedAt = updatedAt,
            taskGeneration = taskGeneration,
        )

    private fun book(
        itemId: UUID,
        status: BookDownloadStatus,
        generation: Long,
        updatedAt: Long,
        failureReason: String? = null,
    ) =
        BookDownloadTask(
            itemId = itemId,
            title = "测试书籍",
            status = status,
            failureReason = failureReason,
            taskGeneration = generation,
            updatedAtEpochMillis = updatedAt,
        )

    private fun downloaderProxy(
        queue: MutableSharedFlow<Unit>,
        tasks: MutableStateFlow<List<DownloadTask>>,
        downloads: MutableStateFlow<Set<UUID>>,
        onRefresh: () -> Unit = {},
    ): Downloader =
        proxy(Downloader::class.java) { method, _ ->
            when (method.name) {
                "getQueueChanges" -> queue
                "refreshDownloadTasks" -> {
                    onRefresh()
                    tasks.value
                }
                "downloadedItemIds" -> downloads.value
                else -> defaultValue(method.returnType)
            }
        }

    private fun readerRepositoryProxy(
        books: MutableStateFlow<List<BookDownloadTask>>
    ): ReaderRepository =
        proxy(ReaderRepository::class.java) { method, _ ->
            when (method.name) {
                "getBookDownloads" -> books
                "listLocalFiles" -> emptyList<Any>()
                else -> defaultValue(method.returnType)
            }
        }

    private inline fun <reified T> proxy(
        type: Class<T>,
        crossinline result: (java.lang.reflect.Method, Array<out Any?>) -> Any?,
    ): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
            when (method.name) {
                "equals" -> instance === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(instance)
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

    private suspend fun waitUntil(
        timeoutMs: Long = 1_000L,
        condition: () -> Boolean,
    ) {
        withTimeout(timeoutMs) {
            while (!condition()) {
                delay(10L)
            }
        }
    }
}
