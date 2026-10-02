package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.net.ConnectivityManager
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.network.buildCertificateAwareOkHttpClient
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    override suspend fun ensureLocalFile(itemId: UUID): File = downloadLocalFile(itemId) {}

    override suspend fun downloadLocalFile(itemId: UUID, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            downloadMutex.withLock {
                val target = localFileFor(itemId)
                if (target.isFile && target.length() > 0) {
                    onProgress(1f)
                    return@withContext target
                }
                download(target, itemId, onProgress)
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

    private fun download(
        target: File,
        itemId: UUID,
        onProgress: (Float) -> Unit,
    ): File {
        target.parentFile?.mkdirs()
        // 先写 .part 再原子改名：中断的下载不会留下"看着像完整书"的半截文件。
        val partial = File(target.parentFile, "${target.name}.part")
        partial.delete()

        try {
            val baseUrl = requireNotNull(jellyfinApi.api.baseUrl) { "服务器地址为空" }
            val token = requireNotNull(jellyfinApi.api.accessToken) { "访问令牌为空" }
            val url = "${baseUrl.trimEnd('/')}/Items/$itemId/Download"
            val request = Request.Builder().url(url).header("X-Emby-Token", token).build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("下载书籍失败：HTTP ${response.code}（离线时请先联网下载）")
                }
                val totalBytes = response.body.contentLength()
                var copiedBytes = 0L
                response.body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            copiedBytes += read
                            if (totalBytes > 0) {
                                onProgress((copiedBytes.toDouble() / totalBytes).toFloat())
                            }
                        }
                    }
                }
            }

            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            // W36：书名侧车——离线（无服务器会话）时离线书架 / 离线媒体库仍能显示真实书名。
            fetchItemTitle(baseUrl = baseUrl, token = token, itemId = itemId)?.let { title ->
                writeLocalTitle(itemId, title)
            }
            onProgress(1f)
            return target
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    override suspend fun deleteLocalFile(itemId: UUID) {
        withContext(Dispatchers.IO) {
            val target = localFileFor(itemId)
            target.delete()
            File(target.parentFile, "${target.name}.part").delete()
            titleFileFor(itemId).delete()
        }
    }

    override suspend fun getReadingProgress(itemId: UUID): ReadingProgress? =
        withContext(Dispatchers.IO) {
            val local = progressStore.load(itemId)
            // 飞行模式 / 无网络时不发请求：直接用本地 locator 打开，避免打开阅读页先卡超时。
            val remote =
                if (!hasActiveNetwork()) {
                    null
                } else {
                    runCatching { fetchRemoteProgress(itemId) }
                        .onFailure { Timber.w(it, "读取阅读进度失败，回退本地进度") }
                        .getOrNull()
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
            val request = Request.Builder().url(url).header("X-Emby-Token", token).build()
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
    }
}

private fun LocalDateTime.toInstant(): Instant = atZone(ZoneId.systemDefault()).toInstant()
