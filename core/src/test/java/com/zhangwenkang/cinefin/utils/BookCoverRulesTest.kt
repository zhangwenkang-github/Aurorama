package com.zhangwenkang.cinefin.utils

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** W59 书籍封面计划 / 格式嗅探 / EPUB 封面定位（纯函数）。 */
class BookCoverRulesTest {

    @Test
    fun `文件头嗅探 PDF 与 ZIP`() {
        assertEquals(
            BookCoverRules.Kind.PDF,
            BookCoverRules.detectKind("%PDF-1.7".toByteArray()),
        )
        assertEquals(
            BookCoverRules.Kind.ZIP,
            BookCoverRules.detectKind(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)),
        )
        assertEquals(BookCoverRules.Kind.UNKNOWN, BookCoverRules.detectKind(ByteArray(0)))
        assertEquals(
            BookCoverRules.Kind.UNKNOWN,
            BookCoverRules.detectKind("hello world".toByteArray()),
        )
    }

    @Test
    fun `ZIP 内部结构分流 EPUB 与 CBZ`() {
        assertEquals(BookCoverRules.ZipKind.EPUB, BookCoverRules.zipKind(hasContainerXml = true))
        assertEquals(BookCoverRules.ZipKind.CBZ, BookCoverRules.zipKind(hasContainerXml = false))
    }

    @Test
    fun `封面计划 服务器图优先 其次生成缓存 再生成 最后占位`() {
        assertEquals(
            BookCoverRules.CoverSource.SERVER_IMAGE,
            BookCoverRules.planCover(
                serverImageUrl = "https://host/cover.jpg",
                generatedPath = "/files/book_covers/a.jpg",
                generationFailed = true,
            ),
        )
        assertEquals(
            BookCoverRules.CoverSource.GENERATED_CACHE,
            BookCoverRules.planCover(
                serverImageUrl = null,
                generatedPath = "/files/book_covers/a.jpg",
                generationFailed = false,
            ),
        )
        assertEquals(
            BookCoverRules.CoverSource.GENERATE,
            BookCoverRules.planCover(
                serverImageUrl = null,
                generatedPath = null,
                generationFailed = false,
            ),
        )
        assertEquals(
            BookCoverRules.CoverSource.PLACEHOLDER,
            BookCoverRules.planCover(
                serverImageUrl = null,
                generatedPath = null,
                generationFailed = true,
            ),
        )
        assertEquals(
            BookCoverRules.CoverSource.PLACEHOLDER,
            BookCoverRules.planCover(
                serverImageUrl = "   ",
                generatedPath = "",
                generationFailed = true,
            ),
        )
    }

    @Test
    fun `缓存与失败标记路径分属 book_covers 目录`() {
        val filesDir = java.io.File("/tmp/files")
        val itemId = UUID.randomUUID()
        assertEquals(
            java.io.File("/tmp/files/book_covers/$itemId.jpg"),
            BookCoverRules.cacheFile(filesDir, itemId),
        )
        assertEquals(
            java.io.File("/tmp/files/book_covers/$itemId.fail"),
            BookCoverRules.failureMarker(filesDir, itemId),
        )
        assertNotEquals(
            BookCoverRules.cacheFile(filesDir, itemId),
            BookCoverRules.failureMarker(filesDir, itemId),
        )
    }

    @Test
    fun `EPUB 封面选择顺序 cover-image 优先于 meta cover 与启发式`() {
        val items =
            listOf(
                EpubCoverRules.ManifestItem("img1", "images/other.jpg", "image/jpeg", ""),
                EpubCoverRules.ManifestItem("img2", "images/meta-cover.jpg", "image/jpeg", ""),
                EpubCoverRules.ManifestItem(
                    "img3",
                    "images/cover.jpg",
                    "image/jpeg",
                    "cover-image",
                ),
                EpubCoverRules.ManifestItem("text", "chapter1.xhtml", "application/xhtml+xml", ""),
            )
        assertEquals(
            "images/cover.jpg",
            EpubCoverRules.selectCoverHref(items, coverMetaId = "img2"),
        )
        assertEquals(
            "images/meta-cover.jpg",
            EpubCoverRules.selectCoverHref(
                items.filter { it.id != "img3" },
                coverMetaId = "img2",
            ),
        )
        assertEquals(
            "images/COVER-art.png",
            EpubCoverRules.selectCoverHref(
                items.filter { it.id != "img2" && it.id != "img3" } +
                    EpubCoverRules.ManifestItem("x", "images/COVER-art.png", "image/png", ""),
                coverMetaId = null,
            ),
        )
        assertEquals(
            null,
            EpubCoverRules.selectCoverHref(items.filter { it.id == "text" }, coverMetaId = null),
        )
    }

    @Test
    fun `EPUB 相对 href 解析到归档路径`() {
        assertEquals(
            "OPS/images/cover.jpg",
            EpubCoverRules.resolveHref("OPS/content.opf", "images/cover.jpg"),
        )
        assertEquals("cover.jpg", EpubCoverRules.resolveHref("OPS/content.opf", "../cover.jpg"))
        assertEquals("cover.jpg", EpubCoverRules.resolveHref("content.opf", "cover.jpg"))
        assertEquals("OPS/a b.jpg", EpubCoverRules.resolveHref("OPS/content.opf", "a%20b.jpg"))
        assertEquals(
            "OPS/x/cover.jpg",
            EpubCoverRules.resolveHref("OPS/content.opf", "./x/cover.jpg#frag"),
        )
        assertEquals("cover.jpg", EpubCoverRules.resolveHref("OPS/content.opf", "/cover.jpg"))
    }

    @Test
    fun `封面覆盖值 服务器图优先 缺图回退生成封面 无图返回空`() {
        // 有服务器图：不使用本地生成封面（返回 null = 卡片走服务器图）。
        assertEquals(
            null,
            BookCoverRules.coverOverride(
                serverImageUrl = "https://host/cover.jpg",
                generatedPath = "/files/book_covers/a.jpg",
            ),
        )
        assertEquals(
            "/files/book_covers/a.jpg",
            BookCoverRules.coverOverride(
                serverImageUrl = null,
                generatedPath = "/files/book_covers/a.jpg",
            ),
        )
        // 服务器图字段为空串 / 空白 = 视为缺图。
        assertEquals(
            "/files/book_covers/a.jpg",
            BookCoverRules.coverOverride(
                serverImageUrl = " ",
                generatedPath = "/files/book_covers/a.jpg",
            ),
        )
        // 都没有 → null（卡片回退类型占位）。
        assertEquals(
            null,
            BookCoverRules.coverOverride(serverImageUrl = null, generatedPath = null),
        )
        assertEquals(
            null,
            BookCoverRules.coverOverride(serverImageUrl = null, generatedPath = " "),
        )
    }
}
