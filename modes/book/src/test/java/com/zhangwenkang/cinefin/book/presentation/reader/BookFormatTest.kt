package com.zhangwenkang.cinefin.book.presentation.reader

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class BookFormatTest {

    @Test
    fun `PDF 魔数识别为 PDF`() {
        val file = tempFile("attention.book")
        file.writeBytes("%PDF-1.7\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        assertEquals(BookFormat.Pdf, sniffBookFormat(file))
    }

    @Test
    fun `前 1KB 内出现的 PDF 头也能识别`() {
        val file = tempFile("junk-head.book")
        file.writeBytes(ByteArray(300) { 0x20 } + "%PDF-1.7\n".toByteArray())
        assertEquals(BookFormat.Pdf, sniffBookFormat(file))
    }

    @Test
    fun `含 OCF 容器的压缩包识别为 EPUB`() {
        val file = tempFile("ripely.book")
        writeZip(
            file,
            mapOf("mimetype" to "application/epub+zip", "META-INF/container.xml" to "<container/>"),
        )
        assertEquals(BookFormat.Epub, sniffBookFormat(file))
    }

    @Test
    fun `只有 mimetype 的压缩包也识别为 EPUB`() {
        val file = tempFile("minimal-epub.book")
        writeZip(file, mapOf("mimetype" to "application/epub+zip", "content.opf" to "<package/>"))
        assertEquals(BookFormat.Epub, sniffBookFormat(file))
    }

    @Test
    fun `不含 OCF 容器的压缩包识别为 CBZ`() {
        val file = tempFile("andas.book")
        writeZip(
            file,
            mapOf(
                "01.jpg" to "page-1",
                "02.jpg" to "page-2",
                "Fonts/caveatbrush-regular.ttf" to "font",
            ),
        )
        assertEquals(BookFormat.ComicArchive, sniffBookFormat(file))
    }

    @Test
    fun `未知内容不误判`() {
        val file = tempFile("unknown.book")
        file.writeBytes("随便一段文本".toByteArray())
        assertEquals(BookFormat.Unknown, sniffBookFormat(file))
    }

    private fun tempFile(name: String): File =
        Files.createTempDirectory("cinefin-format-test").resolve(name).toFile()

    private fun writeZip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
