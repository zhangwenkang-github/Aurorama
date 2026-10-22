package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.player.core.domain.models.PlaybackMediaIdentity
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerMediaInfo
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackSnapshotStateTest {

    private val itemId = UUID.fromString("00000000-0000-0000-0000-000000000043")

    @Test
    fun `source A 到 B 时清空旧快照且 generation 单调递增`() {
        val sourceA = PlaybackMediaIdentity(itemId, "source-a")
        val sourceB = PlaybackMediaIdentity(itemId, "source-b")
        val initial =
            PlaybackSnapshotState(
                identity = sourceA,
                generation = 7,
                sourceMediaInfo = PlayerMediaInfo(width = 3840, height = 2160),
                currentStreamMediaInfo = PlayerMediaInfo(width = 1920, height = 1080),
            )

        val (snapshot, token) = beginPlaybackRequest(initial, sourceB)

        assertEquals(sourceB, snapshot.identity)
        assertEquals(8, snapshot.generation)
        assertEquals(token, PlaybackRequestToken(sourceB, 8))
        assertNull(snapshot.sourceMediaInfo)
        assertNull(snapshot.currentStreamMediaInfo)
    }

    @Test
    fun `同一条目重复请求也递增 generation 且旧结果晚到被丢弃`() {
        val identity = PlaybackMediaIdentity(itemId, "source-a")
        val (firstState, firstToken) = beginPlaybackRequest(PlaybackSnapshotState(), identity)
        val withSource =
            bindSourceSnapshot(firstState, firstToken, PlayerMediaInfo(width = 3840, height = 2160))
        val (secondState, secondToken) = beginPlaybackRequest(withSource, identity)
        val newer =
            bindSourceSnapshot(
                secondState,
                secondToken,
                PlayerMediaInfo(width = 1280, height = 720),
            )

        val staleCurrent =
            acceptCurrentStreamSnapshot(
                newer,
                firstToken,
                PlayerMediaInfo(width = 3840, height = 2160),
            )
        val staleSource =
            bindSourceSnapshot(newer, firstToken, PlayerMediaInfo(width = 640, height = 360))

        assertSame(newer, staleCurrent)
        assertSame(newer, staleSource)
        assertEquals(1280, newer.sourceMediaInfo?.width)
    }

    @Test
    fun `新 current 结果只在 identity 与 generation 均匹配时提交`() {
        val identity = PlaybackMediaIdentity(itemId, "source-a")
        val (state, token) = beginPlaybackRequest(PlaybackSnapshotState(), identity)
        val wrongIdentity = token.copy(identity = PlaybackMediaIdentity(itemId, "source-b"))

        assertEquals(
            state,
            acceptCurrentStreamSnapshot(
                state,
                wrongIdentity,
                PlayerMediaInfo(width = 1920, height = 1080),
            ),
        )
        val accepted =
            acceptCurrentStreamSnapshot(
                state,
                token,
                PlayerMediaInfo(width = 1920, height = 1080),
            )
        assertEquals(1920, accepted.currentStreamMediaInfo?.width)
    }

    @Test
    fun `初始化前的空轨道回调不推进 generation`() {
        val initializing = PlaybackSnapshotState(generation = 9)

        val (state, token) = advanceCurrentStreamRequest(initializing)

        assertSame(initializing, state)
        assertNull(token)
        assertEquals(9, state.generation)
    }

    @Test
    fun `已有 identity 的轨道变化保留 source 并清空 current`() {
        val identity = PlaybackMediaIdentity(itemId, "source-a")
        val state =
            PlaybackSnapshotState(
                identity = identity,
                generation = 9,
                sourceMediaInfo = PlayerMediaInfo(width = 3840, height = 2160),
                currentStreamMediaInfo = PlayerMediaInfo(width = 1920, height = 1080),
            )

        val (advanced, token) = advanceCurrentStreamRequest(state)

        assertEquals(PlaybackRequestToken(identity, 10), token)
        assertEquals(10, advanced.generation)
        assertEquals(3840, advanced.sourceMediaInfo?.width)
        assertNull(advanced.currentStreamMediaInfo)
    }
}
