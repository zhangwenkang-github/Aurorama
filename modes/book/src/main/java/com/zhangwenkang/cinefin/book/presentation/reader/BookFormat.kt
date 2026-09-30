package com.zhangwenkang.cinefin.book.presentation.reader

import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

/** 阅读器识别的书籍容器（EB-2 / EB-3 / EB-4）。 */
enum class BookFormat(val label: String) {
    /** EPUB：继续由 Readium 渲染（W1–W3 已验收，不回归）。 */
    Epub("EPUB"),

    /** PDF：PdfRenderer 按页懒加载（EB-3 硬约束）。 */
    Pdf("PDF"),

    /** CBZ：ZipFile 随机读的图片包（EB-4）。 */
    ComicArchive("CBZ"),

    /** 未识别：仍交给 Readium 尝试，失败时按原样报错。 */
    Unknown("未知格式"),
}

private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)

/**
 * 按内容识别格式。
 *
 * 本地缓存统一命名为 `{itemId}.book`（D3 取书流程），扩展名不可用，只能嗅探内容：
 * - `%PDF-` 头 → PDF；
 * - ZIP 且含 `META-INF/container.xml` → EPUB（OCF 容器，W3C EPUB 3.3）；
 * - 其余 ZIP → CBZ（条目级图片过滤交给 [orderComicPageNames]）。
 *
 * 之所以必须自己嗅探：Readium 的 `ArchiveSniffer` 只在「压缩包内所有条目扩展名都在白名单 内」或「文件带 .cbz 扩展名」时才认作漫画包，Anda's Game 含
 * `Fonts` 目录下的 ttf 等条目会被判成 普通 ZIP（见 READER_PLAN §8 踩坑 15）。
 */
fun sniffBookFormat(file: File): BookFormat {
    val header = ByteArray(8)
    val read = FileInputStream(file).use { it.read(header) }
    if (read >= PDF_MAGIC.size && header.copyOfRange(0, PDF_MAGIC.size).contentEquals(PDF_MAGIC)) {
        return BookFormat.Pdf
    }
    if (read >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
        return ZipFile(file).use { zip ->
            if (zip.getEntry("META-INF/container.xml") != null) {
                BookFormat.Epub
            } else {
                BookFormat.ComicArchive
            }
        }
    }
    return BookFormat.Unknown
}
