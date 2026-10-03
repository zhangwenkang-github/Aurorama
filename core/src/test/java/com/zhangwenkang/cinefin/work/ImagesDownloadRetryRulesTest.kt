package com.zhangwenkang.cinefin.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W60 图片缓存 worker 失败重试口径单测（瞬时失败限次重试；永久失败 / 成功不重试）。 */
class ImagesDownloadRetryRulesTest {

    @Test
    fun `瞬时失败且还有剩余尝试时重试`() {
        assertTrue(
            ImagesDownloadRetryRules.shouldRetry(hadTransientFailure = true, runAttemptCount = 0)
        )
        assertTrue(
            ImagesDownloadRetryRules.shouldRetry(hadTransientFailure = true, runAttemptCount = 1)
        )
    }

    @Test
    fun `瞬时失败达到上限后不再重试`() {
        assertFalse(
            ImagesDownloadRetryRules.shouldRetry(
                hadTransientFailure = true,
                runAttemptCount = ImagesDownloadRetryRules.MAX_ATTEMPTS - 1,
            )
        )
        assertFalse(
            ImagesDownloadRetryRules.shouldRetry(
                hadTransientFailure = true,
                runAttemptCount = ImagesDownloadRetryRules.MAX_ATTEMPTS,
            )
        )
    }

    @Test
    fun `没有瞬时失败时成功结束不重试`() {
        assertFalse(
            ImagesDownloadRetryRules.shouldRetry(hadTransientFailure = false, runAttemptCount = 0)
        )
    }

    @Test
    fun `HTTP 状态分类：5xx 与 408 429 瞬时，4xx 永久`() {
        assertTrue(ImagesDownloadRetryRules.isTransientHttpFailure(500))
        assertTrue(ImagesDownloadRetryRules.isTransientHttpFailure(503))
        assertTrue(ImagesDownloadRetryRules.isTransientHttpFailure(408))
        assertTrue(ImagesDownloadRetryRules.isTransientHttpFailure(429))
        assertFalse(ImagesDownloadRetryRules.isTransientHttpFailure(404))
        assertFalse(ImagesDownloadRetryRules.isTransientHttpFailure(403))
    }
}
