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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

@HiltWorker
class ImagesDownloaderWorker
@AssistedInject
constructor(
    @Assisted private val appContext: Context,
    @Assisted private val params: WorkerParameters,
    private val repository: JellyfinRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val itemId = UUID.fromString(params.inputData.getString(KEY_ITEM_ID))
        downloadImages(itemId = itemId)
        return Result.success()
    }

    private suspend fun downloadImages(itemId: UUID) {
        withContext(Dispatchers.IO) {
            val item = repository.getItem(itemId) ?: return@withContext

            val basePath = "images/${item.id}"

            val baseDir = File(appContext.filesDir, basePath)

            val client = OkHttpClient()
            val uris = mapOf("primary" to item.images.primary, "backdrop" to item.images.backdrop)

            try {
                baseDir.mkdirs()
            } catch (e: IOException) {
                Timber.e(e)
                return@withContext
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
                                Timber.e("Failed to download image: ${response.code}")
                                continue
                            }

                            response.body.bytes()
                        }
                    } catch (e: IOException) {
                        Timber.e(e)
                        continue
                    }

                try {
                    target.writeBytes(imageBytes)
                } catch (e: IOException) {
                    Timber.e(e)
                }
            }
        }
    }

    companion object {
        const val KEY_ITEM_ID = "KEY_ITEM_ID"
    }
}
