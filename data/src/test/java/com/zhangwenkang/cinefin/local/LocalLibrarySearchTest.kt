package com.zhangwenkang.cinefin.local

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W43 本地搜索匹配纯函数单测：归一化 / 大小写 / 扩展名 / 标签标题 / 排序 / 大库耗时。 */
class LocalLibrarySearchTest {

    @Test
    fun `文件名匹配对大小写与首尾空白不敏感`() {
        val entry = entry("TONE_A.WAV", LocalMediaKind.MUSIC)
        assertTrue(LocalLibrarySearch.matches(entry, " tone_a "))
        assertTrue(LocalLibrarySearch.matches(entry, "TONE_A.WAV"))
        assertEquals(listOf(entry), LocalLibrarySearch.filter(listOf(entry), " Tone_A "))
    }

    @Test
    fun `扩展名参与匹配且大小写不敏感`() {
        val entry = entry("Movie.MKV", LocalMediaKind.VIDEO)
        assertTrue(LocalLibrarySearch.matches(entry, "movie.mkv"))
        assertTrue(LocalLibrarySearch.matches(entry, "mkv"))
        assertFalse(LocalLibrarySearch.matches(entry, "avi"))
    }

    @Test
    fun `音乐内嵌标签标题也参与匹配`() {
        val entry = entry("02-track.flac", LocalMediaKind.MUSIC, title = "旅行的意义")
        assertTrue(LocalLibrarySearch.matches(entry, "旅行"))
        assertTrue(LocalLibrarySearch.matches(entry, "02-track"))
        assertFalse(LocalLibrarySearch.matches(entry, "意义非凡"))
    }

    @Test
    fun `空白标题不参与匹配且空查询返回空结果`() {
        val entries = listOf(entry("book.pdf", LocalMediaKind.BOOK, title = "   "))
        assertFalse(LocalLibrarySearch.matches(entries[0], "   "))
        assertEquals(emptyList<LocalLibraryEntry>(), LocalLibrarySearch.filter(entries, ""))
        assertEquals(emptyList<LocalLibraryEntry>(), LocalLibrarySearch.filter(entries, "   "))
        assertEquals(emptyList<LocalLibraryEntry>(), LocalLibrarySearch.filter(entries, "zzz"))
    }

    @Test
    fun `结果按类型与文件名稳定排序`() {
        val entries =
            listOf(
                entry("gamma.pdf", LocalMediaKind.BOOK),
                entry("beta.mp3", LocalMediaKind.MUSIC),
                entry("delta.mp4", LocalMediaKind.VIDEO),
                entry("alpha.mp4", LocalMediaKind.VIDEO),
            )
        val hits = LocalLibrarySearch.filter(entries, "a")
        assertEquals(
            listOf("alpha.mp4", "delta.mp4", "beta.mp3", "gamma.pdf"),
            hits.map { it.name },
        )
    }

    @Test
    fun `五千条大库过滤耗时在预算内`() {
        val entries =
            (1..5000).map { index ->
                entry(
                    name = "track_%04d.flac".format(index),
                    kind = LocalMediaKind.MUSIC,
                    title = if (index % 100 == 0) "专辑曲目 $index" else null,
                    folderId = (index % 3).toLong(),
                )
            }
        // 预热一次，避免 JIT 未编译的首次开销参与计时。
        LocalLibrarySearch.filter(entries, "track_0001")
        val startedAt = System.nanoTime()
        val hits = LocalLibrarySearch.filter(entries, "track_4999")
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
        println("W43 本地搜索：5000 条内存过滤耗时 %.2f ms（命中 %d 条）".format(elapsedMs, hits.size))
        assertEquals(1, hits.size)
        assertTrue("5000 条过滤应远快于 250ms 预算（实测 %.2f ms）".format(elapsedMs), elapsedMs < 250.0)
    }

    private fun entry(
        name: String,
        kind: LocalMediaKind,
        title: String? = null,
        folderId: Long = 1L,
    ) =
        LocalLibraryEntry(
            itemId = UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)),
            folderId = folderId,
            name = name,
            kind = kind,
            documentUri = "content://test/$name",
            relativePath = name,
            sizeBytes = 1024L,
            lastModified = 0L,
            title = title,
        )
}
