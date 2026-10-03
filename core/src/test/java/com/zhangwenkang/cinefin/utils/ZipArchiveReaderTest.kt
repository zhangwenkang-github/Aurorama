package com.zhangwenkang.cinefin.utils

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** W59 ZIP 只读解析（EPUB / CBZ 封面提取）：条目枚举 / DEFLATE / STORED / 大目录。 */
class ZipArchiveReaderTest {

    private fun buildZip(
        entries: List<Pair<String, ByteArray>>,
        stored: Boolean = false,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test
    fun `枚举条目并解出 DEFLATE 字节`() {
        val cover = ByteArray(4096) { index -> (index % 251).toByte() }
        val chapter = "chapter".toByteArray()
        val zipBytes =
            buildZip(
                listOf(
                    "mimetype" to "application/epub+zip".toByteArray(),
                    "OPS/images/cover.jpg" to cover,
                    "OPS/chapter1.xhtml" to chapter,
                )
            )

        ZipArchiveReader(ByteArrayByteSource(zipBytes)).use { zip ->
            assertEquals(
                listOf("mimetype", "OPS/images/cover.jpg", "OPS/chapter1.xhtml"),
                zip.entries.map { it.name },
            )
            val entry = zip.find("OPS/images/cover.jpg")
            assertNotNull(entry)
            assertArrayEquals(cover, zip.readBytes(entry!!))
            assertNull(zip.find("missing"))
        }
    }

    @Test
    fun `解出 STORED 条目（CBZ 常见写法）`() {
        val page = ByteArray(1024) { index -> index.toByte() }
        val zipBytes = buildZip(listOf("001.jpg" to page), stored = true)

        ZipArchiveReader(ByteArrayByteSource(zipBytes)).use { zip ->
            val entry = zip.find("001.jpg") ?: error("缺少条目")
            assertEquals(0, entry.method)
            assertArrayEquals(page, zip.readBytes(entry))
        }
    }

    @Test
    fun `EPUB container 条目可见 用于区分 CBZ`() {
        val epub = buildZip(listOf("META-INF/container.xml" to "<container/>".toByteArray()))
        ZipArchiveReader(ByteArrayByteSource(epub)).use { zip ->
            assertNotNull(zip.find("META-INF/container.xml"))
        }
        val cbz = buildZip(listOf("pages/001.jpg" to ByteArray(16)))
        ZipArchiveReader(ByteArrayByteSource(cbz)).use { zip ->
            assertNull(zip.find("META-INF/container.xml"))
        }
    }

    @Test
    fun `非 ZIP 字节报错`() {
        val error = runCatching {
            ZipArchiveReader(ByteArrayByteSource("not a zip".toByteArray())).entries
        }
            .exceptionOrNull()
        assertNotNull(error)
    }
}
