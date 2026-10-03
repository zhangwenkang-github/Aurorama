package com.zhangwenkang.cinefin.presentation.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.zhangwenkang.cinefin.book.presentation.reader.LocalEpubCover
import com.zhangwenkang.cinefin.local.LocalLibraryEntry
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.local.LocalMediaExtensions
import com.zhangwenkang.cinefin.local.LocalMediaKind
import com.zhangwenkang.cinefin.local.LocalThumbnailRules
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * W45：本地媒体封面 / 缩略图（懒生成 + 磁盘缓存 + 失败标记）。
 *
 * |类型 |来源                                                                                            |回退              |
 * |---|----------------------------------------------------------------------------------------------|----------------|
 * |视频 |`MediaMetadataRetriever` 候选帧 1s / 10% / 30%（跳过近黑帧；`getScaledFrameAtTime` 失败退 `getFrameAtTime`）|全黑退第 1 秒帧 → 类型图标|
 * |书籍 |PDF 首页（`PdfRenderer`）/ CBZ **自然序**第一张图（`ZipInputStream` 两遍顺序流）/ EPUB（Readium metadata cover）  |类型图标            |
 * |音乐 |沿用 W37 链路：内嵌标签封面 → 同目录封面（`entry.coverUri`），不重做                                                |类型图标            |
 *
 * 纪律：
 * - 只在调用方（卡片 / 列表行 / 详情头部可见时）按需生成；生成前先查 `files/local_thumbs/<itemId>.jpg`；
 * - 并发 ≤ [LocalThumbnailRules.MAX_CONCURRENT]，全部在 `Dispatchers.IO`；
 * - 失败写 `files/local_thumbs/<itemId>.fail` 并在内存标记，不再反复重试；
 * - 返回给 UI 的是可直接喂给 Coil 的字符串（本地文件绝对路径；音乐为既有 `coverUri`）。
 */
@Singleton
class LocalThumbnailProvider
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val repository: LocalLibraryRepository,
) {

    private val semaphore = Semaphore(LocalThumbnailRules.MAX_CONCURRENT)
    private val failed = ConcurrentHashMap.newKeySet<UUID>()
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<UUID, CompletableDeferred<String?>>()

    /** 单条目缩略图（列表行 / 详情头部）：先查索引拿条目，再走 [thumbnail]。 */
    suspend fun thumbnail(itemId: UUID): String? = runCatching {
        repository.entry(itemId)
    }
        .getOrNull()
        ?.let { thumbnail(it) }

    /** 单条目缩略图：音乐走既有封面链路；其余查缓存 → 生成 → 失败标记。 */
    suspend fun thumbnail(entry: LocalLibraryEntry): String? {
        if (entry.kind == LocalMediaKind.MUSIC) return entry.coverUri?.takeIf { it.isNotBlank() }
        cachedThumb(entry)?.let {
            return it
        }
        if (isMarkedFailed(entry.itemId)) return null
        return generateOnce(entry)
    }

    /**
     * 首页 / 媒体库库卡封面：`LocalThumbnailRules.coverCandidates` 顺序（视频 → 书籍 → 音乐）里 「第一个已有封面」优先；都没有时最多现场生成
     * [LocalThumbnailRules.MAX_COVER_ATTEMPTS] 张，全失败返回 null。
     */
    suspend fun libraryCover(libraryId: Long): String? {
        val entries = runCatching { repository.entries(libraryId) }.getOrDefault(emptyList())
        val candidates = LocalThumbnailRules.coverCandidates(entries)
        if (candidates.isEmpty()) return null
        val readyIds =
            candidates
                .filter { LocalThumbnailRules.hasCover(context.filesDir, it) }
                .mapTo(mutableSetOf()) { it.itemId }
        val plan = LocalThumbnailRules.planCover(candidates, readyIds)
        plan.ready?.let { ready ->
            return resolveReady(ready)
        }
        for (candidate in plan.attempts) {
            val path = thumbnail(candidate)
            if (path != null) return path
        }
        return null
    }

    // ------------------------------------------------------------ 缓存命中

    private fun cachedThumb(entry: LocalLibraryEntry): String? =
        LocalThumbnailRules.cacheFile(context.filesDir, entry.itemId)
            .takeIf { it.isFile && it.length() > 0 }
            ?.absolutePath

    private fun resolveReady(entry: LocalLibraryEntry): String? =
        if (entry.kind == LocalMediaKind.MUSIC) entry.coverUri?.takeIf { it.isNotBlank() }
        else cachedThumb(entry)

    private fun isMarkedFailed(itemId: UUID): Boolean {
        if (itemId in failed) return true
        val marked = LocalThumbnailRules.failureMarker(context.filesDir, itemId).isFile
        if (marked) failed += itemId
        return marked
    }

    // ------------------------------------------------------------ 生成

    /** 同一条目的并发请求合并为一次生成；`Semaphore` 限制全局并发。 */
    private suspend fun generateOnce(entry: LocalLibraryEntry): String? {
        val (deferred, owner) =
            mutex.withLock {
                inFlight[entry.itemId]?.let { it to false }
                    ?: CompletableDeferred<String?>().also { inFlight[entry.itemId] = it } to true
            }
        if (!owner) return deferred.await()
        return try {
            val result = withContext(Dispatchers.IO) { semaphore.withPermit { generate(entry) } }
            deferred.complete(result)
            result
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
            throw error
        } finally {
            mutex.withLock { inFlight.remove(entry.itemId) }
        }
    }

    private suspend fun generate(entry: LocalLibraryEntry): String? {
        val bitmap =
            try {
                extract(entry)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Timber.w(error, "W45 缩略图生成失败：${entry.name}")
                null
            }
        if (bitmap == null) {
            markFailed(entry.itemId)
            return null
        }
        val written = writeThumb(entry.itemId, bitmap)
        if (written == null) markFailed(entry.itemId)
        return written
    }

    private fun markFailed(itemId: UUID) {
        failed += itemId
        runCatching {
            val marker = LocalThumbnailRules.failureMarker(context.filesDir, itemId)
            marker.parentFile?.mkdirs()
            marker.writeBytes(ByteArray(0))
        }
    }

    // ------------------------------------------------------------ 各类型抽取

    private suspend fun extract(entry: LocalLibraryEntry): Bitmap? =
        when (entry.kind) {
            LocalMediaKind.VIDEO -> videoCover(entry.documentUri)
            LocalMediaKind.BOOK ->
                when (LocalMediaExtensions.extensionOf(entry.name)) {
                    "pdf" -> pdfCover(entry.documentUri)
                    "cbz" -> cbzCover(entry.documentUri)
                    "epub" -> LocalEpubCover.load(context, entry.documentUri)
                    else -> null
                }
            LocalMediaKind.MUSIC -> null
        }

    /**
     * 视频：候选帧 1s → 10% → 30% → 0s，跳过近黑帧（W49，W45 遗留「黑场片源取到黑帧」）。
     *
     * 全部候选都是黑帧时回退第一张成功取到的帧（正常情况下即第 1 秒），取不到任何帧才回退类型图标。
     */
    private fun videoCover(documentUri: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(documentUri))
            val width =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
            val height =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
            val rotation =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
            val durationMs =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
            val target = LocalThumbnailRules.videoTargetSize(width, height, rotation)
            var fallback: Bitmap? = null
            for (timeUs in LocalThumbnailRules.videoFrameTimesUs(durationMs)) {
                val frame = frameAt(retriever, timeUs, target) ?: continue
                if (isNearlyBlackFrame(frame)) {
                    if (fallback == null) fallback = frame else frame.recycle()
                    continue
                }
                fallback?.recycle()
                return frame
            }
            fallback
        } catch (error: Throwable) {
            Timber.w(error, "W45 视频首帧失败：$documentUri")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 单个候选时间点取帧：优先按目标尺寸缩放（旋转已折算），失败退整帧。 */
    private fun frameAt(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        target: Pair<Int, Int>?,
    ): Bitmap? {
        val scaled = target?.let { size ->
            runCatching {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    size.first,
                    size.second,
                )
            }
                .getOrNull()
        }
        if (scaled != null) return scaled
        return runCatching {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
            .getOrNull()
    }

    /** 近黑帧判定：缩到 32×32 后取像素求平均相对亮度；测量失败按「不黑」处理（照常显示该帧）。 */
    private fun isNearlyBlackFrame(frame: Bitmap): Boolean = runCatching {
        val side = LocalThumbnailRules.VIDEO_LUMA_SAMPLE_SIDE_PX
        val sample = Bitmap.createScaledBitmap(frame, side, side, true)
        try {
            val pixels = IntArray(side * side)
            sample.getPixels(pixels, 0, side, 0, 0, side, side)
            LocalThumbnailRules.isNearlyBlack(pixels)
        } finally {
            if (sample !== frame) sample.recycle()
        }
    }
        .getOrDefault(false)

    /** 书籍：PDF 首页（白底 + 降采样渲染）。 */
    private fun pdfCover(documentUri: String): Bitmap? {
        val descriptor =
            runCatching { context.contentResolver.openFileDescriptor(Uri.parse(documentUri), "r") }
                .getOrNull() ?: return null
        return try {
            val renderer = runCatching { PdfRenderer(descriptor) }.getOrNull() ?: return null
            try {
                if (renderer.pageCount <= 0) return null
                renderer.openPage(0).use { page ->
                    val size = LocalThumbnailRules.thumbSize(page.width, page.height) ?: return null
                    val bitmap =
                        Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
                    // PDF 页面默认白底：先铺白再渲染，避免透明通道在深色主题下泛黑。
                    bitmap.eraseColor(Color.WHITE)
                    page.render(
                        bitmap,
                        null,
                        Matrix().apply {
                            setScale(
                                size.first.toFloat() / page.width.toFloat(),
                                size.second.toFloat() / page.height.toFloat(),
                            )
                        },
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                    )
                    bitmap
                }
            } finally {
                runCatching { renderer.close() }
            }
        } catch (error: Throwable) {
            Timber.w(error, "W45 PDF 首页失败：$documentUri")
            null
        } finally {
            runCatching { descriptor.close() }
        }
    }

    /**
     * 书籍：CBZ **自然序**第一张页图（W49，W45 遗留「归档顺序第一张图」）。
     *
     * SAF `content://` 拿不到随机访问的 `ZipFile`，只能顺序流：第一遍列页名求自然序首图， 第二遍流到该条目再解码（目录 / 隐藏文件 / `__MACOSX` /
     * 超大图仍由 [LocalThumbnailRules.isComicPageImage] 过滤）。
     */
    private fun cbzCover(documentUri: String): Bitmap? {
        val firstName = cbzFirstPageName(documentUri) ?: return null
        val stream = openContentStream(documentUri) ?: return null
        stream.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name == firstName) {
                        if (entry.size > MAX_COMIC_PAGE_BYTES) return null
                        val bytes = runCatching { zip.readBytes() }.getOrNull() ?: return null
                        return decodeThumb(bytes)
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return null
    }

    /** 第一遍：只列页名（不解码），返回自然序第一张页图的条目名。 */
    private fun cbzFirstPageName(documentUri: String): String? {
        val stream = openContentStream(documentUri) ?: return null
        val names = mutableListOf<String>()
        stream.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name ?: ""
                    if (
                        LocalThumbnailRules.isComicPageImage(name, entry.isDirectory) &&
                            entry.size <= MAX_COMIC_PAGE_BYTES
                    ) {
                        names += name
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return LocalThumbnailRules.sortedComicPageNames(names).firstOrNull()
    }

    private fun openContentStream(documentUri: String): java.io.InputStream? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(documentUri))
    }
        .getOrNull()

    // ------------------------------------------------------------ 位图 → 磁盘

    private fun decodeThumb(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val size = LocalThumbnailRules.thumbSize(bounds.outWidth, bounds.outHeight) ?: return null
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= size.first &&
                bounds.outHeight / (sample * 2) >= size.second
        ) {
            sample *= 2
        }
        val decoded =
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
        return scaleToThumb(decoded)
    }

    /** 最长边收到 [LocalThumbnailRules.MAX_SIDE_PX]（已在范围内的位图原样返回）。 */
    private fun scaleToThumb(bitmap: Bitmap): Bitmap {
        val size = LocalThumbnailRules.thumbSize(bitmap.width, bitmap.height) ?: return bitmap
        if (size.first == bitmap.width && size.second == bitmap.height) return bitmap
        return runCatching { Bitmap.createScaledBitmap(bitmap, size.first, size.second, true) }
            .getOrDefault(bitmap)
    }

    /** 写 `files/local_thumbs/<itemId>.jpg`（先写 `.tmp` 再改名，避免半截文件被当命中）。 */
    private fun writeThumb(itemId: UUID, bitmap: Bitmap): String? = runCatching {
        val dir = LocalThumbnailRules.cacheDir(context.filesDir).apply { mkdirs() }
        val target = LocalThumbnailRules.cacheFile(context.filesDir, itemId)
        val temporary = File(dir, "${target.name}.tmp")
        val scaled = scaleToThumb(bitmap)
        val encoded =
            FileOutputStream(temporary).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, LocalThumbnailRules.JPEG_QUALITY, out)
            }
        if (!encoded || temporary.length() <= 0L) {
            temporary.delete()
            return@runCatching null
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) {
            temporary.delete()
            return@runCatching null
        }
        runCatching { LocalThumbnailRules.failureMarker(context.filesDir, itemId).delete() }
        target.absolutePath
    }
        .getOrNull()

    private companion object {
        /** CBZ 第一张图体积上限：超过即放弃（避免超大页图 OOM）。 */
        const val MAX_COMIC_PAGE_BYTES = 32L * 1024 * 1024
    }
}
