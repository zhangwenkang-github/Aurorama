package com.zhangwenkang.cinefin.book.presentation.reader

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.Writer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * PdfBox-Android 实现的 PDF 文本层数据源（W29-READER）。
 *
 * 内存策略：`PDDocument.load` 默认把整个文件读进主内存——《虚构推理》(639 MB) 那种体量会直接爆掉， 因此固定用
 * [MemoryUsageSetting.setupMixed]（32 MB 主内存 + 溢出到临时文件）。渲染侧仍然是 PdfRenderer（D14
 * 不变），本类**只抽文本**，不渲染、不解码图片。
 *
 * 线程：PdfBox 的 `PDDocument` 非线程安全，所有访问用同一把 [Mutex] 串行化。
 */
internal class PdfBoxPageTextSource(
    context: Context,
    private val file: File,
) : PdfPageTextSource {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var document: PDDocument? = null
    private var loadFailure: Throwable? = null
    private var closed = false

    override suspend fun streamPageTexts(
        onPage: (pageIndex: Int, text: String, chars: List<PageChar>) -> Boolean
    ) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val doc = ensureDocument()
                val stripper = StreamingTextStripper(onPage)
                stripper.sortByPosition = true
                try {
                    stripper.writeText(doc, stripper.pageBuffer)
                    // 末尾没有内容流的页（不触发 writePageEnd）按空页补齐，保证页码连续。
                    var index = stripper.lastEmittedPage + 1
                    while (index < doc.numberOfPages) {
                        stripper.lastEmittedPage = index
                        if (!onPage(index, "", emptyList())) break
                        index++
                    }
                } catch (stopped: SearchStopped) {
                    // 达到结果上限 / 调用方要求停止：正常提前结束，不算错误。
                    Timber.d("reader pdf search stopped pages=%d", stripper.lastEmittedPage + 1)
                }
            }
        }
    }

    override fun close() {
        closed = true
        val doc = document
        document = null
        runCatching { doc?.close() }
    }

    private fun ensureDocument(): PDDocument {
        document?.let {
            return it
        }
        loadFailure?.let { throw it }
        if (closed) throw IllegalStateException("PDF 文本层会话已关闭")
        return try {
            PDFBoxResourceLoader.init(appContext)
            PDDocument.load(file, MemoryUsageSetting.setupMixed(PDFBOX_MEMORY_BUFFER_BYTES)).also {
                document = it
            }
        } catch (error: Throwable) {
            loadFailure = error
            throw error
        }
    }

    /** 提前结束（结果上限）用的控制流异常：不做栈回溯。 */
    private class SearchStopped : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * 逐页流式 PDFTextStripper：
     * - 页面文本走 `output`（与 `getText()` 同口径，含换行 / 段落分隔），写到 [pageText]；
     * - 字符框在 [writeString] 里从 [TextPosition] 收集（页面归一化坐标）；
     * - `writePageStart` / `writePageEnd` 是 PDFBox 每页处理的固定钩子，用它们切页并回调。
     */
    private inner class StreamingTextStripper(
        private val onPage: (Int, String, List<PageChar>) -> Boolean
    ) : PDFTextStripper() {
        private val pageText = StringBuilder()
        private val pageChars = ArrayList<PageChar>()
        private var pageWidth = 0f
        private var pageHeight = 0f
        private var pageRotation = 0
        /** 已回调的最后一个页索引（-1 = 还没有）。补齐空页 / 提前结束时由外部改写。 */
        var lastEmittedPage: Int = -1

        private val pageWriter =
            object : Writer() {
                override fun write(buffer: CharArray, offset: Int, length: Int) {
                    pageText.append(buffer, offset, length)
                }

                override fun flush() {}

                override fun close() {}
            }

        /** 页面文本缓冲（页面级 `output`）：与 `getText()` 同口径，含换行 / 段落分隔。 */
        val pageBuffer: Writer
            get() = pageWriter

        override fun startPage(page: PDPage) {
            super.startPage(page)
            val crop = page.cropBox
            pageWidth = crop.width
            pageHeight = crop.height
            pageRotation = page.rotation
        }

        override fun writePageStart() {
            super.writePageStart()
            pageText.setLength(0)
            pageChars.clear()
        }

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            // 文本走父类实现（写进 pageWriter），这里只负责把字符框收下来。
            super.writeString(text, textPositions)
            collectChars(textPositions)
        }

        override fun writePageEnd() {
            super.writePageEnd()
            // 没有内容流的页不会触发本回调：先补齐中间的空页，保证页码连续。
            val index = currentPageNo - 1
            while (lastEmittedPage + 1 < index) {
                lastEmittedPage++
                if (!onPage(lastEmittedPage, "", emptyList())) throw SearchStopped()
            }
            lastEmittedPage = index
            if (!onPage(index, pageText.toString(), pageChars.toList())) throw SearchStopped()
        }

        private fun collectChars(textPositions: MutableList<TextPosition>?) {
            if (textPositions.isNullOrEmpty()) return
            val displayWidth =
                if (pageRotation == 90 || pageRotation == 270) pageHeight else pageWidth
            val displayHeight =
                if (pageRotation == 90 || pageRotation == 270) pageWidth else pageHeight
            if (displayWidth <= 0f || displayHeight <= 0f) return
            textPositions.forEach { position ->
                val unicode = position.unicode ?: return@forEach
                if (unicode.isEmpty()) return@forEach
                val left = position.x / displayWidth
                val right = (position.x + position.widthDirAdj) / displayWidth
                // getYDirAdj 是基线在「上原点」坐标系里的位置，字形框取 [y - 高, y]。
                val top = (position.y - position.heightDir) / displayHeight
                val bottom = position.y / displayHeight
                val rect = PageRect(left, top, right, bottom).normalized()
                if (rect.width <= 0f && rect.height <= 0f) return@forEach
                unicode.forEach { char ->
                    if (!char.isWhitespace()) pageChars += PageChar(char, rect)
                }
            }
        }
    }
}

/**
 * PdfBox 主内存缓冲上限：超出部分溢出到临时文件。
 *
 * `PDDocument.load` 默认整份读进主内存（639 MB 的《虚构推理》会直接爆），这里压到 8 MB， 与 EB-3 的「页位图窗口 3
 * 张」内存红线同量级；文本抽取是顺序读，临时文件走 OS page cache，代价可接受。
 */
private const val PDFBOX_MEMORY_BUFFER_BYTES: Long = 8L * 1024 * 1024
