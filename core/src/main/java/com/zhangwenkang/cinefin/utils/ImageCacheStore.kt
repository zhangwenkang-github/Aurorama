package com.zhangwenkang.cinefin.utils

import android.content.Context
import com.zhangwenkang.cinefin.work.ImagesDownloadRetryRules
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * W66：本地图片缓存的读写与原子替换（`files/images/<id>/<name>` + `<name>.meta`）。
 *
 * 三处共用同一缓存路径与规则：`ImagesDownloaderWorker`（入队即落图 / 重试）、下载页缺图后台补齐 （并发上限 4）、后续任何需要保证图片新鲜的路径。写盘顺序 = 图片
 * `.part` + rename → 元数据 `.part` + rename，中途失败不会留下「半张图 + 新元数据」。
 */
@Singleton
class ImageCacheStore @Inject constructor(@ApplicationContext private val context: Context) {

    /** 一次拉取的结果：[changed] = 图片被替换（UI 需要刷新）；[transientFailure] = 网络类瞬时失败（可重试）。 */
    data class FetchOutcome(val changed: Boolean, val transientFailure: Boolean)

    /** 图片专用客户端（与 `ImagesDownloaderWorker` 旧实现一致：不挂业务拦截器，纯图片请求）。 */
    private val client: OkHttpClient by lazy { OkHttpClient() }

    fun imageFile(itemId: UUID, name: String = PRIMARY): File =
        File(context.filesDir, "images/$itemId/$name")

    fun metaFile(itemId: UUID, name: String = PRIMARY): File =
        File(context.filesDir, "images/$itemId/$name.meta")

    fun readMeta(itemId: UUID, name: String = PRIMARY): ImageCacheRules.Meta? {
        val file = metaFile(itemId, name)
        if (!file.isFile) return null
        return runCatching { ImageCacheRules.decodeMeta(file.readText()) }.getOrNull()
    }

    /** 本地判定（不联网）：[currentSourceUrl] 传 null 表示本次不比对 URL。 */
    fun decide(
        itemId: UUID,
        currentSourceUrl: String?,
        name: String = PRIMARY,
        nowMillis: Long = System.currentTimeMillis(),
    ): ImageCacheRules.Decision {
        val file = imageFile(itemId, name)
        val exists = file.isFile
        return ImageCacheRules.decide(
            fileExists = exists,
            fileSizeBytes = if (exists) file.length() else 0L,
            meta = readMeta(itemId, name),
            currentSourceUrl = currentSourceUrl,
            nowMillis = nowMillis,
        )
    }

    /**
     * 拉取并原子替换（条件请求：304 只刷新 `fetchedAt`，不算图片变化）。
     *
     * 调用方先跑 [decide] 决定是否需要；本方法也容忍「不需刷新」时被调用（仍会发条件请求）。
     */
    suspend fun fetch(
        itemId: UUID,
        url: String,
        name: String = PRIMARY,
        nowMillis: Long = System.currentTimeMillis(),
    ): FetchOutcome =
        withContext(Dispatchers.IO) {
            val target = imageFile(itemId, name)
            val meta = readMeta(itemId, name)
            val builder = Request.Builder().url(url)
            if (meta != null && meta.sourceUrl == url) {
                meta.etag?.takeIf { it.isNotBlank() }?.let { builder.header("If-None-Match", it) }
                meta.lastModified
                    ?.takeIf { it.isNotBlank() }
                    ?.let { builder.header("If-Modified-Since", it) }
            }

            val response =
                try {
                    client.newCall(builder.build()).execute()
                } catch (error: IOException) {
                    Timber.w(error, "图片缓存拉取失败（%s / %s）", itemId, name)
                    return@withContext FetchOutcome(changed = false, transientFailure = true)
                }

            response.use { result ->
                when {
                    result.code == 304 -> {
                        // 服务端确认未变化：只把 fetchedAt 前移（元数据原子写），图片不动。
                        val latest = meta ?: return@use FetchOutcome(false, false)
                        writeMeta(itemId, name, latest.copy(fetchedAt = nowMillis))
                        FetchOutcome(changed = false, transientFailure = false)
                    }
                    result.isSuccessful -> {
                        val bytes = result.body.bytes()
                        if (bytes.isEmpty()) {
                            FetchOutcome(changed = false, transientFailure = false)
                        } else {
                            val replaced = replaceImage(target, bytes, name)
                            if (replaced) {
                                writeMeta(
                                    itemId,
                                    name,
                                    ImageCacheRules.Meta(
                                        sourceUrl = url,
                                        etag = result.header("ETag"),
                                        lastModified = result.header("Last-Modified"),
                                        fetchedAt = nowMillis,
                                    ),
                                )
                            }
                            FetchOutcome(changed = replaced, transientFailure = !replaced)
                        }
                    }
                    else ->
                        FetchOutcome(
                            changed = false,
                            transientFailure =
                                ImagesDownloadRetryRules.isTransientHttpFailure(result.code),
                        )
                }
            }
        }

    /** `.part` + rename 原子替换；失败时清掉残片并报告。 */
    private fun replaceImage(target: File, bytes: ByteArray, name: String): Boolean =
        try {
            val dir = target.parentFile
            if (dir == null || (!dir.isDirectory && !dir.mkdirs())) return false
            val temp = File(dir, "$name.part")
            temp.writeBytes(bytes)
            if (temp.renameTo(target)) {
                true
            } else {
                temp.delete()
                false
            }
        } catch (error: IOException) {
            Timber.w(error, "图片缓存写入失败（%s）", target.absolutePath)
            false
        }

    /** 元数据 `.part` + rename 原子写。 */
    private fun writeMeta(itemId: UUID, name: String, meta: ImageCacheRules.Meta) {
        runCatching {
            val target = metaFile(itemId, name)
            val dir = target.parentFile ?: return
            if (!dir.isDirectory && !dir.mkdirs()) return
            val temp = File(dir, "$name.meta.part")
            temp.writeText(ImageCacheRules.encodeMeta(meta))
            if (!temp.renameTo(target)) {
                temp.delete()
            }
        }
            .onFailure { Timber.w(it, "图片缓存元数据写入失败（%s / %s）", itemId, name) }
    }

    companion object {
        const val PRIMARY: String = "primary"
        const val BACKDROP: String = "backdrop"
    }
}
