package com.zhangwenkang.cinefin.player.local.session

import com.zhangwenkang.cinefin.player.core.domain.models.PlaybackMediaIdentity
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerMediaInfo
import com.zhangwenkang.cinefin.player.local.domain.PlaybackRequestToken
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackSessionOwnerTest {

    private val itemId = UUID.fromString("00000000-0000-0000-0000-000000000043")
    private val identity = PlaybackMediaIdentity(itemId, "source-a")
    private var publishCount = 0

    private fun owner(): PlaybackSessionOwner {
        publishCount = 0
        return PlaybackSessionOwner(onSnapshotsChanged = { publishCount++ })
    }

    @Test
    fun `reset 后代次单调递增并清空当前令牌`() {
        val owner = owner()
        owner.beginMediaSnapshot(identity)

        val firstReset = owner.resetPlaybackSnapshots()
        val secondReset = owner.resetPlaybackSnapshots()

        assertEquals(2L, firstReset)
        assertEquals(3L, secondReset)
        assertEquals(3L, owner.playbackSnapshots.generation)
        assertNull(owner.playbackSnapshots.identity)
        assertNull(owner.activePlaybackToken)
        assertEquals(3, publishCount)
    }

    @Test
    fun `ensureMediaSnapshot 对同一 identity 复用令牌`() {
        val owner = owner()

        val first = owner.ensureMediaSnapshot(identity)
        val second = owner.ensureMediaSnapshot(identity)

        assertSame(first, second)
        assertEquals(first.generation, owner.playbackSnapshots.generation)
        assertEquals(1, publishCount)
    }

    @Test
    fun `ensureMediaSnapshot 换 media source 时重建令牌并递增代次`() {
        val owner = owner()
        val first = owner.ensureMediaSnapshot(identity)
        val other = PlaybackMediaIdentity(itemId, "source-b")

        val second = owner.ensureMediaSnapshot(other)

        assertNotEquals(first, second)
        assertEquals(other, second.identity)
        assertEquals(first.generation + 1L, second.generation)
        assertEquals(other, owner.playbackSnapshots.identity)
        assertEquals(2, publishCount)
    }

    @Test
    fun `ensureMediaSnapshot 在 reset 后重建令牌`() {
        val owner = owner()
        val first = owner.ensureMediaSnapshot(identity)
        owner.resetPlaybackSnapshots()

        val second = owner.ensureMediaSnapshot(identity)

        assertNotEquals(first, second)
        assertEquals(first.generation + 2L, second.generation)
        assertEquals(second, owner.activePlaybackToken)
    }

    @Test
    fun `advanceCurrentStreamGeneration 清空 current 并让旧令牌失效`() {
        val owner = owner()
        val token = owner.beginMediaSnapshot(identity)
        owner.applySourceSnapshot(token, PlayerMediaInfo(width = 3840, height = 2160))
        owner.applyCurrentStreamSnapshot(token, PlayerMediaInfo(width = 1920, height = 1080))

        val advanced = owner.advanceCurrentStreamGeneration()!!

        assertEquals(PlaybackRequestToken(identity, token.generation + 1L), advanced)
        assertEquals(3840, owner.playbackSnapshots.sourceMediaInfo?.width)
        assertNull(owner.playbackSnapshots.currentStreamMediaInfo)

        owner.applyCurrentStreamSnapshot(token, PlayerMediaInfo(width = 1280, height = 720))
        assertNull(owner.playbackSnapshots.currentStreamMediaInfo)

        owner.applyCurrentStreamSnapshot(advanced, PlayerMediaInfo(width = 1280, height = 720))
        assertEquals(1280, owner.playbackSnapshots.currentStreamMediaInfo?.width)
    }

    @Test
    fun `advanceCurrentStreamGeneration 在没有 identity 时不推进也不发布`() {
        val owner = owner()

        val advanced = owner.advanceCurrentStreamGeneration()

        assertNull(advanced)
        assertEquals(0L, owner.playbackSnapshots.generation)
        assertEquals(0, publishCount)
    }

    @Test
    fun `applySourceSnapshot 拒绝过期令牌但仍照旧发布一次`() {
        val owner = owner()
        val stale = owner.beginMediaSnapshot(identity)
        owner.resetPlaybackSnapshots()
        val before = owner.playbackSnapshots
        val publishesBefore = publishCount

        owner.applySourceSnapshot(stale, PlayerMediaInfo(width = 3840, height = 2160))

        assertSame(before, owner.playbackSnapshots)
        assertNull(owner.playbackSnapshots.sourceMediaInfo)
        assertEquals(publishesBefore + 1, publishCount)
    }

    @Test
    fun `applyCurrentStreamSnapshot 只在 identity 与代次均匹配时提交`() {
        val owner = owner()
        val token = owner.beginMediaSnapshot(identity)
        val wrongIdentity = token.copy(identity = PlaybackMediaIdentity(itemId, "source-b"))

        owner.applyCurrentStreamSnapshot(
            wrongIdentity,
            PlayerMediaInfo(width = 1920, height = 1080),
        )
        assertNull(owner.playbackSnapshots.currentStreamMediaInfo)

        owner.applyCurrentStreamSnapshot(token, PlayerMediaInfo(width = 1920, height = 1080))
        assertEquals(1920, owner.playbackSnapshots.currentStreamMediaInfo?.width)
    }

    @Test
    fun `源意图在起播与失败之间转移`() {
        val owner = owner()
        val first = sourceIntent("intent-1")
        val second = sourceIntent("intent-2")

        owner.startSourceAttempt(first)
        assertSame(first, owner.lastSourceIntent)
        assertNull(owner.failedSourceIntent)

        owner.failLastSourceIntent()
        assertSame(first, owner.failedSourceIntent)

        owner.startSourceAttempt(second)
        assertSame(second, owner.lastSourceIntent)
        assertNull(owner.failedSourceIntent)
    }

    private fun sourceIntent(id: String) =
        SourceResolutionIntent(
            itemId = itemId,
            itemKind = "MOVIE",
            startFromBeginning = false,
            startPositionMs = 0L,
            playbackSessionId = "session-$id",
            queueEntries = emptyList(),
            preferredMediaSourceId = null,
            sourceIntentId = id,
        )
}
