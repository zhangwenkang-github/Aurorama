package com.zhangwenkang.cinefin.models

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W73（#10）「未知季」取集口径：服务端的未知季分组**不返回 SeasonId**，按季取集时用请求季 ID 回落， 否则整条集会被丢掉（真机表现 = 季详情没有集、播放键置灰）。 */
class EpisodeSeasonFallbackTest {
    private val serverSeasonId = UUID.fromString("2fc76b0e-3ed7-5775-0dad-426061f9471a")
    private val requestedSeasonId = UUID.fromString("c0ed3051-4548-b0f8-85f1-f83bebafe707")

    @Test
    fun serverSeasonId_wins() {
        assertEquals(
            serverSeasonId,
            resolveEpisodeSeasonId(serverSeasonId, requestedSeasonId),
        )
    }

    @Test
    fun nullServerSeasonId_fallsBackToRequested() {
        assertEquals(requestedSeasonId, resolveEpisodeSeasonId(null, requestedSeasonId))
    }

    @Test
    fun bothMissing_returnsNull() {
        assertNull(resolveEpisodeSeasonId(null, null))
    }
}
