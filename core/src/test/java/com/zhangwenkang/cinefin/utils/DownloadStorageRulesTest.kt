package com.zhangwenkang.cinefin.utils

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W76-B9 单测：首次下载（新装 / 清数据）时目标父目录 `…/files/downloads` 尚不存在， `StatFs(不存在路径)` 会抛
 * `IllegalArgumentException` → 任务在 HTTP 之前以 UNKNOWN 失败。 规则负责先建父目录、必要时回落 `filesDir`，保证交给 `StatFs`
 * 的路径总是可用目录。
 */
class DownloadStorageRulesTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    @Test
    fun `首次下载·目标目录不存在时创建父目录并返回该目录`() {
        val root = tempDir("cinefin-dl-root")
        val fallback = tempDir("cinefin-dl-fallback")
        val downloads = File(root, "downloads")
        assertFalse("前置条件：downloads 目录不应存在", downloads.exists())
        val target = File(downloads, "movie.mkv")

        val resolved = DownloadStorageRules.resolveStatPath(target, fallback)

        assertEquals(downloads, resolved)
        assertTrue("应已创建父目录", resolved.isDirectory)
    }

    @Test
    fun `父目录已存在时原样返回`() {
        val root = tempDir("cinefin-dl-existing")
        val fallback = tempDir("cinefin-dl-fallback2")
        val downloads = File(root, "downloads").apply { mkdirs() }

        val resolved = DownloadStorageRules.resolveStatPath(File(downloads, "movie.mkv"), fallback)

        assertEquals(downloads, resolved)
    }

    @Test
    fun `目标无父目录时回落 fallbackDir`() {
        val fallback = tempDir("cinefin-dl-fallback3")

        val resolved = DownloadStorageRules.resolveStatPath(File("movie.mkv"), fallback)

        assertEquals(fallback, resolved)
    }

    @Test
    fun `父路径被普通文件占据无法建目录时回落 fallbackDir`() {
        val root = tempDir("cinefin-dl-blocked")
        val fallback = tempDir("cinefin-dl-fallback4")
        val blocker = File(root, "downloads").apply { writeText("not a dir") }

        val resolved = DownloadStorageRules.resolveStatPath(File(blocker, "movie.mkv"), fallback)

        assertEquals(fallback, resolved)
        assertTrue(resolved.isDirectory)
    }
}
