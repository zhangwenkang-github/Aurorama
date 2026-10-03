package com.zhangwenkang.cinefin.local

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** W45 本地缩略图纯函数单测：缓存路径 / 缩放尺寸 / 首项选取 / 回退与失败标记。 */
class LocalThumbnailRulesTest {

    @get:Rule val temp = TemporaryFolder()

    private fun entry(
        name: String,
        kind: LocalMediaKind,
        path: String = name,
        id: UUID = UUID.randomUUID(),
        coverUri: String? = null,
    ) =
        LocalLibraryEntry(
            itemId = id,
            folderId = 1L,
            name = name,
            kind = kind,
            documentUri = "content://tree/primary/$path",
            relativePath = path,
            sizeBytes = 1024L,
            lastModified = 0L,
            coverUri = coverUri,
        )

    @Test
    fun `缓存路径与失败标记按 itemId 落在 local_thumbs`() {
        val filesDir = temp.newFolder("files")
        val id = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val cache = LocalThumbnailRules.cacheFile(filesDir, id)
        val marker = LocalThumbnailRules.failureMarker(filesDir, id)
        assertEquals("local_thumbs", cache.parentFile?.name)
        assertEquals("$id.jpg", cache.name)
        assertEquals("local_thumbs", marker.parentFile?.name)
        assertEquals("$id.fail", marker.name)
        assertEquals(cache.absolutePath, LocalThumbnailRules.cacheFile(filesDir, id).absolutePath)
        assertTrue(
            LocalThumbnailRules.cacheFile(filesDir, UUID.randomUUID()) !=
                LocalThumbnailRules.cacheFile(filesDir, id)
        )
    }

    @Test
    fun `缩放保持比例且不放大原图`() {
        assertEquals(512 to 288, LocalThumbnailRules.thumbSize(1920, 1080))
        assertEquals(288 to 512, LocalThumbnailRules.thumbSize(1080, 1920))
        assertEquals(400 to 300, LocalThumbnailRules.thumbSize(400, 300))
        assertEquals(1 to 1, LocalThumbnailRules.thumbSize(1, 1))
        assertNull(LocalThumbnailRules.thumbSize(0, 100))
        assertNull(LocalThumbnailRules.thumbSize(100, -1))
        assertNull(LocalThumbnailRules.thumbSize(100, 100, maxSide = 0))
    }

    @Test
    fun `视频目标尺寸按旋转角折算显示宽高`() {
        assertEquals(512 to 288, LocalThumbnailRules.videoTargetSize(1920, 1080, 0))
        assertEquals(288 to 512, LocalThumbnailRules.videoTargetSize(1920, 1080, 90))
        assertEquals(288 to 512, LocalThumbnailRules.videoTargetSize(1920, 1080, 270))
        assertEquals(512 to 288, LocalThumbnailRules.videoTargetSize(1920, 1080, 360))
        assertNull(LocalThumbnailRules.videoTargetSize(0, 0, 0))
    }

    @Test
    fun `已有封面判定：音乐看封面URI其余看缓存文件`() {
        val filesDir = temp.newFolder("files2")
        val music = entry("a.mp3", LocalMediaKind.MUSIC, coverUri = "/data/covers/a.jpg")
        assertTrue(LocalThumbnailRules.hasCover(filesDir, music))
        assertFalse(LocalThumbnailRules.hasCover(filesDir, entry("b.mp3", LocalMediaKind.MUSIC)))

        val video = entry("a.mp4", LocalMediaKind.VIDEO)
        assertFalse(LocalThumbnailRules.hasCover(filesDir, video))
        val cache = LocalThumbnailRules.cacheFile(filesDir, video.itemId)
        cache.parentFile?.mkdirs()
        cache.writeBytes(ByteArray(4))
        assertTrue(LocalThumbnailRules.hasCover(filesDir, video))
        cache.writeBytes(ByteArray(0))
        assertFalse("空文件视为未生成", LocalThumbnailRules.hasCover(filesDir, video))
    }

    @Test
    fun `封面候选顺序为视频_书籍_音乐且同类型按路径稳定`() {
        val music = entry("b.mp3", LocalMediaKind.MUSIC, path = "b.mp3")
        val videoLate = entry("z.mp4", LocalMediaKind.VIDEO, path = "z/z.mp4")
        val videoEarly = entry("a.mp4", LocalMediaKind.VIDEO, path = "a.mp4")
        val book = entry("c.pdf", LocalMediaKind.BOOK, path = "c.pdf")
        val ordered =
            LocalThumbnailRules.coverCandidates(listOf(music, videoLate, book, videoEarly))
        assertEquals(
            listOf(videoEarly.itemId, videoLate.itemId, book.itemId, music.itemId),
            ordered.map { it.itemId },
        )
    }

    @Test
    fun `封面计划优先已有缩略图否则限额生成`() {
        val a = entry("a.mp4", LocalMediaKind.VIDEO, path = "a.mp4")
        val b = entry("b.pdf", LocalMediaKind.BOOK, path = "b.pdf")
        val c = entry("c.mp3", LocalMediaKind.MUSIC, path = "c.mp3")
        val candidates = LocalThumbnailRules.coverCandidates(listOf(c, b, a))

        val readyPlan =
            LocalThumbnailRules.planCover(candidates, readyIds = setOf(c.itemId, b.itemId))
        assertEquals(b.itemId, readyPlan.ready?.itemId)
        assertTrue("已有封面时不再现场生成", readyPlan.attempts.isEmpty())

        val coldPlan = LocalThumbnailRules.planCover(candidates, readyIds = emptySet())
        assertNull(coldPlan.ready)
        assertEquals(
            "默认最多试 3 张（候选顺序）",
            listOf(a.itemId, b.itemId, c.itemId),
            coldPlan.attempts.map { it.itemId },
        )

        val limited = LocalThumbnailRules.planCover(candidates, emptySet(), maxAttempts = 1)
        assertEquals(listOf(a.itemId), limited.attempts.map { it.itemId })
        assertTrue(
            "上限为 0 时不生成",
            LocalThumbnailRules.planCover(candidates, emptySet(), maxAttempts = 0)
                .attempts
                .isEmpty(),
        )
    }

    @Test
    fun `CBZ 第一张图跳过目录隐藏文件与资源叉`() {
        assertTrue(LocalThumbnailRules.isComicPageImage("001.png", isDirectory = false))
        assertTrue(LocalThumbnailRules.isComicPageImage("pages/002.JPG", isDirectory = false))
        assertFalse(LocalThumbnailRules.isComicPageImage("pages", isDirectory = true))
        assertFalse(LocalThumbnailRules.isComicPageImage("__MACOSX/._001.png", false))
        assertFalse(LocalThumbnailRules.isComicPageImage(".hidden.png", false))
        assertFalse(LocalThumbnailRules.isComicPageImage("info.txt", false))
        assertFalse(LocalThumbnailRules.isComicPageImage("", false))
    }

    @Test
    fun `书籍封面生成只针对pdf_cbz_epub`() {
        assertTrue(LocalThumbnailRules.needsBookCover(entry("a.pdf", LocalMediaKind.BOOK)))
        assertTrue(LocalThumbnailRules.needsBookCover(entry("a.cbz", LocalMediaKind.BOOK)))
        assertTrue(LocalThumbnailRules.needsBookCover(entry("a.epub", LocalMediaKind.BOOK)))
        assertFalse(LocalThumbnailRules.needsBookCover(entry("a.mp4", LocalMediaKind.VIDEO)))
    }

    @Test
    fun `视频首帧回退顺序为第一秒再第零秒`() {
        assertEquals(listOf(1_000_000L, 0L), LocalThumbnailRules.VIDEO_FRAME_TIMES_US)
    }
}
