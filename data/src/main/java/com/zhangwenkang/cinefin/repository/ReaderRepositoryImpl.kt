package com.zhangwenkang.cinefin.repository

import android.content.Context
import com.zhangwenkang.cinefin.api.JellyfinApi
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import timber.log.Timber

/**
 * 阅读器数据实现（W1）。
 *
 * 只调用 Jellyfin 用户数据白名单接口；媒体库只读。
 */
class ReaderRepositoryImpl(
    context: Context,
    private val jellyfinApi: JellyfinApi,
) : ReaderRepository {
    private val appContext = context.applicationContext
    private val progressStore =
        ReaderProgressStore(File(appContext.filesDir, "reader/progress.json"))
    private val httpClient = OkHttpClient()

    override suspend fun ensureLocalFile(itemId: UUID): File =
        withContext(Dispatchers.IO) {
            val target = localFile(itemId)
            if (target.isFile && target.length() > 0) {
                return@withContext target
            }

            target.parentFile?.mkdirs()
            val baseUrl = requireNotNull(jellyfinApi.api.baseUrl) { "服务器地址为空" }
            val token = requireNotNull(jellyfinApi.api.accessToken) { "访问令牌为空" }
            val url = "${baseUrl.trimEnd('/')}/Items/$itemId/Download"
            val request = Request.Builder().url(url).header("X-Emby-Token", token).build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("下载书籍失败：HTTP ${response.code}")
                }
                response.body.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            target
        }

    override suspend fun deleteLocalFile(itemId: UUID) {
        localFile(itemId).delete()
        progressStore.remove(itemId)
    }

    override suspend fun getReadingProgress(itemId: UUID): ReadingProgress? =
        withContext(Dispatchers.IO) {
            val local = progressStore.load(itemId)
            val remote = runCatching {
                fetchRemoteProgress(itemId)
            }
                .onFailure { Timber.w(it, "读取阅读进度失败，回退本地进度") }
                .getOrNull()
            resolveReadingProgress(itemId, local, remote)
        }

    override suspend fun saveReadingProgress(itemId: UUID, progress: ReadingProgress) {
        // Jellyfin 会用 PlaybackPositionTicks / RunTimeTicks 重算百分比；
        // 书籍 runtime 可能只是 1 秒占位值，因此必须随条目动态换算。
        val runtimeTicks = fetchRunTimeTicks(itemId)
        val pending =
            progress.copy(
                itemId = itemId,
                progression = normalizedProgression(progress.progression),
                positionTicks = progressionToTicks(progress.progression, runtimeTicks),
                pendingSync = true,
            )
        progressStore.save(pending)
        syncProgress(pending)
    }

    override suspend fun flushPendingProgress() {
        progressStore.pending().forEach { syncProgress(it) }
    }

    private suspend fun fetchRemoteProgress(itemId: UUID): ReadingProgress {
        val userId = requireNotNull(jellyfinApi.userId) { "当前没有登录用户" }
        val data = jellyfinApi.itemsApi.getItemUserData(itemId, userId).content
        val runtimeTicks = fetchRunTimeTicks(itemId)
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
                .onSuccess { progressStore.markSynced(progress.itemId) }
                .onFailure { Timber.w(it, "阅读进度回传失败，保留待同步标记") }
                .isSuccess
        }

    private fun localFile(itemId: UUID): File = File(appContext.filesDir, "books/$itemId.book")
}

private fun LocalDateTime.toInstant(): Instant = atZone(ZoneId.systemDefault()).toInstant()
