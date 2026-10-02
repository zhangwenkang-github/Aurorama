package com.zhangwenkang.cinefin.book.presentation.reader

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * PDF 版式元数据（页宽高比）数据源（W33）。
 *
 * 背景（READER_PLAN §7.13.4 / 踩坑 29）：W26 的双栏「横版整页独占」用 `PdfRenderer.openPage` 逐页读尺寸， 3649 页 PDF 上把
 * native heap 抬到 748 MB 且不回收。**只读页树元数据不需要渲染、更不需要逐页 openPage**： PdfBox 读 `/CropBox`（缺省回退
 * `/MediaBox`）与继承的 `/Rotate` 即可得到与 PdfRenderer 同口径的显示尺寸。
 *
 * 内存策略与 [PdfBoxPageTextSource] 同源：`setupMixed(8 MB)` 主缓冲 + 溢出临时文件；页面对象读完即丢弃，
 * 常驻只有页树与缓冲。线程：`PDDocument` 非线程安全，读取用同一把 [Mutex] 串行化。
 */
internal class PdfLayoutSource(
    private val file: File,
    private val memoryUsage: MemoryUsageSetting =
        MemoryUsageSetting.setupMixed(PDF_LAYOUT_MEMORY_BUFFER_BYTES),
) {
    private val mutex = Mutex()
    private var document: PDDocument? = null
    private var failure: Throwable? = null
    private var closed = false
    private var cache: List<Float?>? = null

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
        runCatching { doc?.close() }
    }

    private fun ensureDocument(): PDDocument {
        document?.let {
            return it
        }
        failure?.let { throw it }
        if (closed) throw IllegalStateException("PDF 版式会话已关闭")
        return try {
            PDDocument.load(file, memoryUsage).also { document = it }
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
}

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
