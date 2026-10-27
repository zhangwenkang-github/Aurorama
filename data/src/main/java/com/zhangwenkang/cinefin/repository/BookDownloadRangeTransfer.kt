package com.zhangwenkang.cinefin.repository

import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.BookDownloadTaskDto
import com.zhangwenkang.cinefin.network.JellyfinAuth
import com.zhangwenkang.cinefin.utils.DownloadCanonicalIdentity
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class BookDownloadCredentials(
    val serverId: String,
    val userId: UUID,
    val baseUrl: String,
    val accessToken: String,
) {
    init {
        require(serverId.isNotBlank()) { "书籍下载缺少 serverId" }
        require(baseUrl.isNotBlank()) { "书籍下载缺少服务器地址" }
        require(accessToken.isNotBlank()) { "书籍下载缺少访问令牌" }
    }
}

internal interface BookDownloadTaskStore {
    suspend fun load(): List<BookDownloadTaskDto>

    suspend fun save(task: BookDownloadTaskDto)

    suspend fun delete(canonicalKey: String)
}

internal class DaoBookDownloadTaskStore(private val database: ServerDatabaseDao) :
    BookDownloadTaskStore {
    override suspend fun load(): List<BookDownloadTaskDto> = database.getBookDownloadTasks()

    override suspend fun save(task: BookDownloadTaskDto) {
        database.insertBookDownloadTask(task)
    }

    override suspend fun delete(canonicalKey: String) {
        database.deleteBookDownloadTask(canonicalKey)
    }
}

internal data class BookDownloadTransferRequest(
    val itemId: UUID,
    val targetFile: File,
    val partialFile: File,
    val credentials: BookDownloadCredentials,
    val resumeValidator: String?,
    val taskGeneration: Long,
)

internal data class BookDownloadTransferResult(
    val totalBytes: Long,
    val validator: String?,
)

internal interface BookDownloadTransfer {
    suspend fun download(
        request: BookDownloadTransferRequest,
        onMetadata: suspend (validator: String?, totalBytes: Long) -> Unit = { _, _ -> },
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): BookDownloadTransferResult
}

/**
 * 书籍整本下载的唯一 HTTP / 文件提交实现。
 *
 * - 首请求不带 Range；只有已有 `.part` 且存在校验器时才续传；
 * - 206 必须从当前文件末尾开始，200 从零截断；
 * - 416 仅当偏移等于总大小时完成，否则仅允许一次无 Range 安全重下；
 * - 任何失败 / 取消都保留 `.part`，完整文件只通过同目录原子 move 出现。
 */
internal class BookDownloadRangeTransfer(private val httpClient: OkHttpClient) :
    BookDownloadTransfer {
    override suspend fun download(
        request: BookDownloadTransferRequest,
        onMetadata: suspend (validator: String?, totalBytes: Long) -> Unit,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): BookDownloadTransferResult {
        request.targetFile.parentFile?.mkdirs()
        request.partialFile.parentFile?.mkdirs()

        val resumableOffset =
            request.partialFile
                .takeIf {
                    it.isFile && it.length() > 0L && !request.resumeValidator.isNullOrBlank()
                }
                ?.length() ?: 0L
        var recoveryUsed = false
        var useRange = resumableOffset > 0L

        while (true) {
            currentCoroutineContext().ensureActive()
            val offset = request.partialFile.takeIf { it.isFile }?.length() ?: 0L
            val attemptOffset = if (useRange) offset else 0L
            val response =
                execute(
                    request = request,
                    offset = attemptOffset,
                    useRange = useRange && attemptOffset > 0L,
                )

            response.use {
                if (response.code == 416) {
                    val total = parseUnsatisfiedTotal(response.header("Content-Range"))
                    if (useRange && total != null && attemptOffset == total && total > 0L) {
                        finishPartial(request.partialFile, request.targetFile)
                        return BookDownloadTransferResult(
                            totalBytes = total,
                            validator =
                                response.header("ETag")
                                    ?: response.header("Last-Modified")
                                    ?: request.resumeValidator,
                        )
                    }
                    if (recoveryUsed) {
                        throw IOException("书籍服务器拒绝 Range，安全重下后仍返回 HTTP 416")
                    }
                    recoveryUsed = true
                    useRange = false
                    return@use
                }

                if (response.code == 206) {
                    val range =
                        parseContentRange(response.header("Content-Range"))
                            ?: throw IOException("HTTP 206 缺少有效 Content-Range")
                    if (!useRange || range.start != attemptOffset) {
                        if (recoveryUsed) {
                            throw IOException(
                                "HTTP 206 Content-Range 起点 ${range.start} 与本地偏移 $attemptOffset 不一致"
                            )
                        }
                        recoveryUsed = true
                        useRange = false
                        return@use
                    }
                    writeBody(
                        request = request,
                        response = response,
                        append = true,
                        expectedStart = range.start,
                        expectedEnd = range.endInclusive,
                        declaredTotal = range.total,
                        onMetadata = onMetadata,
                        onProgress = onProgress,
                    )
                    finishPartial(request.partialFile, request.targetFile)
                    return BookDownloadTransferResult(
                        totalBytes = range.total ?: request.partialFile.length(),
                        validator =
                            response.header("ETag")
                                ?: response.header("Last-Modified")
                                ?: request.resumeValidator,
                    )
                }

                if (response.code == 200) {
                    writeBody(
                        request = request,
                        response = response,
                        append = false,
                        expectedStart = 0L,
                        expectedEnd = null,
                        declaredTotal = response.body.contentLength().takeIf { it >= 0L },
                        onMetadata = onMetadata,
                        onProgress = onProgress,
                    )
                    finishPartial(request.partialFile, request.targetFile)
                    return BookDownloadTransferResult(
                        totalBytes = request.partialFile.length(),
                        validator =
                            response.header("ETag")
                                ?: response.header("Last-Modified")
                                ?: request.resumeValidator,
                    )
                }

                throw IOException("下载书籍失败：HTTP ${response.code}（离线时请先联网下载）")
            }
        }
    }

    private suspend fun execute(
        request: BookDownloadTransferRequest,
        offset: Long,
        useRange: Boolean,
    ) =
        request.credentials.let { credentials ->
            val builder =
                Request.Builder()
                    .url("${credentials.baseUrl.trimEnd('/')}/Items/${request.itemId}/Download")
                    .header(
                        JellyfinAuth.HEADER,
                        JellyfinAuth.headerValue(credentials.accessToken),
                    )
            if (useRange) {
                builder.header("Range", "bytes=$offset-")
                request.resumeValidator
                    ?.takeIf { it.isNotBlank() }
                    ?.let { builder.header("If-Range", it) }
            }
            val call = httpClient.newCall(builder.build())
            val cancelHandle =
                currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) call.cancel()
                }
            try {
                call.execute()
            } finally {
                cancelHandle?.dispose()
            }
        }

    private suspend fun writeBody(
        request: BookDownloadTransferRequest,
        response: okhttp3.Response,
        append: Boolean,
        expectedStart: Long,
        expectedEnd: Long?,
        declaredTotal: Long?,
        onMetadata: suspend (validator: String?, totalBytes: Long) -> Unit,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        val responseLength = response.body.contentLength()
        val total =
            declaredTotal ?: if (responseLength >= 0L) expectedStart + responseLength else 0L
        onMetadata(
            response.header("ETag") ?: response.header("Last-Modified") ?: request.resumeValidator,
            total,
        )
        var copied = expectedStart
        response.body.byteStream().use { input ->
            FileOutputStream(request.partialFile, append).use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    copied += read
                    onProgress(copied, total.coerceAtLeast(0L))
                }
                output.fd.sync()
            }
        }
        if (expectedEnd != null && copied != expectedEnd + 1L) {
            throw IOException("书籍 Range 响应长度不完整")
        }
        if (total > 0L && copied != total) {
            throw IOException("书籍下载未完整：$copied/$total")
        }
    }

    private fun finishPartial(partial: File, target: File) {
        try {
            Files.move(
                partial.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: Exception) {
            throw IOException("书籍下载完成但原子提交失败", error)
        }
    }

    private fun parseContentRange(value: String?): ParsedContentRange? {
        val match = CONTENT_RANGE.matchEntire(value?.trim().orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        if (end < start) return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        return ParsedContentRange(start = start, endInclusive = end, total = total)
    }

    private fun parseUnsatisfiedTotal(value: String?): Long? =
        UNSATISFIED_RANGE.matchEntire(value?.trim().orEmpty())?.groupValues?.get(1)?.toLongOrNull()

    private data class ParsedContentRange(
        val start: Long,
        val endInclusive: Long,
        val total: Long?,
    )

    private companion object {
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
        val UNSATISFIED_RANGE = Regex("""bytes\s+\*/(\d+)""")
    }
}

internal class BookDownloadManager(
    private val store: BookDownloadTaskStore,
    private val transfer: BookDownloadTransfer,
    private val credentialsProvider: suspend () -> BookDownloadCredentials,
    private val localFileProvider: (UUID) -> File,
    private val partialFileProvider: (UUID) -> File,
    private val scope: CoroutineScope,
    private val recordProvenance: (BookDownloadOrigin) -> Boolean = { true },
    private val clearProvenance: (UUID) -> Boolean = { true },
    private val fetchTitle: suspend (BookDownloadCredentials, UUID) -> String? = { _, _ -> null },
    private val writeTitle: (UUID, String) -> Unit = { _, _ -> },
    private val readTitle: (UUID) -> String? = { null },
    private val deleteTitle: (UUID) -> Boolean = { true },
    private val deleteFile: (File) -> Boolean = { file ->
        !file.exists() || runCatching { file.delete() }.getOrDefault(false)
    },
    private val clock: () -> Long = System::currentTimeMillis,
    private val alreadyDownloadedDeadlineMs: Long = 1_000L,
) {
    private val mutableTasks = MutableStateFlow<List<BookDownloadTask>>(emptyList())
    val tasks: StateFlow<List<BookDownloadTask>> = mutableTasks.asStateFlow()

    private val lock = Mutex()
    private val itemLifecycles = mutableMapOf<UUID, Mutex>()
    private val jobs = mutableMapOf<String, ActiveBookDownloadJob>()
    private val retiringJobs = mutableMapOf<String, ActiveBookDownloadJob>()
    private val results = mutableMapOf<String, CompletableDeferred<File>>()
    private val restored = CompletableDeferred<Unit>()

    init {
        scope.launch(Dispatchers.IO) { restore() }
    }

    suspend fun awaitRestored() {
        restored.await()
    }

    suspend fun enqueueBookDownload(
        itemId: UUID,
        title: String?,
    ): BookDownloadEnqueueResult =
        restored.await().let {
            itemLifecycle(itemId).withLock {
                if (lock.withLock { hasUnresolvedDeleteReceiptLocked(itemId) }) {
                    return@withLock BookDownloadEnqueueResult.BlockedByDelete
                }
                val existing = completedFileIfReady(itemId)
                if (existing != null) {
                    return@withLock BookDownloadEnqueueResult.AlreadyDownloaded
                }
                val credentials = credentialsProvider()
                enqueueBookDownload(itemId, title, credentials)
            }
        }

    suspend fun awaitBookDownload(
        itemId: UUID,
        title: String?,
    ): File {
        restored.await()
        val existing = itemLifecycle(itemId).withLock { completedFileIfReady(itemId) }
        if (existing != null) return existing

        val credentials = credentialsProvider()
        val canonicalKey = canonicalBookDownloadKey(credentials, itemId)
        val result =
            itemLifecycle(itemId).withLock { enqueueBookDownload(itemId, title, credentials) }
        if (result == BookDownloadEnqueueResult.BlockedByDelete) {
            throw IOException("书籍删除尚未完成，请重试删除")
        }
        if (result == BookDownloadEnqueueResult.AlreadyDownloaded) {
            return awaitVisibleFile(
                fileProvider = { localFileProvider(itemId) },
                deadlineMs = alreadyDownloadedDeadlineMs,
            )
        }
        val deferred = lock.withLock { results[canonicalKey] }
        return deferred?.await()
            ?: awaitVisibleFile(
                fileProvider = { localFileProvider(itemId) },
                deadlineMs = alreadyDownloadedDeadlineMs,
            )
    }

    fun cancelBookDownload(itemId: UUID) {
        scope.launch(Dispatchers.IO) {
            val key =
                runCatching { credentialsProvider() }
                    .getOrNull()
                    ?.let { canonicalBookDownloadKey(it, itemId) }
                    ?: tasks.value
                        .lastOrNull { it.itemId == itemId && !it.isDeleteRecord() }
                        ?.canonicalKey
                    ?: tasks.value.lastOrNull { it.itemId == itemId }?.canonicalKey
                    ?: return@launch
            cancelByKey(key)
        }
    }

    suspend fun deleteBookDownload(itemId: UUID) {
        restored.await()
        itemLifecycle(itemId).withLock lifecycle@{
            val credentials = runCatching { credentialsProvider() }.getOrNull()
            val preferredKey = credentials?.let { canonicalBookDownloadKey(it, itemId) }
            val deletion = lock.withLock {
                val task =
                    selectDeleteTaskLocked(itemId, preferredKey)
                        ?: deleteReceipt(itemId, credentials)
                val target = task.targetPath?.let(::File) ?: localFileProvider(itemId)
                val receipt =
                    task.copy(
                        taskGeneration =
                            task.taskGeneration.takeIf { it > 0L }
                                ?: latestArtifactGeneration(
                                    target = target,
                                    basePartial = File(target.parentFile, "${target.name}.part"),
                                )
                    )
                saveLocked(
                    receipt.copy(
                        status = BookDownloadStatus.FAILED,
                        failureReason = DELETE_PENDING,
                        error = "正在清理书籍文件",
                        updatedAtEpochMillis = clock(),
                    )
                )
                results.remove(receipt.canonicalKey)?.cancel(CancellationException("书籍任务正在删除"))
                val activeJob =
                    jobs.remove(receipt.canonicalKey)?.also {
                        retiringJobs[receipt.canonicalKey] = it
                    }
                receipt to activeJob
            }
            val (receipt, activeJob) = deletion
            activeJob?.job?.cancel(CancellationException("书籍任务正在删除"))
            activeJob?.job?.join()

            val failures = withContext(NonCancellable) { deleteBookArtifacts(itemId, receipt) }
            lock.withLock {
                if (failures.isEmpty()) {
                    store.delete(receipt.canonicalKey)
                    mutableTasks.updateList { current ->
                        current.filterNot { it.canonicalKey == receipt.canonicalKey }
                    }
                    results.remove(receipt.canonicalKey)?.cancel(CancellationException("书籍任务已删除"))
                } else {
                    saveLocked(
                        receipt.copy(
                            status = BookDownloadStatus.FAILED,
                            failureReason = DELETE_FAILED,
                            error = "未清理完成：${failures.joinToString()}",
                            updatedAtEpochMillis = clock(),
                        )
                    )
                }
            }
        }
    }

    private suspend fun enqueueBookDownload(
        itemId: UUID,
        title: String?,
        credentials: BookDownloadCredentials,
    ): BookDownloadEnqueueResult {
        val canonicalKey = canonicalBookDownloadKey(credentials, itemId)
        return lock.withLock {
            if (hasUnresolvedDeleteReceiptLocked(itemId)) {
                return@withLock BookDownloadEnqueueResult.BlockedByDelete
            }
            if (jobs[canonicalKey]?.job?.isActive == true) {
                return@withLock BookDownloadEnqueueResult.AlreadyQueued
            }
            val target = localFileProvider(itemId)
            if (target.isComplete()) {
                partialFileProvider(itemId).delete()
                return@withLock BookDownloadEnqueueResult.AlreadyDownloaded
            }
            val existing = tasks.value.firstOrNull { it.canonicalKey == canonicalKey }
            val generation = (existing?.taskGeneration ?: 0L) + 1L
            val partial =
                preparePartialFile(
                    canonicalKey = canonicalKey,
                    basePartial = partialFileProvider(itemId),
                    existing = existing,
                    generation = generation,
                )
            val now = clock()
            val running =
                BookDownloadTask(
                    itemId = itemId,
                    title = title ?: existing?.title ?: readTitle(itemId),
                    downloadedBytes = partial.takeIf { it.isFile }?.length() ?: 0L,
                    totalBytes = existing?.totalBytes ?: 0L,
                    status = BookDownloadStatus.RUNNING,
                    failureReason = null,
                    taskGeneration = generation,
                    canonicalKey = canonicalKey,
                    serverId = credentials.serverId,
                    userId = credentials.userId,
                    targetPath = target.absolutePath,
                    resumeValidator = existing?.resumeValidator,
                    createdAtEpochMillis =
                        existing?.createdAtEpochMillis?.takeIf { it > 0L } ?: now,
                    updatedAtEpochMillis = now,
                )
            saveLocked(running)
            launchLocked(running, credentials, target, partial)
            BookDownloadEnqueueResult.Queued
        }
    }

    private suspend fun restore() {
        try {
            val loaded =
                store.load().mapNotNull { dto ->
                    runCatching { dto.toBookDownloadTask() }.getOrNull()
                }
            val reconciled = reconcileCoexistingDeleteReceipts(loaded)
            lock.withLock {
                mutableTasks.value =
                    reconciled.sortedWith(
                        compareBy<BookDownloadTask> { it.updatedAtEpochMillis }
                            .thenBy { it.canonicalKey }
                    )
            }

            for (task in reconciled) {
                if (task.isUnresolvedDeleteReceipt()) {
                    retryDeletionDuringRestore(task)
                    continue
                }
                if (task.failureReason == DELETE_SUPERSEDED) continue
                val target = task.targetPath?.let(::File) ?: localFileProvider(task.itemId)
                val basePartial = File(target.parentFile, "${target.name}.part")
                if (target.isComplete()) {
                    val recorded =
                        recordProvenance(
                            BookDownloadOrigin(
                                serverId = task.serverId,
                                userId = task.userId,
                                itemId = task.itemId,
                                displayName = task.title ?: readTitle(task.itemId),
                            )
                        )
                    if (recorded) {
                        basePartial.delete()
                        transitionRecovered(task, BookDownloadStatus.COMPLETED, null, null)
                    } else {
                        transitionRecovered(
                            task,
                            BookDownloadStatus.FAILED,
                            "PROVENANCE_WRITE_FAILED",
                            "书籍已下载，但来源记录写入失败，将在重启或重试时补写",
                        )
                    }
                    continue
                }
                if (task.status != BookDownloadStatus.RUNNING) continue
                val credentials = runCatching {
                    credentialsProvider()
                }
                    .getOrElse {
                        transitionIfCurrent(
                            task,
                            BookDownloadStatus.FAILED,
                            "ORIGIN_UNAVAILABLE",
                            "当前登录来源不可用，已保留断点",
                        )
                        continue
                    }
                if (credentials.serverId != task.serverId || credentials.userId != task.userId) {
                    transitionIfCurrent(
                        task,
                        BookDownloadStatus.FAILED,
                        "ORIGIN_UNAVAILABLE",
                        "请切回原服务器 / 用户后继续下载",
                    )
                    continue
                }
                val generation = task.taskGeneration + 1L
                val partial =
                    preparePartialFile(
                        canonicalKey = task.canonicalKey,
                        basePartial = basePartial,
                        existing = task,
                        generation = generation,
                    )
                val resumed =
                    task.copy(
                        status = BookDownloadStatus.RUNNING,
                        failureReason = null,
                        error = null,
                        taskGeneration = generation,
                        downloadedBytes = partial.takeIf { it.isFile }?.length() ?: 0L,
                        targetPath = target.absolutePath,
                        updatedAtEpochMillis = clock(),
                    )
                lock.withLock { launchLocked(resumed, credentials, target, partial) }
            }
        } finally {
            restored.complete(Unit)
        }
    }

    private suspend fun launchLocked(
        task: BookDownloadTask,
        credentials: BookDownloadCredentials,
        target: File,
        partial: File,
    ) {
        if (jobs[task.canonicalKey]?.job?.isActive == true) return
        val running =
            task.copy(
                status = BookDownloadStatus.RUNNING,
                failureReason = null,
                error = null,
                downloadedBytes = partial.takeIf { it.isFile }?.length() ?: 0L,
                targetPath = target.absolutePath,
                updatedAtEpochMillis = clock(),
            )
        val commitFile = commitFileFor(target, running.taskGeneration)
        saveLocked(running)
        val result = CompletableDeferred<File>()
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                runDownload(running, credentials, target, partial, commitFile, result)
            }
        results[running.canonicalKey] = result
        jobs[running.canonicalKey] = ActiveBookDownloadJob(running.taskGeneration, job)
        job.start()
    }

    private suspend fun runDownload(
        task: BookDownloadTask,
        credentials: BookDownloadCredentials,
        target: File,
        partial: File,
        commitFile: File,
        result: CompletableDeferred<File>,
    ) {
        var lastPersistedBytes = task.downloadedBytes
        try {
            val transferred =
                transfer.download(
                    request =
                        BookDownloadTransferRequest(
                            itemId = task.itemId,
                            targetFile = commitFile,
                            partialFile = partial,
                            credentials = credentials,
                            resumeValidator = task.resumeValidator,
                            taskGeneration = task.taskGeneration,
                        ),
                    onMetadata = { validator, totalBytes ->
                        updateIfCurrent(task) { current ->
                            current.copy(
                                downloadedBytes =
                                    partial.takeIf { it.isFile }?.length()
                                        ?: current.downloadedBytes,
                                totalBytes = totalBytes.coerceAtLeast(0L),
                                resumeValidator = validator,
                            )
                        }
                    },
                    onProgress = { downloadedBytes, totalBytes ->
                        val current =
                            updateIfCurrent(task, persist = false) {
                                it.copy(
                                    downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                                    totalBytes = totalBytes.coerceAtLeast(0L),
                                )
                            }
                        if (
                            current != null &&
                                downloadedBytes - lastPersistedBytes >= PROGRESS_SAVE_INTERVAL_BYTES
                        ) {
                            lastPersistedBytes = downloadedBytes
                            persistIfCurrent(current)
                        }
                    },
                )

            if (!isCurrent(task)) return
            currentCoroutineContext().ensureActive()
            if (!commitFile.isComplete()) {
                throw IOException("下载 transfer 返回时完整文件尚未原子提交")
            }
            val fetchedTitle = runCatching { fetchTitle(credentials, task.itemId) }.getOrNull()
            if (!fetchedTitle.isNullOrBlank()) writeTitle(task.itemId, fetchedTitle)
            val committed = lock.withLock {
                val current =
                    tasks.value.firstOrNull { it.canonicalKey == task.canonicalKey }
                        ?: return@withLock CommitOutcome.Superseded
                if (
                    current.taskGeneration != task.taskGeneration ||
                        current.status != BookDownloadStatus.RUNNING
                ) {
                    return@withLock CommitOutcome.Superseded
                }
                atomicMove(commitFile, target)
                val recorded =
                    recordProvenance(
                        BookDownloadOrigin(
                            serverId = credentials.serverId,
                            userId = credentials.userId,
                            itemId = task.itemId,
                            displayName = fetchedTitle ?: task.title ?: readTitle(task.itemId),
                        )
                    )
                if (!recorded) {
                    val failed =
                        current.copy(
                            title = fetchedTitle ?: current.title ?: readTitle(current.itemId),
                            downloadedBytes = target.length(),
                            totalBytes = target.length(),
                            status = BookDownloadStatus.FAILED,
                            error = "书籍已下载，但来源记录写入失败",
                            failureReason = "PROVENANCE_WRITE_FAILED",
                            updatedAtEpochMillis = clock(),
                        )
                    saveLocked(failed)
                    return@withLock CommitOutcome.ProvenanceFailed
                }
                val completed =
                    current.copy(
                        title = fetchedTitle ?: current.title ?: readTitle(current.itemId),
                        downloadedBytes = target.length(),
                        totalBytes = target.length(),
                        status = BookDownloadStatus.COMPLETED,
                        error = null,
                        failureReason = null,
                        resumeValidator = transferred.validator ?: current.resumeValidator,
                        updatedAtEpochMillis = clock(),
                    )
                saveLocked(completed)
                CommitOutcome.Completed(completed)
            }
            when (committed) {
                is CommitOutcome.Completed -> result.complete(target)
                CommitOutcome.ProvenanceFailed ->
                    result.completeExceptionally(IOException("书籍来源记录写入失败，任务保持可重试"))
                CommitOutcome.Superseded -> Unit
            }
        } catch (cancellation: CancellationException) {
            result.cancel(cancellation)
            if (isCurrent(task)) {
                clearProvenance(task.itemId)
                transitionIfCurrent(
                    task,
                    BookDownloadStatus.FAILED,
                    "CANCELLED",
                    "下载已取消，已保留断点",
                )
            }
            throw cancellation
        } catch (error: Throwable) {
            if (isCurrent(task)) {
                clearProvenance(task.itemId)
                transitionIfCurrent(
                    task,
                    BookDownloadStatus.FAILED,
                    "ERROR",
                    error.message ?: "下载失败，请检查网络",
                )
                result.completeExceptionally(error)
            }
        } finally {
            val currentJob = currentCoroutineContext()[Job]
            withContext(NonCancellable) {
                lock.withLock {
                    if (jobs[task.canonicalKey]?.job == currentJob) {
                        jobs.remove(task.canonicalKey)
                    }
                    if (retiringJobs[task.canonicalKey]?.job == currentJob) {
                        retiringJobs.remove(task.canonicalKey)
                    }
                    if (results[task.canonicalKey] == result) {
                        results.remove(task.canonicalKey)
                    }
                }
                commitFile.delete()
            }
        }
    }

    private suspend fun cancelByKey(canonicalKey: String) {
        val active = lock.withLock {
            val task =
                tasks.value.firstOrNull {
                    it.canonicalKey == canonicalKey && it.status == BookDownloadStatus.RUNNING
                } ?: return@withLock null
            val activeJob = jobs[canonicalKey] ?: return@withLock null
            if (activeJob.generation != task.taskGeneration) return@withLock null
            val failed =
                task.copy(
                    status = BookDownloadStatus.FAILED,
                    failureReason = "CANCELLED",
                    error = "下载已取消，已保留断点",
                    updatedAtEpochMillis = clock(),
                )
            saveLocked(failed)
            results.remove(canonicalKey)
            jobs.remove(canonicalKey)?.also { retiringJobs[canonicalKey] = it }
            activeJob
        }
        active?.job?.cancel(CancellationException("书籍下载已取消"))
    }

    private suspend fun updateIfCurrent(
        expected: BookDownloadTask,
        persist: Boolean = true,
        transform: (BookDownloadTask) -> BookDownloadTask,
    ): BookDownloadTask? = lock.withLock {
        val current =
            tasks.value.firstOrNull { it.canonicalKey == expected.canonicalKey }
                ?: return@withLock null
        if (
            current.taskGeneration != expected.taskGeneration ||
                current.status != BookDownloadStatus.RUNNING
        ) {
            return@withLock null
        }
        val updated = transform(current).copy(updatedAtEpochMillis = clock())
        if (persist) saveLocked(updated) else replaceInMemoryLocked(updated)
        updated
    }

    private suspend fun persistIfCurrent(task: BookDownloadTask) {
        lock.withLock {
            val current =
                tasks.value.firstOrNull { it.canonicalKey == task.canonicalKey } ?: return@withLock
            if (
                current.taskGeneration == task.taskGeneration &&
                    current.status == BookDownloadStatus.RUNNING
            ) {
                store.save(current.toDto())
            }
        }
    }

    private suspend fun transitionIfCurrent(
        expected: BookDownloadTask,
        status: BookDownloadStatus,
        failureReason: String?,
        error: String?,
    ): BookDownloadTask? =
        updateIfCurrent(expected) {
            it.copy(
                status = status,
                failureReason = failureReason,
                error = error,
                updatedAtEpochMillis = clock(),
            )
        }

    private suspend fun transitionRecovered(
        expected: BookDownloadTask,
        status: BookDownloadStatus,
        failureReason: String?,
        error: String?,
    ): BookDownloadTask? = lock.withLock {
        val current =
            tasks.value.firstOrNull { it.canonicalKey == expected.canonicalKey }
                ?: return@withLock null
        if (current.taskGeneration != expected.taskGeneration) return@withLock null
        val updated =
            current.copy(
                status = status,
                failureReason = failureReason,
                error = error,
                updatedAtEpochMillis = clock(),
            )
        saveLocked(updated)
        updated
    }

    private suspend fun isCurrent(expected: BookDownloadTask): Boolean = lock.withLock {
        tasks.value.any {
            it.canonicalKey == expected.canonicalKey &&
                it.taskGeneration == expected.taskGeneration &&
                it.status == BookDownloadStatus.RUNNING
        }
    }

    private suspend fun saveLocked(task: BookDownloadTask) {
        store.save(task.toDto())
        replaceInMemoryLocked(task)
    }

    private fun replaceInMemoryLocked(task: BookDownloadTask) {
        mutableTasks.updateList { tasks ->
            tasks.filterNot { it.canonicalKey == task.canonicalKey } + task
        }
    }

    private suspend fun awaitVisibleFile(
        fileProvider: () -> File,
        deadlineMs: Long,
    ): File {
        val deadline = clock() + deadlineMs.coerceAtLeast(0L)
        while (true) {
            val file = fileProvider()
            if (file.isComplete()) return file
            val remaining = deadline - clock()
            if (remaining <= 0L) {
                throw IOException("下载任务已标记为已有文件，但文件在 deadline 内仍不可见")
            }
            delay(remaining.coerceAtMost(FILE_VISIBILITY_POLL_MS))
        }
    }

    private fun File.isComplete(): Boolean = isFile && length() > 0L

    private suspend fun completedFileIfReady(itemId: UUID): File? = lock.withLock {
        val target = localFileProvider(itemId)
        if (!target.isComplete()) return@withLock null
        if (hasUnresolvedDeleteReceiptLocked(itemId)) return@withLock null

        val task =
            tasks.value
                .asSequence()
                .filter { it.itemId == itemId && !it.isDeleteRecord() }
                .sortedByDescending { it.updatedAtEpochMillis }
                .firstOrNull() ?: return@withLock target
        if (
            task.status == BookDownloadStatus.RUNNING &&
                jobs[task.canonicalKey]?.job?.isActive == true
        ) {
            return@withLock null
        }

        val recorded =
            recordProvenance(
                BookDownloadOrigin(
                    serverId = task.serverId,
                    userId = task.userId,
                    itemId = task.itemId,
                    displayName = task.title ?: readTitle(task.itemId),
                )
            )
        if (!recorded) {
            if (task.failureReason != "PROVENANCE_WRITE_FAILED") {
                saveLocked(
                    task.copy(
                        status = BookDownloadStatus.FAILED,
                        failureReason = "PROVENANCE_WRITE_FAILED",
                        error = "书籍已下载，但来源记录写入失败",
                        updatedAtEpochMillis = clock(),
                    )
                )
            }
            throw IOException("书籍来源记录写入失败，任务保持可重试")
        }

        saveLocked(
            task.copy(
                downloadedBytes = target.length(),
                totalBytes = target.length(),
                status = BookDownloadStatus.COMPLETED,
                failureReason = null,
                error = null,
                updatedAtEpochMillis = clock(),
            )
        )
        val basePartial = File(target.parentFile, "${target.name}.part")
        basePartial.delete()
        target.parentFile?.let { parent -> deleteGeneratedSiblings(parent, "${basePartial.name}.") }
        target
    }

    private fun itemLifecycle(itemId: UUID): Mutex =
        synchronized(itemLifecycles) { itemLifecycles.getOrPut(itemId) { Mutex() } }

    private fun selectDeleteTaskLocked(itemId: UUID, preferredKey: String?): BookDownloadTask? =
        tasks.value
            .asSequence()
            .filter { it.itemId == itemId && !it.isDeleteRecord() }
            .sortedByDescending { it.updatedAtEpochMillis }
            .firstOrNull { it.canonicalKey == preferredKey }
            ?: tasks.value
                .asSequence()
                .filter { it.itemId == itemId && !it.isDeleteRecord() }
                .sortedByDescending { it.updatedAtEpochMillis }
                .firstOrNull()
            ?: tasks.value
                .asSequence()
                .filter { it.itemId == itemId }
                .sortedByDescending { it.updatedAtEpochMillis }
                .firstOrNull { it.canonicalKey == preferredKey }
            ?: tasks.value
                .asSequence()
                .filter { it.itemId == itemId }
                .sortedByDescending { it.updatedAtEpochMillis }
                .firstOrNull()

    private fun deleteReceipt(
        itemId: UUID,
        credentials: BookDownloadCredentials?,
    ): BookDownloadTask {
        val target = localFileProvider(itemId)
        val basePartial = partialFileProvider(itemId)
        val now = clock()
        val serverId = credentials?.serverId ?: LOCAL_DELETE_SERVER_ID
        val userId = credentials?.userId ?: LOCAL_DELETE_USER_ID
        return BookDownloadTask(
            itemId = itemId,
            title = readTitle(itemId),
            status = BookDownloadStatus.FAILED,
            failureReason = DELETE_PENDING,
            error = "正在清理书籍文件",
            canonicalKey =
                DownloadCanonicalIdentity.of(
                        mediaKind = DownloadMediaKind.BOOK,
                        itemId = itemId.toString(),
                        serverId = serverId,
                        userId = userId.toString(),
                        sourceId = null,
                    )
                    .canonicalKey,
            serverId = serverId,
            userId = userId,
            targetPath = target.absolutePath,
            taskGeneration = latestArtifactGeneration(target, basePartial),
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
    }

    private suspend fun retryDeletionDuringRestore(task: BookDownloadTask) {
        itemLifecycle(task.itemId).withLock {
            val failures = withContext(NonCancellable) { deleteBookArtifacts(task.itemId, task) }
            lock.withLock {
                if (failures.isEmpty()) {
                    store.delete(task.canonicalKey)
                    mutableTasks.updateList { current ->
                        current.filterNot { it.canonicalKey == task.canonicalKey }
                    }
                } else {
                    saveLocked(
                        task.copy(
                            status = BookDownloadStatus.FAILED,
                            failureReason = DELETE_FAILED,
                            error = "未清理完成：${failures.joinToString()}",
                            updatedAtEpochMillis = clock(),
                        )
                    )
                }
            }
        }
    }

    private fun deleteBookArtifacts(
        itemId: UUID,
        receipt: BookDownloadTask,
    ): List<String> {
        val failures = mutableListOf<String>()
        val target = receipt.targetPath?.let(::File) ?: localFileProvider(itemId)
        val basePartial = File(target.parentFile, "${target.name}.part")
        val partial = generationPartialFile(basePartial, receipt.taskGeneration)
        val commit = commitFileFor(target, receipt.taskGeneration)

        if (!deleteFile(target)) failures += "book:${target.name}"
        if (!deleteFile(basePartial)) failures += "part:${basePartial.name}"
        if (!deleteFile(partial)) failures += "part:${partial.name}"
        if (!deleteFile(commit)) failures += "commit:${commit.name}"
        if (!deleteTitle(itemId)) failures += "title"
        if (!clearProvenance(itemId)) failures += "provenance"
        return failures
    }

    private fun deleteGeneratedSiblings(parent: File, namePrefix: String): List<String> {
        val failures = mutableListOf<String>()
        parent
            .listFiles()
            ?.filter { file ->
                file.name.startsWith(namePrefix) &&
                    file.name.removePrefix(namePrefix).toLongOrNull() != null
            }
            ?.forEach { file -> if (!deleteFile(file)) failures += file.name }
        return failures
    }

    private fun preparePartialFile(
        canonicalKey: String,
        basePartial: File,
        existing: BookDownloadTask?,
        generation: Long,
    ): File {
        val destination = generationPartialFile(basePartial, generation)
        if (retiringJobs[canonicalKey]?.job?.isActive == true) return destination
        val previous =
            existing
                ?.let { generationPartialFile(basePartial, it.taskGeneration) }
                ?.takeIf { it.isFile }
        val source = previous ?: basePartial.takeIf { it.isFile }
        if (source != null && source != destination) {
            runCatching { atomicMove(source, destination) }
        }
        return destination
    }

    private fun generationPartialFile(basePartial: File, generation: Long): File =
        File(basePartial.parentFile, "${basePartial.name}.$generation")

    private fun commitFileFor(target: File, generation: Long): File =
        File(target.parentFile, "${target.name}.commit.$generation")

    private fun latestArtifactGeneration(target: File, basePartial: File): Long =
        target.parentFile
            ?.listFiles()
            .orEmpty()
            .mapNotNull { file ->
                when {
                    file.name.startsWith("${basePartial.name}.") ->
                        file.name.removePrefix("${basePartial.name}.").toLongOrNull()
                    file.name.startsWith("${target.name}.commit.") ->
                        file.name.removePrefix("${target.name}.commit.").toLongOrNull()
                    else -> null
                }
            }
            .maxOrNull() ?: 0L

    private fun hasUnresolvedDeleteReceiptLocked(itemId: UUID): Boolean =
        tasks.value.any { it.itemId == itemId && it.isUnresolvedDeleteReceipt() }

    private suspend fun reconcileCoexistingDeleteReceipts(
        loaded: List<BookDownloadTask>
    ): List<BookDownloadTask> {
        val currentTasksByItem = loaded.filterNot { it.isDeleteRecord() }.groupBy { it.itemId }
        val supersededKeys =
            loaded
                .asSequence()
                .filter { it.isUnresolvedDeleteReceipt() }
                .filter { receipt ->
                    currentTasksByItem[receipt.itemId].orEmpty().any { task ->
                        task.canonicalKey != receipt.canonicalKey
                    }
                }
                .map { it.canonicalKey }
                .toSet()
        if (supersededKeys.isEmpty()) return loaded

        return loaded.map { task ->
            if (task.canonicalKey !in supersededKeys) {
                task
            } else {
                task
                    .copy(
                        status = BookDownloadStatus.FAILED,
                        failureReason = DELETE_SUPERSEDED,
                        error = "旧删除请求已由同 item 的新下载取代",
                        updatedAtEpochMillis = maxOf(task.updatedAtEpochMillis, clock()),
                    )
                    .also { store.save(it.toDto()) }
            }
        }
    }

    private fun BookDownloadTask.isUnresolvedDeleteReceipt(): Boolean =
        failureReason == DELETE_PENDING || failureReason == DELETE_FAILED

    private fun BookDownloadTask.isDeleteRecord(): Boolean =
        isUnresolvedDeleteReceipt() || failureReason == DELETE_SUPERSEDED

    private fun atomicMove(source: File, target: File) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private data class ActiveBookDownloadJob(
        val generation: Long,
        val job: Job,
    )

    private sealed interface CommitOutcome {
        data class Completed(val task: BookDownloadTask) : CommitOutcome

        data object ProvenanceFailed : CommitOutcome

        data object Superseded : CommitOutcome
    }

    private companion object {
        const val LOCAL_DELETE_SERVER_ID = "__book_local_delete__"
        val LOCAL_DELETE_USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
        const val DELETE_PENDING = "DELETE_PENDING"
        const val DELETE_FAILED = "DELETE_FAILED"
        const val DELETE_SUPERSEDED = "DELETE_SUPERSEDED"
        const val PROGRESS_SAVE_INTERVAL_BYTES = 1024L * 1024L
        const val FILE_VISIBILITY_POLL_MS = 10L
    }
}

internal fun canonicalBookDownloadKey(
    credentials: BookDownloadCredentials,
    itemId: UUID,
): String =
    DownloadCanonicalIdentity.of(
            mediaKind = DownloadMediaKind.BOOK,
            itemId = itemId.toString(),
            serverId = credentials.serverId,
            userId = credentials.userId.toString(),
            sourceId = null,
        )
        .canonicalKey

private fun BookDownloadTaskDto.toBookDownloadTask(): BookDownloadTask =
    BookDownloadTask(
        itemId = itemId,
        title = title.takeIf { it.isNotBlank() },
        downloadedBytes = downloadedBytes.coerceAtLeast(0L),
        totalBytes = totalBytes.coerceAtLeast(0L),
        status =
            runCatching { BookDownloadStatus.valueOf(status) }
                .getOrDefault(BookDownloadStatus.FAILED),
        error = failureReason,
        failureReason = failureReason,
        taskGeneration = taskGeneration,
        canonicalKey = canonicalKey,
        serverId = serverId,
        userId = UUID.fromString(userId),
        targetPath = targetPath,
        resumeValidator = resumeValidator,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = updatedAt,
    )

private fun BookDownloadTask.toDto(): BookDownloadTaskDto =
    BookDownloadTaskDto(
        canonicalKey = canonicalKey,
        itemId = itemId,
        serverId = requireNotNull(serverId) { "书籍任务缺少 serverId" },
        userId = requireNotNull(userId) { "书籍任务缺少 userId" }.toString(),
        title = title.orEmpty(),
        targetPath = requireNotNull(targetPath) { "书籍任务缺少 targetPath" },
        downloadedBytes = downloadedBytes.coerceAtLeast(0L),
        totalBytes = totalBytes.coerceAtLeast(0L),
        status = status.name,
        failureReason = failureReason,
        resumeValidator = resumeValidator,
        taskGeneration = taskGeneration,
        createdAt = createdAtEpochMillis,
        updatedAt = updatedAtEpochMillis,
    )

private inline fun MutableStateFlow<List<BookDownloadTask>>.updateList(
    transform: (List<BookDownloadTask>) -> List<BookDownloadTask>
) {
    val current = value
    value = transform(current)
}
