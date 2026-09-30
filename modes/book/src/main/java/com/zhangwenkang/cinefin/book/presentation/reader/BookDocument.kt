package com.zhangwenkang.cinefin.book.presentation.reader

import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/** 阅读页当前打开的文档：EPUB 走 Readium，PDF / CBZ 走页窗口自研视图。 */
sealed interface ReaderDocument {
    /** EPUB（Readium）：支持排版 / 主题 / 书签 / 精确 Locator 进度。 */
    data class Rich(val publication: Publication, val initialLocator: Locator?) : ReaderDocument

    /** PDF / CBZ：按页懒加载；进度用"页索引 / 总页数"换算 progression（EB-3 / EB-4）。 */
    data class Simple(
        val format: SimpleBookFormat,
        val pageSource: PageSource,
        val initialPage: Int,
    ) : ReaderDocument
}

/** PDF / CBZ 两种"页序列"格式。 */
enum class SimpleBookFormat(val label: String) {
    Pdf("PDF"),
    ComicArchive("CBZ"),
}
