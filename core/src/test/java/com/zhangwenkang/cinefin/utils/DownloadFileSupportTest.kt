package com.zhangwenkang.cinefin.utils

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFileSupportTest {

    @Test
    fun `删除任务同时清理残片最终文件与 finalizing witness`() {
        val directory = Files.createTempDirectory("download-delete-completion").toFile()
        val partial = directory.resolve("media.download").apply { writeText("partial") }
        val finalFile = directory.resolve("media").apply { writeText("final") }
        val witness =
            DownloadCompletionCommitRules.witnessFile(partial, finalFile).apply {
                writeText("witness")
            }

        assertTrue(deleteCompletionArtifacts(partial.path))
        assertFalse(partial.exists())
        assertFalse(finalFile.exists())
        assertFalse(witness.exists())
        directory.deleteRecursively()
    }
}
