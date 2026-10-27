package com.zhangwenkang.cinefin.presentation.film.downloads

import com.zhangwenkang.cinefin.core.R
import com.zhangwenkang.cinefin.utils.DownloadControlPlaneRules
import com.zhangwenkang.cinefin.utils.DownloadIntentPhase
import com.zhangwenkang.cinefin.utils.DownloadTaskPhase
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DownloadIntentPhaseProjectionTest {

    @Test
    fun `queued resolving and source pending have three distinct user labels`() {
        val phases =
            listOf(
                    DownloadIntentPhase.QUEUED,
                    DownloadIntentPhase.RESOLVING,
                    DownloadIntentPhase.SOURCE_PENDING,
                )
                .map { intentPhase ->
                    DownloadControlPlaneRules.phase(
                        status = DownloadTaskStatus.PENDING,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        failureReason = null,
                        nextRetryAt = 0L,
                        nowMs = 0L,
                        intentPhase = intentPhase,
                    )
                }

        assertEquals(
            listOf(
                DownloadTaskPhase.QUEUED,
                DownloadTaskPhase.RESOLVING,
                DownloadTaskPhase.SOURCE_PENDING,
            ),
            phases,
        )
        val labels = phases.map(::downloadIntentPhaseLabel)
        assertEquals(R.string.download_intent_queued, labels[0])
        assertEquals(R.string.download_intent_resolving, labels[1])
        assertEquals(R.string.download_intent_source_pending, labels[2])
        assertNotEquals(labels[0], labels[1])
        assertNotEquals(labels[1], labels[2])
        assertNotEquals(labels[0], labels[2])
    }
}
