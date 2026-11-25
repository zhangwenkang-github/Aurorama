package com.zhangwenkang.cinefin.models

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W73（#10）「未知季」取集口径：服务端的未知季分组**不返回 SeasonId**，按季取集时用请求季 ID 回落， 否则整条集会被丢掉（真机表现 = 季详情没有集、播放键置灰）。 */
class EpisodeSeasonFallbackTest {
    private val serverSeasonId = UUID.fromString("2fc76b0e-3ed7-5775-0dad-426061f9471a")
    private val requestedSeasonId = UUID.fromString("c0ed3051-4548-b0f8-85f1-f83bebafe707")
    private val otherSeasonId = UUID.fromString("2d5c5f3a-3d84-4b7a-9f14-4a0a86b0c9f1")

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

    /** #21：服务器给的季与请求季一致 → 属于本次请求。 */
    @Test
    fun sameServerSeason_matchesRequested() {
        assertTrue(episodeMatchesRequestedSeason(serverSeasonId, serverSeasonId))
    }

    /** #21：服务器把别的季的集混进了本季返回 → 丢弃。 */
    @Test
    fun otherServerSeason_isRejected() {
        assertFalse(episodeMatchesRequestedSeason(otherSeasonId, requestedSeasonId))
    }

    /** #21 × W73：服务端没给季（未知季）→ 不判否，保留请求季回落。 */
    @Test
    fun nullServerSeason_keepsFallback() {
        assertTrue(episodeMatchesRequestedSeason(null, requestedSeasonId))
    }

    /** 不按季取（请求季为空）时没有判别依据，不丢条目。 */
    @Test
    fun nullRequestedSeason_keepsEpisode() {
        assertTrue(episodeMatchesRequestedSeason(serverSeasonId, null))
    }
}
