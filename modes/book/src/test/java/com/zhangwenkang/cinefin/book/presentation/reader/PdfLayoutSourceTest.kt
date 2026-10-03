package com.zhangwenkang.cinefin.book.presentation.reader

import com.tom_roush.pdfbox.io.RandomAccessBufferedFileInputStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF 版式元数据读取（W33，踩坑 29）：PdfBox 只读页树得到与 PdfRenderer 同口径的显示宽高比。
 *
 * 覆盖：`/Rotate` 折算（0 / 90 / 180 / 270）、`/CropBox` 与 `/MediaBox` 回退、无效尺寸与标题。 修复目标：双栏版式扫描不再逐页
 * `PdfRenderer.openPage`（3649 页 PDF 的 native 内存回归）。
 */
class PdfLayoutSourceTest {

    @Test
    fun `旋转折算：0 与 180 不换轴，90 与 270 交换宽高`() {
        assertEquals(600f / 900f, requireNotNull(pdfAspectRatio(600f, 900f, 0)), 0.0001f)
        assertEquals(600f / 900f, requireNotNull(pdfAspectRatio(600f, 900f, 180)), 0.0001f)
        assertEquals(900f / 600f, requireNotNull(pdfAspectRatio(600f, 900f, 90)), 0.0001f)
        assertEquals(900f / 600f, requireNotNull(pdfAspectRatio(600f, 900f, 270)), 0.0001f)
        // PdfRenderer 同样把非法 / 负角度按 0 处理（PDPage.getRotation 归一化）
        assertEquals(600f / 900f, requireNotNull(pdfAspectRatio(600f, 900f, 45)), 0.0001f)
        assertEquals(600f / 900f, requireNotNull(pdfAspectRatio(600f, 900f, -90)), 0.0001f)
        assertNull(pdfAspectRatio(0f, 900f, 0))
        assertNull(pdfAspectRatio(600f, 0f, 0))
        assertNull(pdfAspectRatio(Float.NaN, 900f, 0))
        assertNull(pdfAspectRatio(600f, Float.POSITIVE_INFINITY, 0))
    }

    @Test
    fun `页树批量读取：横版竖版旋转页与缺省 MediaBox 回退`() = runBlocking {
        val file = Files.createTempFile("w33-layout", ".pdf").toFile()
        try {
            PDDocument().use { doc ->
                doc.addPage(PDPage(PDRectangle(0f, 0f, 600f, 900f)))
                doc.addPage(PDPage(PDRectangle(0f, 0f, 1200f, 800f)))
                doc.addPage(PDPage(PDRectangle(0f, 0f, 800f, 1200f)).apply { rotation = 90 })
                doc.save(file)
            }

            val aspects =
                PdfLayoutSource(file).let { source ->
                    try {
                        source.pageAspectRatios()
                    } finally {
                        source.close()
                    }
                }

            assertEquals(3, aspects.size)
            assertEquals(600f / 900f, requireNotNull(aspects[0]), 0.0001f)
            assertEquals(1.5f, requireNotNull(aspects[1]), 0.0001f)
            // 旋转 90° 后显示尺寸 = 1200×800（与 PdfRenderer 的 FilePage 同口径）
            assertEquals(1.5f, requireNotNull(aspects[2]), 0.0001f)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `继承的 Rotate 也生效`() = runBlocking {
        val file = Files.createTempFile("w33-layout-inherit", ".pdf").toFile()
        try {
            PDDocument().use { doc ->
                doc.documentCatalog.pages.cosObject.setInt(
                    com.tom_roush.pdfbox.cos.COSName.ROTATE,
                    90,
                )
                doc.addPage(PDPage(PDRectangle(0f, 0f, 800f, 1200f)))
                doc.save(file)
            }

            val aspects =
                PdfLayoutSource(file).let { source ->
                    try {
                        source.pageAspectRatios()
                    } finally {
                        source.close()
                    }
                }

            assertEquals(1, aspects.size)
            assertEquals(1.5f, requireNotNull(aspects[0]), 0.0001f)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `SAF fd 可用性判定：长度为正且定位读探针成功`() {
        assertTrue(descriptorLayoutReadable(1024L, 8))
        // 管道 / 代理 fd：pread 抛 ESPIPE，探针读数 ≤ 0
        assertFalse(descriptorLayoutReadable(1024L, 0))
        assertFalse(descriptorLayoutReadable(1024L, -1))
        assertFalse(descriptorLayoutReadable(0L, 8))
        assertFalse(descriptorLayoutReadable(-1L, 8))
    }

    @Test
    fun `随机读源批量读取与 File 路径同口径（W48 SAF 路径）`() = runBlocking {
        val file = Files.createTempFile("w48-layout-random-access", ".pdf").toFile()
        try {
            PDDocument().use { doc ->
                doc.addPage(PDPage(PDRectangle(0f, 0f, 600f, 900f)))
                doc.addPage(PDPage(PDRectangle(0f, 0f, 1200f, 800f)))
                doc.addPage(PDPage(PDRectangle(0f, 0f, 800f, 1200f)).apply { rotation = 90 })
                doc.save(file)
            }

            val aspects =
                PdfLayoutSource(RandomAccessBufferedFileInputStream(file)).let { source ->
                    try {
                        source.pageAspectRatios()
                    } finally {
                        source.close()
                    }
                }

            assertEquals(3, aspects.size)
            assertEquals(600f / 900f, requireNotNull(aspects[0]), 0.0001f)
            assertEquals(1.5f, requireNotNull(aspects[1]), 0.0001f)
            assertEquals(1.5f, requireNotNull(aspects[2]), 0.0001f)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `页缓存定位读：单字节合并整页、跨页读与 EOF（W48）`() {
        val bytes = ByteArray(10_000) { (it % 251).toByte() }
        var bottomReads = 0
        val reader =
            PagedPositionalReader(
                length = bytes.size.toLong(),
                pageSize = 1024,
                maxCachedPages = 4,
            ) { buffer, offset, count, position ->
                bottomReads++
                val available = minOf(count.toLong(), bytes.size - position).toInt()
                if (available <= 0) 0
                else {
                    System.arraycopy(bytes, position.toInt(), buffer, offset, available)
                    available
                }
            }

        val before = bottomReads
        for (index in 0 until 300) {
            assertEquals(bytes[index].toInt() and 0xFF, reader.byteAt(index.toLong()))
        }
        // 连续 300 字节落在同一页：只触发 1 次底层定位读
        assertEquals(1, bottomReads - before)

        // 跨页读：从 900 起读 1500 字节跨两页，内容与源一致
        val out = ByteArray(1500)
        assertEquals(1500, reader.read(out, 0, 1500, 900L))
        for (index in out.indices) {
            assertEquals(bytes[900 + index], out[index])
        }

        // EOF：越界读返回 0、单字节返回 -1
        assertEquals(0, reader.read(ByteArray(8), 0, 8, bytes.size.toLong()))
        assertEquals(-1, reader.byteAt(bytes.size.toLong()))
    }
}
