package com.zhangwenkang.cinefin.book.presentation.reader

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.RandomAccessRead
import com.tom_roush.pdfbox.io.ScratchFile
import com.tom_roush.pdfbox.pdfparser.PDFParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * PDF 版式元数据（页宽高比）数据源（W33 / W48）。
 *
 * 背景（READER_PLAN §7.13.4 / D-W47-1 / 踩坑 29）：W26 的双栏「横版整页独占」用 `PdfRenderer.openPage` 逐页读尺寸， 3649 页
 * PDF 上把 native heap 抬到 748 MB 且不回收（5006 页本地 SAF 样本 1.59 GB，进程被杀）。**只读页树元数据不需要 渲染、更不需要逐页
 * openPage**：PdfBox 读 `/CropBox`（缺省回退 `/MediaBox`）与继承的 `/Rotate` 即可得到与 PdfRenderer 同口径的显示尺寸。
 *
 * W33 只覆盖本地缓存文件路径（`PDDocument.load(file, …)`）；W48 补上本地媒体库的 SAF `content://` 路径： [forDescriptor] 用
 * `dup` 出的独立 fd 做随机读（[DescriptorRandomAccessRead]，`Os.pread` 定位读），再交给 [PDFParser] + [ScratchFile]（与
 * `PDDocument.load` 同路径；本 fork 没有 `load(RandomAccessRead)` 重载）。
 *
 * 内存策略与 [PdfBoxPageTextSource] 同源：`setupMixed(8 MB)` 主缓冲 + 溢出临时文件；页面对象读完即丢弃，
 * 常驻只有页树与缓冲。线程：`PDDocument` 非线程安全，读取用同一把 [Mutex] 串行化。
 */
internal class PdfLayoutSource(
    /** 惰性载入：只读页树、不渲染；失败抛出，由调用方回退。 */
    private val loader: (MemoryUsageSetting) -> PDDocument,
    private val memoryUsage: MemoryUsageSetting =
        MemoryUsageSetting.setupMixed(PDF_LAYOUT_MEMORY_BUFFER_BYTES),
    /** SAF fd 路径：文档尚未载入（或载入失败）时也要释放的随机读源；载入成功后由 `PDDocument.close()` 释放。 */
    private val pendingSource: RandomAccessRead? = null,
) {
    private val mutex = Mutex()
    private var document: PDDocument? = null
    private var failure: Throwable? = null
    private var closed = false
    private var cache: List<Float?>? = null

    /** 本地缓存文件（`{itemId}.book`）：`PDDocument.load(file, …)` 只读页树。 */
    constructor(file: File) : this(loader = { memory -> PDDocument.load(file, memory) })

    /** 随机读源（单测用 `RandomAccessBufferedFileInputStream(file)`；App 内只走 [forDescriptor]）。 */
    constructor(
        source: RandomAccessRead
    ) : this(
        loader = { memory -> loadPdfDocument(source, memory) },
        pendingSource = source,
    )

    /** 一次性读完全书页宽高比（与 [PageSource.pageAspectRatios] 同口径）；失败抛出，由调用方回退。 */
    suspend fun pageAspectRatios(): List<Float?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                cache?.let {
                    return@withLock it
                }
                buildPageAspectRatios(ensureDocument()).also { cache = it }
            }
        }

    fun close() {
        closed = true
        val doc = document
        document = null
        cache = null
        if (doc != null) {
            runCatching { doc.close() }
        } else {
            runCatching { pendingSource?.close() }
        }
    }

    private fun ensureDocument(): PDDocument {
        document?.let {
            return it
        }
        failure?.let { throw it }
        if (closed) throw IllegalStateException("PDF 版式会话已关闭")
        return try {
            loader(memoryUsage).also { document = it }
        } catch (error: Throwable) {
            failure = error
            throw error
        }
    }

    private fun buildPageAspectRatios(doc: PDDocument): List<Float?> {
        val count = doc.numberOfPages
        val pages = doc.pages
        return List(count) { index ->
            val page = runCatching { pages.get(index) }.getOrNull() ?: return@List null
            val crop = runCatching { page.cropBox }.getOrNull() ?: return@List null
            val rotation = runCatching { page.rotation }.getOrDefault(0)
            pdfAspectRatio(crop.width, crop.height, rotation)
        }
    }

    companion object {
        /**
         * SAF `content://`（W48）：`dup` 出独立 fd（原 fd 归 `PdfRenderer`）后按定位读；fd 已关、长度为 0 或定位读 探针失败（管道 /
         * 代理 fd）时返回 null，由 [PdfPageSource] 走兜底策略。
         */
        fun forDescriptor(descriptor: ParcelFileDescriptor): PdfLayoutSource? {
            val duplicate = runCatching { descriptor.dup() }.getOrNull() ?: return null
            val source = runCatching { DescriptorRandomAccessRead(duplicate) }.getOrNull()
            if (source == null) {
                runCatching { duplicate.close() }
                return null
            }
            return PdfLayoutSource(source)
        }
    }
}

/** 用 PdfBox `PDFParser` 载入随机读源（与 `PDDocument.load(file, …)` 同路径；失败时释放 source 与 scratch）。 */
private fun loadPdfDocument(source: RandomAccessRead, memoryUsage: MemoryUsageSetting): PDDocument {
    val scratchFile = ScratchFile(memoryUsage)
    return try {
        val parser = PDFParser(source, scratchFile)
        parser.parse()
        parser.getPDDocument()
    } catch (error: Throwable) {
        runCatching { scratchFile.close() }
        runCatching { source.close() }
        throw error
    }
}

/**
 * `ParcelFileDescriptor` → PdfBox [RandomAccessRead]（W48）。
 *
 * `Os.pread` 定位读（经 [PagedPositionalReader] 页缓存），不改变共享的文件偏移；`close()` 关闭 `dup` 出来的 fd（原 fd 归
 * `PdfRenderer`，互不影响）。只支持可 seek 的 fd：管道 / 代理 fd 的 `pread` 抛 `ESPIPE`，在构造期即判定不可用。
 */
private class DescriptorRandomAccessRead(private val descriptor: ParcelFileDescriptor) :
    RandomAccessRead {
    private val fd = descriptor.fileDescriptor
    private val closed = AtomicBoolean(false)
    private val length: Long
    private val paged: PagedPositionalReader
    private var position = 0L

    init {
        val fileLength = runCatching { Os.fstat(fd).st_size }.getOrElse { -1L }
        val probeBytes =
            if (fileLength > 0L) {
                val probe =
                    ByteArray(minOf(fileLength, DESCRIPTOR_LAYOUT_PROBE_BYTES.toLong()).toInt())
                runCatching { Os.pread(fd, probe, 0, probe.size, 0L) }.getOrDefault(-1)
            } else {
                -1
            }
        length = fileLength
        if (!descriptorLayoutReadable(fileLength, probeBytes)) {
            runCatching { descriptor.close() }
            throw IOException("SAF fd 不可定位读：length=$fileLength probe=$probeBytes")
        }
        paged =
            PagedPositionalReader(length = fileLength) { buffer, offset, count, at ->
                osPread(buffer, offset, count, at)
            }
    }

    override fun read(): Int {
        checkOpen()
        val value = paged.byteAt(position)
        if (value >= 0) position += 1
        return value
    }

    override fun read(buffer: ByteArray): Int = read(buffer, 0, buffer.size)

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        checkOpen()
        if (count == 0) return 0
        val read = paged.read(buffer, offset, count, position)
        if (read <= 0) return -1
        position += read
        return read
    }

    override fun getPosition(): Long {
        checkOpen()
        return position
    }

    override fun seek(position: Long) {
        checkOpen()
        if (position < 0L) throw IOException("非法位置：$position")
        this.position = position
    }

    override fun length(): Long {
        checkOpen()
        return length
    }

    override fun isClosed(): Boolean = closed.get()

    override fun peek(): Int {
        checkOpen()
        return paged.byteAt(position)
    }

    override fun rewind(bytes: Int) {
        checkOpen()
        seek((position - bytes).coerceAtLeast(0L))
    }

    override fun readFully(length: Int): ByteArray {
        checkOpen()
        if (length < 0) throw IOException("非法长度：$length")
        if (position + length > this.length) throw EOFException("已到文件末尾")
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(buffer, offset, length - offset)
            if (read <= 0) throw EOFException("已到文件末尾")
            offset += read
        }
        return buffer
    }

    override fun isEOF(): Boolean {
        checkOpen()
        return position >= length
    }

    override fun available(): Int {
        checkOpen()
        return (length - position).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { descriptor.close() }
        }
    }

    private fun osPread(buffer: ByteArray, offset: Int, count: Int, at: Long): Int =
        try {
            Os.pread(fd, buffer, offset, count, at)
        } catch (error: ErrnoException) {
            throw IOException("SAF fd 定位读失败", error)
        }

    private fun checkOpen() {
        if (closed.get()) throw IOException("随机读源已关闭")
    }
}

/** 定位读函数（纯 JVM 可测）：从 [position] 最多读 [count] 字节，返回实际字节数，0 表示 EOF。 */
internal fun interface PositionalReader {
    fun read(buffer: ByteArray, offset: Int, count: Int, position: Long): Int
}

/**
 * 带 4 KB 页缓存的定位读（W48）。
 *
 * SAF `content://` 的 fd 多为 FUSE / 代理 fd：**每次 `pread` 都可能带跨进程开销**。PdfBox 解析会在很小粒度上反复读 （逐字节 token
 * 与页树对象），若每次读都打一次系统调用，GB 级 PDF 上会出现「CPU 100% 数分钟不结束」（W48 真机实测）。 本类按页缓存（LRU）把小块读合并成整页读，与 PdfBox 自带
 * `RandomAccessBufferedFileInputStream` 的 4 KB 页口径一致。
 */
internal class PagedPositionalReader(
    private val length: Long,
    private val pageSize: Int = POSITIONAL_PAGE_SIZE_BYTES,
    maxCachedPages: Int = POSITIONAL_PAGE_CACHE_LIMIT,
    private val readAt: PositionalReader,
) {
    private val pageShift = Integer.numberOfTrailingZeros(pageSize)
    private val pageMask = (pageSize - 1).toLong()
    private val maxCachedPages = maxCachedPages
    private val cache =
        object : LinkedHashMap<Long, ByteArray>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Long, ByteArray>?
            ): Boolean = size > this@PagedPositionalReader.maxCachedPages
        }

    /** 读单字节；EOF 返回 -1。 */
    fun byteAt(position: Long): Int {
        if (position >= length) return -1
        val page = page(position shr pageShift)
        return page[(position and pageMask).toInt()].toInt() and 0xFF
    }

    /** 从 [position] 读至多 [count] 字节；返回实际字节数，0 表示 EOF。 */
    fun read(buffer: ByteArray, offset: Int, count: Int, position: Long): Int {
        if (count == 0 || position >= length) return 0
        var cursor = position
        var written = 0
        var remaining = minOf(count.toLong(), length - position).toInt()
        while (remaining > 0) {
            val page = page(cursor shr pageShift)
            val within = (cursor and pageMask).toInt()
            val chunk = minOf(remaining, page.size - within)
            if (chunk <= 0) throw IOException("SAF fd 定位读不完整：$cursor")
            System.arraycopy(page, within, buffer, offset + written, chunk)
            cursor += chunk
            written += chunk
            remaining -= chunk
        }
        return written
    }

    private fun page(pageIndex: Long): ByteArray {
        cache[pageIndex]?.let {
            return it
        }
        val pageOffset = pageIndex shl pageShift
        val size = minOf(pageSize.toLong(), length - pageOffset).toInt()
        val page = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = readAt.read(page, read, size - read, pageOffset + read)
            if (count <= 0) throw IOException("SAF fd 定位读中断：${pageOffset + read}")
            read += count
        }
        cache[pageIndex] = page
        return page
    }
}

/** SAF fd 版式随机读可用性判定（纯函数，单测锁定）：长度为正且定位读探针至少读回 1 字节。 */
internal fun descriptorLayoutReadable(fileLength: Long, probeBytes: Int): Boolean =
    fileLength > 0L && probeBytes > 0

/** 页缓存粒度（与 PdfBox `RandomAccessBufferedFileInputStream` 的 4 KB 页一致）。 */
private const val POSITIONAL_PAGE_SIZE_BYTES: Int = 4 * 1024

/** 页缓存上限（页数）：256 × 4 KB ≈ 1 MB，代价可忽略。 */
private const val POSITIONAL_PAGE_CACHE_LIMIT: Int = 256

/** 定位读探针长度：验证 fd 可 seek 即可，不关心内容。 */
private const val DESCRIPTOR_LAYOUT_PROBE_BYTES: Int = 8

/** PdfBox 主内存缓冲上限：超出部分溢出到临时文件（与 [PdfBoxPageTextSource] 同口径）。 */
private const val PDF_LAYOUT_MEMORY_BUFFER_BYTES: Long = 8L * 1024 * 1024

/**
 * 页面显示宽高比（宽 / 高）纯函数：把 `/Rotate` 折算进尺寸，与 `PdfRenderer.Page.width/height` 同口径。
 *
 * PdfRenderer 的页面尺寸已经是「旋转后」的显示尺寸；旋转 90° / 270° 时交换宽高。取不到有效尺寸返回 null， 版式层按竖版处理（保持 W22 配对行为）。
 */
internal fun pdfAspectRatio(cropWidth: Float, cropHeight: Float, rotation: Int): Float? {
    val displayWidth = if (rotation == 90 || rotation == 270) cropHeight else cropWidth
    val displayHeight = if (rotation == 90 || rotation == 270) cropWidth else cropHeight
    return displayAspectRatio(displayWidth, displayHeight)
}

/** 显示尺寸 → 宽高比；非有限值 / 非正数视为缺失。 */
private fun displayAspectRatio(width: Float, height: Float): Float? {
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
    return width / height
}
