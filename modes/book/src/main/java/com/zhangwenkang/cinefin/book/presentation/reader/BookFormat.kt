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

/** PDF 头只在文件**前 1024 字节**内保证出现（PDF 1.7 §7.5.2）：取 1 KiB 探针再找魔数，兼容少数 带前置垃圾字节的文件；找不到再按 ZIP / 未知处理。 */
private const val FORMAT_PROBE_BYTES = 1024

/**
 * 按内容识别格式。
 *
 * 本地缓存统一命名为 `{itemId}.book`（D3 取书流程），扩展名不可用，只能嗅探内容：
 * - 前 1 KiB 含 `%PDF-` → PDF；
 * - ZIP 且含 `META-INF/container.xml`（OCF 容器，W3C EPUB 3.3）或 `mimetype` → EPUB；
 * - 其余 ZIP → CBZ（条目级图片过滤交给 [orderComicPageNames]）。
 *
 * 之所以必须自己嗅探：Readium 的 `ArchiveSniffer` 只在「压缩包内所有条目扩展名都在白名单 内」或「文件带 .cbz 扩展名」时才认作漫画包，Anda's Game 含
 * `Fonts` 目录下的 ttf 等条目会被判成 普通 ZIP（见 READER_PLAN §8 踩坑 15）。
 */
fun sniffBookFormat(file: File): BookFormat {
    val header = ByteArray(FORMAT_PROBE_BYTES)
    val read = FileInputStream(file).use { it.read(header) }
    // ZIP 魔数在文件头 4 字节内是严格强特征，先判 ZIP，避免压缩包前 1KB 恰好含 "%PDF-" 时误判。
    if (read >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
        return ZipFile(file).use { zip ->
            // OCF 容器（规范必需）或 EPUB mimetype 条目（少数包缺 container.xml）都算 EPUB。
            if (
                zip.getEntry("META-INF/container.xml") != null || zip.getEntry("mimetype") != null
            ) {
                BookFormat.Epub
            } else {
                BookFormat.ComicArchive
            }
        }
    }
    if (read >= PDF_MAGIC.size && header.containsSequence(PDF_MAGIC, read)) {
        return BookFormat.Pdf
    }
    return BookFormat.Unknown
}

/** 在 [length] 字节的有效范围内查找字节序列（避免把未读满的尾部当成数据）。 */
private fun ByteArray.containsSequence(sequence: ByteArray, length: Int): Boolean {
    val lastStart = length - sequence.size
    if (lastStart < 0) return false
    for (start in 0..lastStart) {
        var matched = true
        for (offset in sequence.indices) {
            if (this[start + offset] != sequence[offset]) {
                matched = false
                break
            }
        }
        if (matched) return true
    }
    return false
}
