package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.net.ConnectivityManager
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.network.JellyfinAuth
import com.zhangwenkang.cinefin.network.buildCertificateAwareOkHttpClient
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.DownloadMediaSidecar
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    private val downloadSidecar = DownloadMediaSidecar(appContext)

    // 任务真源是 Room DAO；协调器固定在 App 作用域，页面销毁不会接管执行或取消。
    private val bookDownloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bookDownloadManager =
        BookDownloadManager(
            store =
                DaoBookDownloadTaskStore(
                    EntryPointAccessors.fromApplication(
                            appContext,
                            BookDownloadTaskDaoEntryPoint::class.java,
                        )
                        .database()
                ),
            transfer = BookDownloadRangeTransfer(httpClient),
            credentialsProvider = { freezeBookDownloadCredentials() },
            localFileProvider = ::localFileFor,
            partialFileProvider = ::partialFileFor,
            scope = bookDownloadScope,
            recordProvenance = { origin ->
                recordBookDownloadProvenance(sidecar = downloadSidecar, origin = origin)
            },
            clearProvenance = { itemId -> clearBookDownloadProvenance(downloadSidecar, itemId) },
            fetchTitle = { credentials, itemId ->
                fetchItemTitle(
                    baseUrl = credentials.baseUrl,
                    token = credentials.accessToken,
                    userId = credentials.userId,
                    itemId = itemId,
                )
            },
            writeTitle = ::writeLocalTitle,
            readTitle = ::readLocalTitle,
            deleteTitle = { itemId ->
                val title = titleFileFor(itemId)
                runCatching { !title.exists() || title.delete() }.getOrDefault(false)
            },
        )

    override val bookDownloads: StateFlow<List<BookDownloadTask>> = bookDownloadManager.tasks

    override suspend fun enqueueBookDownload(
        itemId: UUID,
        title: String?,
    ): BookDownloadEnqueueResult = bookDownloadManager.enqueueBookDownload(itemId, title)

    override suspend fun awaitBookDownload(
        itemId: UUID,
        title: String?,
        onProgress: (Float) -> Unit,
    ): File {
        return coroutineScope {
            val watcher = launch {
                bookDownloadManager.tasks
                    .map { tasks ->
                        tasks.filter { it.itemId == itemId }.maxByOrNull { it.updatedAtEpochMillis }
                    }
                    .distinctUntilChanged()
                    .collect { task -> task?.let { onProgress(it.progress) } }
            }
            try {
                bookDownloadManager.awaitBookDownload(itemId, title).also { onProgress(1f) }
            } finally {
                watcher.cancel()
            }
        }
    }

    override fun cancelBookDownload(itemId: UUID) = bookDownloadManager.cancelBookDownload(itemId)

    /**
     * W80b（D2）：取消并把任务**记录**从任务表移除。
     *
     * `cancelBookDownload` 只对**在跑**的任务有意义；已结束（失败）的任务没有 job 可取消，记录会一直留在任务表里，
     * 下载页的「失败」条目因此删不掉。用户主动删条目走这里：取消 + 立即移除记录。
     */
    override fun removeBookDownload(itemId: UUID) {
        bookDownloadScope.launch(Dispatchers.IO) { bookDownloadManager.deleteBookDownload(itemId) }
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

    override suspend fun downloadLocalFile(itemId: UUID, onProgress: (Float) -> Unit): File {
        val existing = withContext(Dispatchers.IO) { localFileFor(itemId) }
        if (existing.isFile && existing.length() > 0L) {
            onProgress(1f)
            return existing
        }
        // 0f 表示本次确实发生整本下载；响应没有 Content-Length 时调用方也能进入「下载中」态。
        onProgress(0f)
        return bookDownloadManager.awaitBookDownload(itemId, title = null).also { onProgress(1f) }
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

    override suspend fun deleteLocalFile(
        itemId: UUID,
        canonicalKey: String?,
    ): BookDownloadDeleteResult =
        withContext(Dispatchers.IO) { bookDownloadManager.deleteBookDownload(itemId, canonicalKey) }

    private fun freezeBookDownloadCredentials(): BookDownloadCredentials =
        BookDownloadCredentials(
            serverId =
                requireNotNull(
                    appPreferences.getValue(appPreferences.currentServer)?.takeIf {
                        it.isNotBlank()
                    }
                ) {
                    "当前没有登录服务器"
                },
            userId = requireNotNull(jellyfinApi.userId) { "当前没有登录用户" },
            baseUrl =
                requireNotNull(jellyfinApi.api.baseUrl?.trimEnd('/')?.takeIf { it.isNotBlank() }) {
                    "服务器地址为空"
                },
            accessToken =
                requireNotNull(jellyfinApi.api.accessToken?.takeIf { it.isNotBlank() }) {
                    "访问令牌为空"
                },
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

    private fun partialFileFor(itemId: UUID): File =
        File(appContext.filesDir, "books/$itemId.book.part")

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
    private fun fetchItemTitle(
        baseUrl: String,
        token: String,
        userId: UUID,
        itemId: UUID,
    ): String? = runCatching {
        // userId 来自入队时冻结的 origin；下载途中切号不得改写 provenance。
        val url = "${baseUrl.trimEnd('/')}/Users/$userId/Items/$itemId"
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
        /** 打开书籍时远程进度查询的等待上限：超时回退本地进度（W64 打开耗时优化）。 */
        const val REMOTE_PROGRESS_TIMEOUT_MS = 2_000L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface BookDownloadTaskDaoEntryPoint {
    fun database(): ServerDatabaseDao
}

private fun LocalDateTime.toInstant(): Instant = atZone(ZoneId.systemDefault()).toInstant()
