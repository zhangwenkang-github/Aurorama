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
    fun `purgeThumbnails 删除指定条目的缓存与失败标记且不动其他文件`() {
        val filesDir = temp.newFolder("purge-files")
        val removed = UUID.fromString("22222222-3333-4444-5555-666666666666")
        val kept = UUID.fromString("77777777-8888-9999-aaaa-bbbbbbbbbbbb")
        val removedCache =
            LocalThumbnailRules.cacheFile(filesDir, removed).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1))
            }
        val removedMarker =
            LocalThumbnailRules.failureMarker(filesDir, removed).apply {
                writeBytes(byteArrayOf(2))
            }
        val keptCache =
            LocalThumbnailRules.cacheFile(filesDir, kept).apply { writeBytes(byteArrayOf(3)) }

        val deleted = LocalThumbnailRules.purgeThumbnails(filesDir, listOf(removed))

        assertEquals(2, deleted)
        assertFalse(removedCache.exists())
        assertFalse(removedMarker.exists())
        assertTrue(keptCache.exists())
        assertEquals(0, LocalThumbnailRules.purgeThumbnails(filesDir, emptyList()))
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
    fun `视频候选帧为 1 秒 10% 30% 再第 0 秒且去重`() {
        // 10 分钟：1s → 60s → 180s → 0s
        assertEquals(
            listOf(1_000_000L, 60_000_000L, 180_000_000L, 0L),
            LocalThumbnailRules.videoFrameTimesUs(600_000L),
        )
        // 10 秒：10% 与第 1 秒重合 → 去重
        assertEquals(
            listOf(1_000_000L, 3_000_000L, 0L),
            LocalThumbnailRules.videoFrameTimesUs(10_000L),
        )
        // 时长未知 / 非法：只保留第 1 秒与第 0 秒
        assertEquals(listOf(1_000_000L, 0L), LocalThumbnailRules.videoFrameTimesUs(null))
        assertEquals(listOf(1_000_000L, 0L), LocalThumbnailRules.videoFrameTimesUs(0L))
        assertEquals(listOf(1_000_000L, 0L), LocalThumbnailRules.videoFrameTimesUs(-5L))
    }

    @Test
    fun `近黑帧判定使用平均相对亮度阈值`() {
        assertTrue(LocalThumbnailRules.isNearlyBlack(IntArray(16) { 0xFF000000.toInt() }))
        // 0x14 = 20 → 相对亮度 ≈ 0.078，仍在黑场阈值内
        assertTrue(LocalThumbnailRules.isNearlyBlack(IntArray(16) { 0xFF141414.toInt() }))
        // 0x40 = 64 → 相对亮度 ≈ 0.25，属于可见内容
        assertFalse(LocalThumbnailRules.isNearlyBlack(IntArray(16) { 0xFF404040.toInt() }))
        assertFalse(LocalThumbnailRules.isNearlyBlack(IntArray(16) { 0xFFFFFFFF.toInt() }))
        assertEquals(0.0, LocalThumbnailRules.meanLuma(IntArray(0)), 1e-9)
        // 阈值可调：同样的像素在更宽松的阈值下算黑
        assertTrue(
            LocalThumbnailRules.isNearlyBlack(IntArray(16) { 0xFF404040.toInt() }, maxLuma = 0.3)
        )
    }

    @Test
    fun `CBZ 页名自然序 p1 p2 p10 且大小写不敏感`() {
        assertEquals(
            listOf("cover/Intro.PNG", "p1.jpg", "p2.jpg", "p3.jpg", "p10.jpg"),
            LocalThumbnailRules.sortedComicPageNames(
                listOf("p10.jpg", "p2.jpg", "p1.jpg", "cover/Intro.PNG", "p3.jpg")
            ),
        )
        assertTrue(LocalThumbnailRules.compareComicPageNames("a.jpg", "A1.jpg") < 0)
        assertEquals(0, LocalThumbnailRules.compareComicPageNames("p01.jpg", "P1.JPG"))
        assertTrue(LocalThumbnailRules.compareComicPageNames("!cover.jpg", "01.jpg") < 0)
        assertEquals(emptyList<String>(), LocalThumbnailRules.sortedComicPageNames(emptyList()))
    }
}
