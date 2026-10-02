package com.zhangwenkang.cinefin.local

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W37 本地媒体库纯函数单测：扩展名白名单 / 类型过滤 / 分组 / 层级与平铺判定。 */
class LocalLibraryPureTest {

    @Test
    fun `扩展名白名单大小写不敏感且未知扩展名被拒绝`() {
        assertEquals(LocalMediaKind.VIDEO, LocalMediaExtensions.kindOf("Movie.MKV"))
        assertEquals(LocalMediaKind.VIDEO, LocalMediaExtensions.kindOf("a.m2ts"))
        assertEquals(LocalMediaKind.MUSIC, LocalMediaExtensions.kindOf("song.FLAC"))
        assertEquals(LocalMediaKind.BOOK, LocalMediaExtensions.kindOf("书.CBZ"))
        assertNull(LocalMediaExtensions.kindOf("readme.txt"))
        assertNull(LocalMediaExtensions.kindOf("无扩展名"))
        assertNull(LocalMediaExtensions.kindOf(""))
    }

    @Test
    fun `类型库只收录所属类型_混合库全收`() {
        assertTrue(LocalMediaExtensions.allowedIn(LocalLibraryType.VIDEO, LocalMediaKind.VIDEO))
        assertTrue(!LocalMediaExtensions.allowedIn(LocalLibraryType.VIDEO, LocalMediaKind.MUSIC))
        assertTrue(!LocalMediaExtensions.allowedIn(LocalLibraryType.BOOK, LocalMediaKind.VIDEO))
        for (kind in LocalMediaKind.entries) {
            assertTrue(LocalMediaExtensions.allowedIn(LocalLibraryType.MIXED, kind))
        }
    }

    @Test
    fun `确定性 itemId 与文档 URI 绑定且稳定`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMedia/a.mkv"
        val first = localItemIdFor(uri)
        val second = localItemIdFor(uri)
        assertEquals(first, second)
        assertNotEquals(first, localItemIdFor("$uri.bak"))
        assertEquals(UUID.nameUUIDFromBytes(uri.toByteArray(Charsets.UTF_8)), first)
    }

    @Test
    fun `混合库按文件类型分组_单一类型库单段`() {
        val entries =
            listOf(
                entry("m.mp3", LocalMediaKind.MUSIC),
                entry("v.mp4", LocalMediaKind.VIDEO),
                entry("b.pdf", LocalMediaKind.BOOK),
            )
        val mixed = LocalLibraryGrouping.sections(entries, LocalLibraryType.MIXED)
        assertEquals(
            listOf(LocalMediaKind.VIDEO, LocalMediaKind.MUSIC, LocalMediaKind.BOOK),
            mixed.map { it.kind },
        )
        assertEquals(listOf("视频", "音乐", "书籍"), mixed.map { it.title })
        assertEquals(1, mixed[0].entries.size)

        val single = LocalLibraryGrouping.sections(entries, LocalLibraryType.VIDEO)
        assertEquals(1, single.size)
        assertNull(single[0].kind)
        assertEquals("视频", single[0].title)
        assertTrue(LocalLibraryGrouping.sections(emptyList(), LocalLibraryType.MIXED).isEmpty())
    }

    @Test
    fun `平铺模式忽略目录层级_按路径排序`() {
        val entries =
            listOf(
                entry("b/第二.mp4", LocalMediaKind.VIDEO),
                entry("a/第一.mp4", LocalMediaKind.VIDEO),
                entry("根.mp4", LocalMediaKind.VIDEO),
            )
        val rows = LocalLibraryBrowse.rows(entries, LocalFolderBrowseMode.FLAT)
        assertEquals(3, rows.size)
        assertEquals(listOf(0, 0, 0), rows.map { it.depth })
        // 按相对路径排序：ASCII 目录名在前，中文根文件在后。
        assertEquals(
            listOf("第一.mp4", "第二.mp4", "根.mp4"),
            rows.map { (it as LocalLibraryBrowse.Row.Entry).entry.name },
        )
    }

    @Test
    fun `层级模式先根文件再文件夹_深度与后代计数正确`() {
        val entries =
            listOf(
                entry("根.mp4", LocalMediaKind.VIDEO),
                entry("影夹/一.mp4", LocalMediaKind.VIDEO),
                entry("影夹/子夹/二.mp4", LocalMediaKind.VIDEO),
                entry("影夹/三.mp4", LocalMediaKind.VIDEO),
            )
        val rows = LocalLibraryBrowse.rows(entries, LocalFolderBrowseMode.HIERARCHY)
        val rootEntry = rows[0] as LocalLibraryBrowse.Row.Entry
        assertEquals("根.mp4", rootEntry.entry.name)
        assertEquals(0, rootEntry.depth)

        val folder = rows[1] as LocalLibraryBrowse.Row.Folder
        assertEquals("影夹", folder.title)
        assertEquals(0, folder.depth)
        assertEquals(3, folder.fileCount)

        val direct = rows.filterIsInstance<LocalLibraryBrowse.Row.Entry>().drop(1)
        assertEquals(listOf("一.mp4", "三.mp4"), direct.take(2).map { it.entry.name })
        assertEquals(1, direct[0].depth)
        assertEquals(1, direct[1].depth)

        val sub = rows[4] as LocalLibraryBrowse.Row.Folder
        assertEquals("子夹", sub.title)
        assertEquals(1, sub.depth)
        assertEquals(1, sub.fileCount)
        assertEquals(2, (rows[5] as LocalLibraryBrowse.Row.Entry).depth)
    }

    @Test
    fun `显示名优先标签标题_空白回退文件名`() {
        assertEquals("标签标题", entry("a.flac", LocalMediaKind.MUSIC, title = "标签标题").displayName)
        assertEquals("a.flac", entry("a.flac", LocalMediaKind.MUSIC, title = "  ").displayName)
        assertEquals("影夹", entry("影夹/一.mp4", LocalMediaKind.VIDEO).let { it.parentPath })
        assertEquals("", entry("根.mp4", LocalMediaKind.VIDEO).parentPath)
    }

    @Test
    fun `浏览模式解析容错回退层级`() {
        assertEquals(LocalFolderBrowseMode.FLAT, LocalFolderBrowseMode.fromName("FLAT"))
        assertEquals(LocalFolderBrowseMode.HIERARCHY, LocalFolderBrowseMode.fromName("UNKNOWN"))
        assertEquals(LocalFolderBrowseMode.HIERARCHY, LocalFolderBrowseMode.fromName(null))
        assertEquals(LocalLibraryType.VIDEO, LocalLibraryType.fromName("VIDEO"))
        assertEquals(LocalLibraryType.MIXED, LocalLibraryType.fromName(null))
    }

    private fun entry(
        relativePath: String,
        kind: LocalMediaKind,
        title: String? = null,
    ): LocalLibraryEntry {
        val name = relativePath.substringAfterLast('/')
        return LocalLibraryEntry(
            itemId = localItemIdFor("content://test/$relativePath"),
            folderId = 1L,
            name = name,
            kind = kind,
            documentUri = "content://test/$relativePath",
            relativePath = relativePath,
            sizeBytes = 1024,
            lastModified = 0L,
            title = title,
        )
    }
}
