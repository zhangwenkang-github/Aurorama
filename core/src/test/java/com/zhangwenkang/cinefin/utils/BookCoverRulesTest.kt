package com.zhangwenkang.cinefin.utils

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
    fun `W69b 服务器图确认不可用时回落到生成链路`() {
        // 服务器给了 URL 但取图 404：有生成缓存 → 用本地缓存。
        assertEquals(
            BookCoverRules.CoverSource.GENERATED_CACHE,
            BookCoverRules.planCover(
                serverImageUrl = "https://host/cover.jpg",
                generatedPath = "/files/book_covers/a.jpg",
                generationFailed = false,
                serverImageUnavailable = true,
            ),
        )
        // 服务器给了 URL 但取图 404 且没有本地缓存 → 触发本地生成。
        assertEquals(
            BookCoverRules.CoverSource.GENERATE,
            BookCoverRules.planCover(
                serverImageUrl = "https://host/cover.jpg",
                generatedPath = null,
                generationFailed = false,
                serverImageUnavailable = true,
            ),
        )
        // 生成也失败（.fail 标记）→ 类型占位，不再重试。
        assertEquals(
            BookCoverRules.CoverSource.PLACEHOLDER,
            BookCoverRules.planCover(
                serverImageUrl = "https://host/cover.jpg",
                generatedPath = null,
                generationFailed = true,
                serverImageUnavailable = true,
            ),
        )
        // 服务器图可用时行为不变（仍优先服务器图）。
        assertEquals(
            BookCoverRules.CoverSource.SERVER_IMAGE,
            BookCoverRules.planCover(
                serverImageUrl = "https://host/cover.jpg",
                generatedPath = "/files/book_covers/a.jpg",
                generationFailed = false,
                serverImageUnavailable = false,
            ),
        )
    }

    @Test
    fun `W77-5 远端 PDF 直接占位 不触发整本 Range 解析`() {
        // 未下载 + 无服务器封面 + PDF → 占位（不生成）。
        assertEquals(
            BookCoverRules.CoverSource.PLACEHOLDER,
            BookCoverRules.planCover(
                serverImageUrl = null,
                generatedPath = null,
                generationFailed = false,
                remotePdf = true,
            ),
        )
        // 服务器图确认不可用（404）时同样占位。
        assertEquals(
            BookCoverRules.CoverSource.PLACEHOLDER,
            BookCoverRules.planCover(
                serverImageUrl = "https://host/cover.jpg",
                generatedPath = null,
                generationFailed = false,
                serverImageUnavailable = true,
                remotePdf = true,
            ),
        )
        // 已有生成缓存仍优先（已下载 / 曾生成成功过的条目不退化成占位）。
        assertEquals(
            BookCoverRules.CoverSource.GENERATED_CACHE,
            BookCoverRules.planCover(
                serverImageUrl = null,
                generatedPath = "/files/book_covers/a.jpg",
                generationFailed = false,
                remotePdf = true,
            ),
        )
        // 服务器图仍最高优先。
        assertEquals(
            BookCoverRules.CoverSource.SERVER_IMAGE,
            BookCoverRules.planCover(
                serverImageUrl = "https://host/cover.jpg",
                generatedPath = null,
                generationFailed = false,
                remotePdf = true,
            ),
        )
        // 非 PDF（EPUB / CBZ / 未知）行为不变 → 仍走生成。
        assertEquals(
            BookCoverRules.CoverSource.GENERATE,
            BookCoverRules.planCover(
                serverImageUrl = null,
                generatedPath = null,
                generationFailed = false,
                remotePdf = false,
            ),
        )
    }

    @Test
    fun `W77-5 远端 PDF 判据只看扩展名 大小写不敏感`() {
        assertTrue(BookCoverRules.isPdfPath("/media/books/虚构推理 (2026).pdf"))
        assertTrue(BookCoverRules.isPdfPath("C:\\media\\books\\W22-Spread-Test.PDF"))
        assertTrue(BookCoverRules.isPdfPath("  a.pdf  "))
        assertFalse(BookCoverRules.isPdfPath(null))
        assertFalse(BookCoverRules.isPdfPath(""))
        assertFalse(BookCoverRules.isPdfPath("/media/books/W22-Spread-Test.cbz"))
        assertFalse(BookCoverRules.isPdfPath("/media/books/飛野同學是笨蛋.epub"))
        // 无扩展名 / 目录名里带 pdf 都不算 PDF 文件。
        assertFalse(BookCoverRules.isPdfPath("/media/pdf/无扩展名"))
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

    @Test
    fun `封面显示来源 服务器图优先 失败回退本地 再失败占位`() {
        // 服务器图可用（即使本地封面也在）：服务器图优先。
        assertEquals(
            BookCoverRules.CoverSource.SERVER_IMAGE,
            BookCoverRules.displaySource(hasServerImage = true, hasLocalCover = true),
        )
        // 服务器图加载失败（离线）→ 回落本地封面。
        assertEquals(
            BookCoverRules.CoverSource.GENERATED_CACHE,
            BookCoverRules.displaySource(
                hasServerImage = true,
                hasLocalCover = true,
                serverFailed = true,
            ),
        )
        // 服务器图失败且无本地封面 → 占位。
        assertEquals(
            BookCoverRules.CoverSource.PLACEHOLDER,
            BookCoverRules.displaySource(
                hasServerImage = true,
                hasLocalCover = false,
                serverFailed = true,
            ),
        )
        // 无服务器图 → 本地封面。
        assertEquals(
            BookCoverRules.CoverSource.GENERATED_CACHE,
            BookCoverRules.displaySource(hasServerImage = false, hasLocalCover = true),
        )
        // 本地封面也失败 → 占位。
        assertEquals(
            BookCoverRules.CoverSource.PLACEHOLDER,
            BookCoverRules.displaySource(
                hasServerImage = false,
                hasLocalCover = true,
                localFailed = true,
            ),
        )
        // 两者都没有 → 占位。
        assertEquals(
            BookCoverRules.CoverSource.PLACEHOLDER,
            BookCoverRules.displaySource(hasServerImage = false, hasLocalCover = false),
        )
    }
}
