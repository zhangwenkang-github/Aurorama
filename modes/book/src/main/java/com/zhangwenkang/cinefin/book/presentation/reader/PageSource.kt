package com.zhangwenkang.cinefin.book.presentation.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 页序列数据源：EB-3（PDF）与 EB-4（CBZ）共用的门面。
 *
 * 约定：所有实现都**按页**渲染 / 解码，调用方用 [PageImageCache] 把窗口钉在 3 张位图； 单页失败返回 null（阅读页显示占位 + 重试），不抛出、不崩溃。
 */
interface PageSource : AutoCloseable {
    val pageCount: Int

    /** 页面自然尺寸（宽 × 高）：PDF 为页面点尺寸，CBZ 为位图像素尺寸；取不到返回 null。 */
    suspend fun pageSizePx(index: Int): Pair<Int, Int>?

    /** 页面宽高比（宽 / 高），用于滚动模式的占位排版；取不到返回 null。 */
    suspend fun pageAspectRatio(index: Int): Float? =
        pageSizePx(index)?.let { (width, height) ->
            if (width > 0 && height > 0) width.toFloat() / height.toFloat() else null
        }

    /** 渲染 / 解码一页，位图长边不超过 [maxSidePx]。 */
    suspend fun renderPage(index: Int, maxSidePx: Int): Bitmap?
}

/** PDF：`PdfRenderer.openPage(index)` + `Page.close()`，严格按页渲染回收（EB-3）。 */
class PdfPageSource(file: File) : PageSource {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer =
        try {
            PdfRenderer(descriptor)
        } catch (error: Throwable) {
            descriptor.close()
            throw error
        }

    /** PdfRenderer 非线程安全：所有页面操作串行化。 */
    private val mutex = Mutex()

    override val pageCount: Int
        get() = renderer.pageCount

    override suspend fun pageSizePx(index: Int): Pair<Int, Int>? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching { renderer.openPage(index).use { page -> page.width to page.height } }
                    .getOrNull()
            }
        }

    override suspend fun renderPage(index: Int, maxSidePx: Int): Bitmap? =
        withContext(Dispatchers.IO) { mutex.withLock { renderPageLocked(index, maxSidePx) } }

    private fun renderPageLocked(index: Int, maxSidePx: Int): Bitmap? = runCatching {
        renderer.openPage(index).use { page ->
            val scale = pdfRenderScale(page.width, page.height, maxSidePx)
            val width = max(1, (page.width * scale).roundToInt())
            val height = max(1, (page.height * scale).roundToInt())
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // PDF 页面默认白底：先铺白再渲染，避免透明通道在深色主题下泛黑。
            bitmap.eraseColor(Color.WHITE)
            page.render(
                bitmap,
                null,
                Matrix().apply { setScale(scale, scale) },
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
            )
            bitmap
        }
    }
        .getOrNull()

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}

/**
 * CBZ：`ZipFile` 随机读条目（D6 既定方案；`ZipInputStream` 顺序流仅作兜底）， `BitmapFactory` + `inSampleSize` 降采样解码。
 */
class ComicPageSource(file: File) : PageSource {
    private val zip = ZipFile(file)
    private val pages: List<ZipEntry> = run {
        val byName = LinkedHashMap<String, ZipEntry>()
        zip.entries().asSequence().forEach { byName[it.name] = it }
        orderComicPageNames(byName.keys.toList()).mapNotNull { byName[it] }
    }

    /** ZipFile.getInputStream 可并发，但解码串行化以减少峰值内存与 CPU 抖动。 */
    private val mutex = Mutex()

    override val pageCount: Int
        get() = pages.size

    override suspend fun pageSizePx(index: Int): Pair<Int, Int>? =
        withContext(Dispatchers.IO) { mutex.withLock { readBounds(index) } }

    override suspend fun renderPage(index: Int, maxSidePx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val bounds = readBounds(index) ?: return@withLock null
                val options =
                    BitmapFactory.Options().apply {
                        inSampleSize = bitmapSampleSize(bounds.first, bounds.second, maxSidePx)
                        // 漫画页是位图，RGB_565 把窗口内存直接减半，肉眼差异极小。
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                runCatching {
                    zip.getInputStream(entry(index)).use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                }
                    .getOrNull()
            }
        }

    private fun entry(index: Int): ZipEntry = pages[index]

    /** 只读尺寸（不解码像素）——用于滚动模式占位与降采样系数。 */
    private fun readBounds(index: Int): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(entry(index)).use { BitmapFactory.decodeStream(it, null, options) }
        options.outWidth to options.outHeight
    }
        .getOrNull()
        ?.takeIf { it.first > 0 && it.second > 0 }

    override fun close() {
        runCatching { zip.close() }
    }
}

/**
 * 页面位图 LRU 缓存：同时最多 [windowSize] 张（默认当前页 ± 1，ARCHITECTURE §3.4）。
 *
 * 超出窗口的位图立即失去引用（GC 回收）；渲染失败返回 null，由 UI 显示占位 + 重试。
 */
internal class PageImageCache(
    private val source: PageSource,
    private val maxSidePx: Int,
    windowSize: Int = PAGE_BITMAP_WINDOW,
) {
    private val cache = LruCache<Int, Bitmap>(windowSize)
    private val aspects = mutableMapOf<Int, Float?>()
    private val mutex = Mutex()

    suspend fun image(index: Int): Bitmap? {
        if (index < 0 || index >= source.pageCount) return null
        cache.get(index)?.let {
            return it
        }
        return mutex.withLock {
            cache.get(index)
                ?: runCatching { source.renderPage(index, maxSidePx) }
                    .getOrNull()
                    ?.also { cache.put(index, it) }
        }
    }

    /** 预取邻页（不阻塞调用方，调用点自行决定时机）。 */
    suspend fun prefetch(index: Int) {
        if (index < 0 || index >= source.pageCount) return
        if (cache.get(index) == null) image(index)
    }

    /** 页面宽高比（带缓存）；取不到返回 null。 */
    suspend fun aspect(index: Int): Float? {
        if (index < 0 || index >= source.pageCount) return null
        aspects[index]?.let {
            return it
        }
        return mutex.withLock {
            if (aspects.containsKey(index)) {
                aspects[index]
            } else {
                runCatching { source.pageAspectRatio(index) }
                    .getOrNull()
                    ?.also { aspects[index] = it }
            }
        }
    }
}
