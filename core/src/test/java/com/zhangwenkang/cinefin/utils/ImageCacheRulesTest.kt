package com.zhangwenkang.cinefin.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W66：图片缓存过期替换判定与元数据编解码（纯函数）。
 *
 * 判定优先级：文件缺失 → 元数据缺失 → 源 URL 变化 → 超 TTL → 保留（KEEP）。
 */
class ImageCacheRulesTest {

    private val now = 1_700_000_000_000L
    private val freshMeta =
        ImageCacheRules.Meta(
            sourceUrl = "https://server/Items/a/Images/Primary?tag=1",
            etag = "etag-1",
            lastModified = "Wed, 01 Oct 2025 00:00:00 GMT",
            fetchedAt = now - 1000L,
        )

    @Test
    fun missingOrEmptyFileRefetches() {
        assertEquals(
            ImageCacheRules.Decision.REFETCH_FILE_MISSING,
            ImageCacheRules.decide(
                fileExists = false,
                fileSizeBytes = 0L,
                meta = freshMeta,
                currentSourceUrl = freshMeta.sourceUrl,
                nowMillis = now,
            ),
        )
        assertEquals(
            ImageCacheRules.Decision.REFETCH_FILE_MISSING,
            ImageCacheRules.decide(
                fileExists = true,
                fileSizeBytes = 0L,
                meta = null,
                currentSourceUrl = null,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun missingMetaRefetchesEvenWhenFileExists() {
        assertEquals(
            ImageCacheRules.Decision.REFETCH_META_MISSING,
            ImageCacheRules.decide(
                fileExists = true,
                fileSizeBytes = 1024L,
                meta = null,
                currentSourceUrl = freshMeta.sourceUrl,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun changedSourceUrlRefetches() {
        assertEquals(
            ImageCacheRules.Decision.REFETCH_SOURCE_CHANGED,
            ImageCacheRules.decide(
                fileExists = true,
                fileSizeBytes = 1024L,
                meta = freshMeta,
                currentSourceUrl = "https://other-server/Items/a/Images/Primary?tag=1",
                nowMillis = now,
            ),
        )
    }

    @Test
    fun expiredMetaRefetches() {
        assertEquals(
            ImageCacheRules.Decision.REFETCH_EXPIRED,
            ImageCacheRules.decide(
                fileExists = true,
                fileSizeBytes = 1024L,
                meta = freshMeta,
                currentSourceUrl = freshMeta.sourceUrl,
                nowMillis = now + ImageCacheRules.TTL_MS + 1L,
            ),
        )
        assertEquals(
            ImageCacheRules.Decision.REFETCH_EXPIRED,
            ImageCacheRules.decide(
                fileExists = true,
                fileSizeBytes = 1024L,
                meta = freshMeta,
                currentSourceUrl = null,
                nowMillis = now + ImageCacheRules.TTL_MS + 1L,
            ),
        )
    }

    @Test
    fun freshFileWithMetaAndSameUrlKeeps() {
        assertEquals(
            ImageCacheRules.Decision.KEEP,
            ImageCacheRules.decide(
                fileExists = true,
                fileSizeBytes = 1024L,
                meta = freshMeta,
                currentSourceUrl = freshMeta.sourceUrl,
                nowMillis = now,
            ),
        )
        // URL 未知（未查询服务器）时不因 URL 分支重拉；其余条件均满足则保留。
        assertEquals(
            ImageCacheRules.Decision.KEEP,
            ImageCacheRules.decide(
                fileExists = true,
                fileSizeBytes = 1024L,
                meta = freshMeta,
                currentSourceUrl = null,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun shouldRefetchOnlyForNonKeepDecisions() {
        assertFalse(ImageCacheRules.shouldRefetch(ImageCacheRules.Decision.KEEP))
        assertTrue(ImageCacheRules.shouldRefetch(ImageCacheRules.Decision.REFETCH_FILE_MISSING))
        assertTrue(ImageCacheRules.shouldRefetch(ImageCacheRules.Decision.REFETCH_META_MISSING))
        assertTrue(ImageCacheRules.shouldRefetch(ImageCacheRules.Decision.REFETCH_SOURCE_CHANGED))
        assertTrue(ImageCacheRules.shouldRefetch(ImageCacheRules.Decision.REFETCH_EXPIRED))
    }

    @Test
    fun metaEncodeDecodeRoundTrip() {
        val decoded = ImageCacheRules.decodeMeta(ImageCacheRules.encodeMeta(freshMeta))
        assertEquals(freshMeta, decoded)

        val noEtags =
            ImageCacheRules.Meta(
                sourceUrl = "https://server/a.b/primary?x=1&y=2",
                etag = null,
                lastModified = null,
                fetchedAt = 42L,
            )
        assertEquals(noEtags, ImageCacheRules.decodeMeta(ImageCacheRules.encodeMeta(noEtags)))
    }

    @Test
    fun metaDecodeRejectsInvalidInput() {
        assertNull(ImageCacheRules.decodeMeta(null))
        assertNull(ImageCacheRules.decodeMeta(""))
        assertNull(ImageCacheRules.decodeMeta("etag=x\nfetchedAt=1"))
        assertNull(ImageCacheRules.decodeMeta("sourceUrl=https://a\nno-fetched-at"))
        assertNull(ImageCacheRules.decodeMeta("sourceUrl=https://a\nfetchedAt=not-a-number"))
    }

    @Test
    fun artworkMemoryCacheKeyVersionsLocalPathsAndKeepsRemoteDefault() {
        assertNull(ImageCacheRules.artworkMemoryCacheKey(null))
        assertNull(ImageCacheRules.artworkMemoryCacheKey(""))
        assertNull(ImageCacheRules.artworkMemoryCacheKey("https://server/Items/x/Images/Primary"))

        val temp = File.createTempFile("cinefin-image", ".bin")
        try {
            temp.writeBytes(byteArrayOf(1, 2, 3))
            temp.setLastModified(1_000L)
            val first = ImageCacheRules.artworkMemoryCacheKey(temp.absolutePath)
            temp.setLastModified(2_000L)
            val second = ImageCacheRules.artworkMemoryCacheKey(temp.absolutePath)
            assertNotEquals(first, second)
            assertTrue(first!!.startsWith("${temp.absolutePath}#"))
        } finally {
            temp.delete()
        }
    }
}
