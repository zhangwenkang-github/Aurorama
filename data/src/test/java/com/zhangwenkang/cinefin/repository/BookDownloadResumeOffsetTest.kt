package com.zhangwenkang.cinefin.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W7-T3（V2-DOWNLOAD-22）：暂停 / 恢复共用的续传偏移纯规则。 */
class BookDownloadResumeOffsetTest {

    @Test
    fun `有残片且有校验器时从残片末尾续传`() {
        assertEquals(
            50L * 1024L * 1024L,
            BookDownloadResumeRules.resumeOffset(
                partialBytes = 50L * 1024L * 1024L,
                resumeValidator = "\"etag\"",
            ),
        )
    }

    @Test
    fun `无残片或无校验器一律从零开始`() {
        assertEquals(0L, BookDownloadResumeRules.resumeOffset(partialBytes = 0L, "\"etag\""))
        assertEquals(0L, BookDownloadResumeRules.resumeOffset(partialBytes = 4096L, null))
        assertEquals(0L, BookDownloadResumeRules.resumeOffset(partialBytes = 4096L, "   "))
        assertEquals(
            "负长度（文件系统异常）不得当作续传点",
            0L,
            BookDownloadResumeRules.resumeOffset(partialBytes = -1L, "\"etag\""),
        )
    }

    @Test
    fun `Range 分支用残片偏移安全重下分支归零`() {
        assertEquals(1234L, BookDownloadResumeRules.attemptOffset(useRange = true, 1234L))
        assertEquals(0L, BookDownloadResumeRules.attemptOffset(useRange = false, 1234L))
        assertEquals(0L, BookDownloadResumeRules.attemptOffset(useRange = true, 0L))
    }

    @Test
    fun `416 只有总长已知且等于当前偏移才算残片已完整`() {
        assertTrue(BookDownloadResumeRules.unsatisfiedRangeCompletes(totalBytes = 900L, 900L))
        assertFalse(BookDownloadResumeRules.unsatisfiedRangeCompletes(totalBytes = 900L, 899L))
        assertFalse("未知总长不能判定完成", BookDownloadResumeRules.unsatisfiedRangeCompletes(null, 900L))
        assertFalse("0 总长不是有效完成态", BookDownloadResumeRules.unsatisfiedRangeCompletes(0L, 0L))
    }
}
