package com.zhangwenkang.cinefin.book.presentation.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.zhangwenkang.cinefin.utils.RemoteComicArchive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * W77-2：远端 CBZ 页源（未下载书籍先出页）。
 *
 * 与本地 [ComicPageSource] 行为对齐：同样是「页序列表 + 按页 `BitmapFactory` 降采样解码」，但条目字节来自 [RemoteComicArchive]（core
 * `HttpByteSource` + `ZipArchiveReader`，按 Range 只取中央目录 + 目标条目）。
 *
 * 与本地路径的三处差异：
 * 1. **跳过逐页版式扫描**：远端逐页拉条目做双栏探测会打爆网络（W77-R1 spike §2.3），故 [perPageAspectScanMaxPages] =
 *    0，双栏按「无横版信息」处理（全 null = 竖版两页一屏）；
 * 2. **页级重试**：单页读取失败重试 [PAGE_READ_ATTEMPTS] 次（网络抖动 / 服务器偶发超时），仍失败返回 null 由 UI 占位；
 * 3. **可取消**：条目读取经 [RemoteComicArchive] 的在途请求登记，离开阅读页时可确定性中止。
 *
 * **按需预取**（用户 2026-10-07 口径：远端只按需取页、不自动整本下载）：每渲染一页后，在 [prefetchScope] 里把**后面 [prefetchAhead]
 * 页**的条目字节顺序拉进传输层分块缓存（不解码、不整本下载），翻页时不再等网络；预取让位给可见页（拿不到页锁即跳过，下次翻页再补）， 并随 [prefetchScope] 一起取消。
 *
 * @param prefetchScope 预取协程作用域（阅读页 `viewModelScope`）；null 时不做预取。
 */
class RemoteComicPageSource(
    private val archive: RemoteComicArchive,
    pageNames: List<String>,
    private val prefetchScope: CoroutineScope? = null,
    private val prefetchAhead: Int = PREFETCH_AHEAD_PAGES,
) : PageSource {

    private val names: List<String> = pageNames.toList()
    private val mutex = Mutex()
    private var lastPrefetchFrom = NO_PREFETCH

    override val pageCount: Int
        get() = names.size

    /** 远端跳过逐页版式扫描（见类注释）。 */
    override val perPageAspectScanMaxPages: Int = 0

    override suspend fun pageSizePx(index: Int): Pair<Int, Int>? =
        withContext(Dispatchers.IO) {
            mutex.withLock { withPageRetry(index) { name -> decodeBounds(readEntryBytes(name)) } }
        }

    override suspend fun renderPage(index: Int, maxSidePx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val bitmap =
                    withPageRetry(index) { name ->
                        val bytes = readEntryBytes(name)
                        val bounds = decodeBounds(bytes) ?: return@withPageRetry null
                        val options =
                            BitmapFactory.Options().apply {
                                inSampleSize =
                                    bitmapSampleSize(bounds.first, bounds.second, maxSidePx)
                                // 漫画页是位图，RGB_565 把窗口内存直接减半，肉眼差异极小（与本地 ComicPageSource 同口径）。
                                inPreferredConfig = Bitmap.Config.RGB_565
                            }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    }
                if (bitmap != null) schedulePrefetch(index)
                bitmap
            }
        }

    override fun close() {
        runCatching { archive.close() }
    }

    private fun readEntryBytes(name: String): ByteArray {
        val bytes = archive.readBytes(name, PAGE_MAX_BYTES)
        Timber.d("W77 远端 CBZ 条目读取 %s（%d B）", name, bytes.size)
        return bytes
    }

    /**
     * 调度「后 [prefetchAhead] 页」的后台预取（只读条目字节，进传输层分块缓存）。
     *
     * 同一个 [from] 不重复触发；预取在 [prefetchScope] 里顺序执行，且让位给可见页（拿不到页锁即跳过）。
     */
    private fun schedulePrefetch(from: Int) {
        val scope = prefetchScope ?: return
        if (from == lastPrefetchFrom) return
        val targets = ((from + 1)..(from + prefetchAhead)).filter { it in names.indices }
        if (targets.isEmpty()) return
        lastPrefetchFrom = from
        scope.launch(Dispatchers.IO) {
            delay(PREFETCH_START_DELAY_MS)
            for (index in targets) {
                coroutineContext.ensureActive()
                // 让位给可见页：拿不到锁说明正在渲染别的页，本次跳过（翻页后会再补）。
                if (!mutex.tryLock()) return@launch
                try {
                    runCatching { readEntryBytes(names[index]) }
                        .onFailure { Timber.w(it, "W77 远端 CBZ 预取失败：%s", names[index]) }
                } finally {
                    mutex.unlock()
                }
                delay(PREFETCH_GAP_MS)
            }
        }
    }

    /** 只读尺寸（不解码像素）——用于滚动模式占位与降采样系数。 */
    private fun decodeBounds(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return (options.outWidth to options.outHeight).takeIf { it.first > 0 && it.second > 0 }
    }

    /**
     * 单页读取重试：网络抖动 / 服务器偶发超时时退避重试；协程取消立即向上抛（不再发起新请求）。
     *
     * 返回 null 表示重试耗尽，调用方按「单页失败」处理（UI 占位 + 重试）。
     */
    private suspend fun <T> withPageRetry(index: Int, block: suspend (String) -> T?): T? {
        val name = names.getOrNull(index) ?: return null
        var attempt = 1
        while (true) {
            coroutineContext.ensureActive()
            val result =
                try {
                    block(name)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    // 离开阅读页 / 切换书籍时阻塞读以 IOException 形式返回：还原取消语义，不当成「页读取失败」。
                    coroutineContext.ensureActive()
                    Timber.w(error, "W77 远端 CBZ 页读取失败（第 %d 次）：%s", attempt, name)
                    null
                }
            if (result != null) return result
            if (attempt >= PAGE_READ_ATTEMPTS) return null
            attempt++
            delay(PAGE_RETRY_DELAY_MS)
        }
    }

    private companion object {
        /** 单页条目体积上限：漫画页常见 1–5 MB、扫描页可到 20 MB+（spike §2.3），留足余量。 */
        const val PAGE_MAX_BYTES = 48L * 1024 * 1024

        /** 单页读取尝试次数（含首次）。 */
        const val PAGE_READ_ATTEMPTS = 2

        /** 重试退避（真机单请求 1.4–20 s，短退避即可，避免把失败页拖成十几秒）。 */
        const val PAGE_RETRY_DELAY_MS = 500L

        /** 预取窗口：当前页之后 [PREFETCH_AHEAD_PAGES] 页（用户建议 3–5 页；取 3 以控首开流量）。 */
        const val PREFETCH_AHEAD_PAGES = 3

        /** 预取起始延迟：先让可见页的首帧落地，再开始抢带宽。 */
        const val PREFETCH_START_DELAY_MS = 250L

        /** 预取相邻页之间的间隔：给用户翻页留窗口，避免长时间占住传输层锁。 */
        const val PREFETCH_GAP_MS = 150L

        const val NO_PREFETCH = -1
    }
}

/**
 * 从远端归档筛出漫画页并按阅读顺序排序（与本地 CBZ 共用 [orderComicPageNames] 同一份页序口径）。
 *
 * 体积超上限 / 体积为 0 的条目直接排除，避免整页必失败。
 */
internal fun remoteComicPageNames(
    archive: RemoteComicArchive,
    maxPageBytes: Long = 48L * 1024 * 1024,
): List<String> =
    orderComicPageNames(archive.entries.map { it.name }).filter { name ->
        val size = archive.size(name) ?: return@filter false
        size in 1..maxPageBytes
    }
