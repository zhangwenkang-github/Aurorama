package com.zhangwenkang.cinefin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * W60：图片缓存 worker 的失败重试口径（纯函数，单测覆盖）。
 *
 * 网络类瞬时失败（连接中断 / 超时 / 5xx / 408 / 429）最多尝试 [MAX_ATTEMPTS] 次； 每次重试由 WorkManager 的指数退避（默认 30s
 * 起）延迟，已落盘的图片幂等跳过。 单个非法地址 / 4xx 属于永久失败，不触发整体重试。
 */
internal object ImagesDownloadRetryRules {
    const val MAX_ATTEMPTS = 3

    /** `runAttemptCount` 从 0 开始（本次为第 runAttemptCount + 1 次尝试）；还有剩余尝试才重试。 */
    fun shouldRetry(hadTransientFailure: Boolean, runAttemptCount: Int): Boolean =
        hadTransientFailure && runAttemptCount < MAX_ATTEMPTS - 1

    /** 5xx / 408 / 429 视为瞬时失败；其余非 2xx 为永久失败（忽略该图，不重试）。 */
    fun isTransientHttpFailure(code: Int): Boolean = code == 408 || code == 429 || code >= 500
}

@HiltWorker
class ImagesDownloaderWorker
@AssistedInject
constructor(
    @Assisted private val appContext: Context,
    @Assisted private val params: WorkerParameters,
    private val repository: JellyfinRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val rawItemId = params.inputData.getString(KEY_ITEM_ID)
        val itemId = rawItemId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (rawItemId.isNullOrBlank() || itemId == null) {
            Timber.w("图片缓存跳过：缺少 / 非法条目 id")
            return Result.success()
        }
        val hadTransientFailure =
            try {
                downloadImages(itemId = itemId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.w(error, "图片缓存执行异常（%s）", itemId)
                true
            }
        if (ImagesDownloadRetryRules.shouldRetry(hadTransientFailure, runAttemptCount)) {
            Timber.i("图片缓存失败，交由 WorkManager 退避重试（第 %d 次尝试）：%s", runAttemptCount + 1, itemId)
            return Result.retry()
        }
        return Result.success()
    }

    /** 返回 true 表示存在网络类瞬时失败（可重试）；永久失败 / 无图返回 false。 */
    private suspend fun downloadImages(itemId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            var hadTransientFailure = false
            val item = repository.getItem(itemId) ?: return@withContext hadTransientFailure

            val basePath = "images/${item.id}"

            val baseDir = File(appContext.filesDir, basePath)

            val client = OkHttpClient()
            val uris = mapOf("primary" to item.images.primary, "backdrop" to item.images.backdrop)

            try {
                baseDir.mkdirs()
            } catch (e: IOException) {
                Timber.e(e)
                return@withContext true
            }

            for ((name, uri) in uris) {
                if (uri == null) {
                    continue
                }

                // W37 遗留修复（W36 §12）：按文件补拉——旧实现"目录存在即跳过"，首次拉图部分失败后不再补。
                val target = File(appContext.filesDir, "$basePath/$name")
                if (target.isFile && target.length() > 0L) continue

                val request =
                    try {
                        Request.Builder().url(uri.toString()).build()
                    } catch (e: IllegalArgumentException) {
                        // W57：单个非法地址（如本地合成 URI）不再让整个 worker 崩溃，其余图片继续。
                        Timber.e(e, "忽略非法图片地址（%s）", name)
                        continue
                    }

                val imageBytes =
                    try {
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                if (
                                    ImagesDownloadRetryRules.isTransientHttpFailure(response.code)
                                ) {
                                    hadTransientFailure = true
                                }
                                Timber.e("Failed to download image: ${response.code}")
                                continue
                            }

                            response.body.bytes()
                        }
                    } catch (e: IOException) {
                        Timber.e(e)
                        hadTransientFailure = true
                        continue
                    }

                try {
                    // 先写临时文件再改名：写失败留下的残片不会以「已缓存」身份被下一次尝试跳过。
                    val temp = File(appContext.filesDir, "$basePath/$name.part")
                    temp.writeBytes(imageBytes)
                    if (!temp.renameTo(target)) {
                        temp.delete()
                        hadTransientFailure = true
                    }
                } catch (e: IOException) {
                    Timber.e(e)
                    hadTransientFailure = true
                }
            }
            return@withContext hadTransientFailure
        }

    companion object {
        const val KEY_ITEM_ID = "KEY_ITEM_ID"
    }
}
