package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.net.ConnectivityManager
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.network.JellyfinAuth
import com.zhangwenkang.cinefin.network.buildCertificateAwareOkHttpClient
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.DownloadMediaSidecar
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.json.JSONObject
import timber.log.Timber

/**
 * 阅读器数据实现（W3-R1：整书下载 + 离线进度队列 + 书签）。
 *
 * 只调用 Jellyfin 用户数据白名单接口与只读取书接口；媒体库只读。
 *
 * 下载能力复用边界：书籍与视频的下载形态不同（视频走 `Downloader` + `DownloadManager` + media source，依赖 Room
 * 条目），书籍是"整本落盘再打开"（ARCHITECTURE §3.3），因此这里复用 `JellyfinApi` 的地址 / 令牌与同一套 用户数据白名单接口，把字节流写进应用私有
 * 目录（`filesDir/books/`），不再另造通用下载器。
 */
class ReaderRepositoryImpl(
    context: Context,
    private val jellyfinApi: JellyfinApi,
    private val appPreferences: AppPreferences,
) : ReaderRepository {
    private val appContext = context.applicationContext
    private val progressStore =
        ReaderProgressStore(File(appContext.filesDir, "reader/progress.json"))
    private val bookmarkStore =
        ReaderBookmarkStore(File(appContext.filesDir, "reader/bookmarks.json"))
    private val httpClient =
        buildCertificateAwareOkHttpClient(
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build(),
            jellyfinApi.certificateTrustStore,
        )
    private val downloadMutex = Mutex()
    private val downloadSidecar = DownloadMediaSidecar(appContext)

    // ---- W80（#23）：书籍整本下载任务表 ----
    // 书架批量下载 / 阅读页「下载」都登记到这里：任务跑在 App 作用域（离开页面不取消），
    // 下载页读 `bookDownloads` 显示「下载中 xx%」；完成后仍由 `listLocalFiles()` 的已完成列表接手。
    private val bookDownloadTasks = MutableStateFlow<List<BookDownloadTask>>(emptyList())
    private val bookDownloadLock = Mutex()
    private val bookDownloadJobs = mutableMapOf<UUID, Job>()
    private val bookDownloadResults = mutableMapOf<UUID, CompletableDeferred<File>>()
    private val bookDownloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val bookDownloads: StateFlow<List<BookDownloadTask>> = bookDownloadTasks.asStateFlow()

    override suspend fun enqueueBookDownload(
        itemId: UUID,
        title: String?,
    ): BookDownloadEnqueueResult {
        // 在任何排队 / IO 调度前冻结账号身份；下载完成时不得重新读取 current server/user。
        val origin = freezeBookDownloadOrigin(itemId = itemId, title = title)
        return bookDownloadLock.withLock {
            if (bookDownloadJobs.containsKey(itemId))
                return@withLock BookDownloadEnqueueResult.AlreadyQueued
            val target = localFileFor(itemId)
            if (target.isFile && target.length() > 0) {
                return@withLock BookDownloadEnqueueResult.AlreadyDownloaded
            }
            val result = CompletableDeferred<File>()
            bookDownloadResults[itemId] = result
            publishBookTask(
                BookDownloadTask(
                    itemId = itemId,
                    title = title ?: readLocalTitle(itemId),
                    status = BookDownloadStatus.RUNNING,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                )
            )
            val job = bookDownloadScope.launch {
                // 数据层每 64 KB 回调一次；任务表按**整数百分比**节流，避免下载页每块都重组。
                var lastPercent = -2
                try {
                    val file =
                        download(
                            target = target,
                            itemId = itemId,
                            origin = origin,
                            onProgress = {},
                        ) { bytes, total ->
                            val percent = if (total > 0L) ((bytes * 100) / total).toInt() else -1
                            if (percent != lastPercent) {
                                lastPercent = percent
                                updateBookTask(itemId) { task ->
                                    task.copy(
                                        downloadedBytes = bytes,
                                        totalBytes = total,
                                        updatedAtEpochMillis = System.currentTimeMillis(),
                                    )
                                }
                            }
                        }
                    result.complete(file)
                    publishBookTask(
                        BookDownloadTask(
                            itemId = itemId,
                            title = readLocalTitle(itemId),
                            downloadedBytes = file.length(),
                            totalBytes = file.length(),
                            status = BookDownloadStatus.COMPLETED,
                            updatedAtEpochMillis = System.currentTimeMillis(),
                        )
                    )
                } catch (cancellation: CancellationException) {
                    result.cancel(cancellation)
                    removeBookTask(itemId)
                    throw cancellation
                } catch (error: Exception) {
                    result.completeExceptionally(error)
                    updateBookTask(itemId) { task ->
                        task.copy(
                            status = BookDownloadStatus.FAILED,
                            error = error.message ?: "下载失败，请检查网络",
                            updatedAtEpochMillis = System.currentTimeMillis(),
                        )
                    }
                } finally {
                    bookDownloadLock.withLock {
                        bookDownloadJobs.remove(itemId)
                        bookDownloadResults.remove(itemId)
                    }
                }
            }
            bookDownloadJobs[itemId] = job
            BookDownloadEnqueueResult.Queued
        }
    }

    override suspend fun awaitBookDownload(
        itemId: UUID,
        title: String?,
        onProgress: (Float) -> Unit,
    ): File {
        // 本地已有：直接命中，不进任务表（与 `downloadLocalFile` 的 1f 语义一致）。
        val existing = localFileFor(itemId)
        if (existing.isFile && existing.length() > 0) {
            onProgress(1f)
            return existing
        }
        return coroutineScope {
            val watcher = launch {
                bookDownloads
                    .map { tasks -> tasks.firstOrNull { it.itemId == itemId } }
                    .distinctUntilChanged()
                    .collect { task -> task?.let { onProgress(it.progress) } }
            }
            try {
                enqueueBookDownload(itemId, title)
                val result =
                    bookDownloadLock.withLock { bookDownloadResults[itemId] }
                        ?: CompletableDeferred()
                result.await()
            } finally {
                watcher.cancel()
            }
        }
    }

    override fun cancelBookDownload(itemId: UUID) {
        // 主线程调用即可：`cancel()` 只投递取消，不阻塞等锁。
        bookDownloadScope.launch {
            val job = bookDownloadLock.withLock { bookDownloadJobs[itemId] }
            job?.cancel()
        }
    }

    /**
     * W80b（D2）：取消并把任务**记录**从任务表移除。
     *
     * `cancelBookDownload` 只对**在跑**的任务有意义；已结束（失败）的任务没有 job 可取消，记录会一直留在任务表里，
     * 下载页的「失败」条目因此删不掉。用户主动删条目走这里：取消 + 立即移除记录。
     */
    override fun removeBookDownload(itemId: UUID) {
        cancelBookDownload(itemId)
        removeBookTask(itemId)
    }

    private fun publishBookTask(task: BookDownloadTask) {
        bookDownloadTasks.update { tasks -> tasks.filterNot { it.itemId == task.itemId } + task }
    }

    private fun updateBookTask(
        itemId: UUID,
        transform: (BookDownloadTask) -> BookDownloadTask,
    ) {
        bookDownloadTasks.update { tasks ->
            tasks.map { task -> if (task.itemId == itemId) transform(task) else task }
        }
    }

    private fun removeBookTask(itemId: UUID) {
        bookDownloadTasks.update { tasks -> tasks.filterNot { it.itemId == itemId } }
    }

    /** W77-3：打开路径的整本下载，进度透传给阅读页顶栏（PDF 过渡态「打开即显示进度」）。 */
    override suspend fun ensureLocalFile(itemId: UUID, onProgress: (Float) -> Unit): File =
        downloadLocalFile(itemId, onProgress)

    override fun readerHttpClient(): OkHttpClient = httpClient

    /** W77：未下载书籍的远程流式入口。地址 / 令牌来自 `JellyfinApi`（与下载路径同源）， 只读 GET / Range，不写服务器。 */
    override suspend fun remoteBookSource(itemId: UUID): RemoteBookSource? {
        val baseUrl =
            jellyfinApi.api.baseUrl?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val token = jellyfinApi.api.accessToken?.takeIf { it.isNotBlank() } ?: return null
        return RemoteBookSource(url = "$baseUrl/Items/$itemId/Download", token = token)
    }

    override suspend fun downloadLocalFile(itemId: UUID, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            downloadMutex.withLock {
                val target = localFileFor(itemId)
                if (target.isFile && target.length() > 0) {
                    onProgress(1f)
                    return@withContext target
                }
                // W77-3：先给一次 0f 表示「本次确实是整本下载」（响应没有 Content-Length 时调用方也能进「下载中」态）。
                onProgress(0f)
                download(
                    target = target,
                    itemId = itemId,
                    origin = freezeBookDownloadOrigin(itemId = itemId, title = null),
                    onProgress = onProgress,
                )
            }
        }

    override suspend fun localFile(itemId: UUID): LocalBookFile? =
        withContext(Dispatchers.IO) {
            val file = localFileFor(itemId)
            if (file.isFile && file.length() > 0) {
                LocalBookFile(itemId = itemId, sizeBytes = file.length())
            } else {
                null
            }
        }

    override suspend fun listLocalFiles(): List<LocalBookFile> =
        withContext(Dispatchers.IO) {
            File(appContext.filesDir, "books")
                .listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.name.endsWith(".book") && it.length() > 0 }
                ?.mapNotNull { file ->
                    runCatching { UUID.fromString(file.name.removeSuffix(".book")) }
                        .getOrNull()
                        ?.let { id ->
                            LocalBookFile(
                                itemId = id,
                                sizeBytes = file.length(),
                                title = readLocalTitle(id),
                            )
                        }
                }
                ?.sortedBy { it.itemId.toString() }
                ?.toList() ?: emptyList()
        }

    /**
     * W64：下载改为**可取消**——协程取消（阅读页返回 / ViewModel 清理）时取消在途 OkHttp call， 否则阻塞的 `execute()`
     * 会继续把整本书下完（用户观察到的"返回后仍占用网络"）。
     */
    private suspend fun download(
        target: File,
        itemId: UUID,
        origin: BookDownloadOrigin,
        onProgress: (Float) -> Unit,
        /** W80（#23）：字节级进度（下载页显示"已下载 / 总大小"用）。 */
        onBytes: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        target.parentFile?.mkdirs()
        // 先写 .part 再原子改名：中断的下载不会留下"看着像完整书"的半截文件。
        val partial = File(target.parentFile, "${target.name}.part")
        partial.delete()

        try {
            val baseUrl = requireNotNull(jellyfinApi.api.baseUrl) { "服务器地址为空" }
            val token = requireNotNull(jellyfinApi.api.accessToken) { "访问令牌为空" }
            val url = "${baseUrl.trimEnd('/')}/Items/$itemId/Download"
            val request =
                Request.Builder()
                    .url(url)
                    .header(JellyfinAuth.HEADER, JellyfinAuth.headerValue(token))
                    .build()

            val call = httpClient.newCall(request)
            val cancelHandle =
                currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) call.cancel()
                }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("下载书籍失败：HTTP ${response.code}（离线时请先联网下载）")
                    }
                    val totalBytes = response.body.contentLength()
                    var copiedBytes = 0L
                    onBytes(0L, totalBytes.coerceAtLeast(0L))
                    response.body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                copiedBytes += read
                                if (totalBytes > 0) {
                                    onProgress((copiedBytes.toDouble() / totalBytes).toFloat())
                                }
                                onBytes(copiedBytes, totalBytes.coerceAtLeast(0L))
                            }
                        }
                    }
                }
            } finally {
                cancelHandle?.dispose()
            }

            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            // W36：书名侧车——离线（无服务器会话）时离线书架 / 离线媒体库仍能显示真实书名。
            val fetchedTitle =
                fetchItemTitle(baseUrl = baseUrl, token = token, itemId = itemId)?.also { title ->
                    writeLocalTitle(itemId, title)
                }
            // 只有完整文件原子 rename 成功后才形成可信下载来源；失败 / 取消路径不会触碰 sidecar。
            recordBookDownloadProvenance(
                sidecar = downloadSidecar,
                origin =
                    origin.copy(
                        displayName = fetchedTitle ?: readLocalTitle(itemId) ?: origin.displayName
                    ),
            )
            onProgress(1f)
            return target
        } catch (error: Throwable) {
            partial.delete()
            clearBookDownloadProvenance(downloadSidecar, itemId)
            // 取消路径：OkHttp 被 cancel 抛出的 IOException 归一成 CancellationException，
            // 避免调用方把它当成"下载失败"展示。
            currentCoroutineContext().ensureActive()
            throw error
        }
    }

    override suspend fun deleteLocalFile(itemId: UUID) {
        withContext(Dispatchers.IO) {
            val target = localFileFor(itemId)
            target.delete()
            File(target.parentFile, "${target.name}.part").delete()
            titleFileFor(itemId).delete()
            downloadSidecar.removeBook(itemId)
        }
    }

    private fun freezeBookDownloadOrigin(
        itemId: UUID,
        title: String?,
    ): BookDownloadOrigin =
        BookDownloadOrigin(
            serverId = appPreferences.getValue(appPreferences.currentServer),
            userId = jellyfinApi.userId,
            itemId = itemId,
            displayName = title ?: readLocalTitle(itemId),
        )

    override suspend fun getReadingProgress(itemId: UUID): ReadingProgress? =
        withContext(Dispatchers.IO) {
            val local = progressStore.load(itemId)
            // 飞行模式 / 无网络时不发请求：直接用本地 locator 打开，避免打开阅读页先卡超时。
            // W64：网络活跃但服务器偶发慢时，远程进度查询会拖住打开首帧（用户感知"打开偶尔很慢"）；
            // 加 2s 上限，超时按"没有远程进度"处理，本地进度照常打开。
            val remote =
                if (!hasActiveNetwork()) {
                    null
                } else {
                    withTimeoutOrNull(REMOTE_PROGRESS_TIMEOUT_MS) {
                            runCatching { fetchRemoteProgress(itemId) }
                                .onFailure { error ->
                                    if (error is CancellationException) throw error
                                    Timber.w(error, "读取阅读进度失败，回退本地进度")
                                }
                                .getOrNull()
                        }
                        .also { result ->
                            if (result == null) {
                                Timber.i("远程阅读进度超时 / 失败（%s），本次用本地进度", itemId)
                            }
                        }
                }
            resolveReadingProgress(itemId, local, remote)
        }

    override suspend fun saveReadingProgress(itemId: UUID, progress: ReadingProgress) {
        withContext(Dispatchers.IO) {
            // W37：本地媒体库书籍（pendingSync=false）= 只落本机进度，不查服务器 RunTimeTicks、不回传。
            if (!progress.pendingSync) {
                progressStore.save(
                    progress.copy(
                        itemId = itemId,
                        progression = normalizedProgression(progress.progression),
                    )
                )
                return@withContext
            }
            // Jellyfin 会用 PlaybackPositionTicks / RunTimeTicks 重算百分比；
            // 书籍 runtime 可能只是 1 秒占位值，因此必须随条目动态换算。
            // 离线时拿不到 RunTimeTicks，用上次缓存值；两者都没有才沿用调用方给的 ticks。
            val runtimeTicks = ensureRuntimeTicks(itemId)
            val pending =
                progress.copy(
                    itemId = itemId,
                    progression = normalizedProgression(progress.progression),
                    positionTicks =
                        if (runtimeTicks > 0) {
                            progressionToTicks(progress.progression, runtimeTicks)
                        } else {
                            progress.positionTicks
                        },
                    runtimeTicks = runtimeTicks,
                    pendingSync = true,
                )
            progressStore.save(pending)
            syncProgress(pending)
        }
    }

    override suspend fun flushPendingProgress(): Int =
        progressStore.pending().count { syncProgress(it) }

    override suspend fun pendingProgressCount(): Int = progressStore.pendingCount()

    override suspend fun getBookmarks(itemId: UUID): List<ReaderBookmark> =
        bookmarkStore.list(itemId)

    override suspend fun saveBookmark(bookmark: ReaderBookmark) {
        bookmarkStore.save(bookmark)
    }

    override suspend fun deleteBookmark(itemId: UUID, bookmarkId: String) {
        bookmarkStore.delete(itemId, bookmarkId)
    }

    /** `RunTimeTicks` 缓存优先：避免每次保存都打网络，且保证离线换算正确。 */
    private suspend fun ensureRuntimeTicks(itemId: UUID): Long {
        val cached = progressStore.load(itemId)?.runtimeTicks ?: 0L
        if (cached > 0) return cached
        return fetchRunTimeTicks(itemId).takeIf { it > 0 } ?: 0L
    }

    private suspend fun fetchRemoteProgress(itemId: UUID): ReadingProgress {
        val userId = requireNotNull(jellyfinApi.userId) { "当前没有登录用户" }
        val data = jellyfinApi.itemsApi.getItemUserData(itemId, userId).content
        val runtimeTicks = fetchRunTimeTicks(itemId)
        if (runtimeTicks > 0) {
            progressStore.setRuntimeTicks(itemId, runtimeTicks)
        }
        val progression =
            data.playedPercentage?.div(100.0)
                ?: if (runtimeTicks > 0) {
                    data.playbackPositionTicks.toDouble() / runtimeTicks
                } else {
                    0.0
                }
        return ReadingProgress(
            itemId = itemId,
            progression = normalizedProgression(progression),
            positionTicks = data.playbackPositionTicks,
            updatedAt = data.lastPlayedDate?.toInstant() ?: Instant.EPOCH,
            runtimeTicks = runtimeTicks,
        )
    }

    private suspend fun fetchRunTimeTicks(itemId: UUID): Long = runCatching {
        val userId = requireNotNull(jellyfinApi.userId) { "当前没有登录用户" }
        jellyfinApi.userLibraryApi.getItem(itemId, userId).content.runTimeTicks ?: 0L
    }
        .onFailure { error -> if (error is CancellationException) throw error }
        .getOrDefault(0L)

    private suspend fun syncProgress(progress: ReadingProgress): Boolean =
        withContext(Dispatchers.IO) {
            val userId = jellyfinApi.userId ?: return@withContext false
            runCatching {
                jellyfinApi.itemsApi.updateItemUserData(
                    progress.itemId,
                    userId,
                    UpdateUserItemDataDto(
                        playedPercentage = normalizedProgression(progress.progression) * 100.0,
                        playbackPositionTicks = progress.positionTicks,
                        played = progress.progression >= 0.99,
                        lastPlayedDate = LocalDateTime.now(),
                    ),
                )
            }
                .onSuccess {
                    progressStore.markSyncedIfUnchanged(progress.itemId, progress.updatedAt)
                }
                .onFailure { Timber.w(it, "阅读进度回传失败，保留待同步标记") }
                .isSuccess
        }

    private fun localFileFor(itemId: UUID): File = File(appContext.filesDir, "books/$itemId.book")

    private fun titleFileFor(itemId: UUID): File = File(appContext.filesDir, "books/$itemId.title")

    /** W36：读取书名侧车；旧数据（无侧车）返回 null，由调用方给占位名。 */
    private fun readLocalTitle(itemId: UUID): String? = runCatching {
        titleFileFor(itemId).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
    }
        .getOrNull()

    private fun writeLocalTitle(itemId: UUID, title: String) {
        runCatching {
            val file = titleFileFor(itemId)
            file.parentFile?.mkdirs()
            file.writeText(title.trim())
        }
            .onFailure { Timber.w(it, "写入书籍标题侧车失败") }
    }

    /** 下载完成后补一次书名（失败不影响下载结果，只影响离线显示名）。 */
    private fun fetchItemTitle(baseUrl: String, token: String, itemId: UUID): String? =
        runCatching {
            // 优先走 `/Users/{userId}/Items/{id}`（10.11.8 稳定接口）；没有账号时退回 `/Items/{id}`。
            val url =
                jellyfinApi.userId?.let { userId ->
                    "${baseUrl.trimEnd('/')}/Users/$userId/Items/$itemId"
                } ?: "${baseUrl.trimEnd('/')}/Items/$itemId"
            val request =
                Request.Builder()
                    .url(url)
                    .header(JellyfinAuth.HEADER, JellyfinAuth.headerValue(token))
                    .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body.string()).optString("Name").takeIf { it.isNotBlank() }
            }
        }
        .onFailure { Timber.w(it, "读取书籍标题失败") }
        .getOrNull()

    private fun hasActiveNetwork(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        return manager.activeNetwork != null
    }

    private companion object {
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        /** 打开书籍时远程进度查询的等待上限：超时回退本地进度（W64 打开耗时优化）。 */
        const val REMOTE_PROGRESS_TIMEOUT_MS = 2_000L
    }
}

private fun LocalDateTime.toInstant(): Instant = atZone(ZoneId.systemDefault()).toInstant()
