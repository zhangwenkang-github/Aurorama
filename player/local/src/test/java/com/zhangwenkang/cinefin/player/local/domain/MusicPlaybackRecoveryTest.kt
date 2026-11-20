package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W5-T8（MUSIC-48/49）：错误重试链、不可用跳过、缓冲口径（纯逻辑，不依赖播放器实例）。 */
class MusicPlaybackRecoveryTest {

    private val firstId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val secondId = UUID.fromString("22222222-2222-4222-8222-222222222222")

    private fun item(id: UUID, name: String, uri: String = "https://example.test/$name") =
        PlayerItem(
            name = name,
            itemId = id,
            mediaSourceId = name,
            playbackPosition = 0L,
            mediaSourceUri = uri,
        )

    @Test
    fun `首次错误重试 重试成功不再跳曲`() {
        val recovery = MusicPlaybackRecovery()

        assertEquals(
            MusicErrorRecoveryAction.RETRY,
            recovery.onError(itemId = firstId, hasNext = true),
        )
        // 重试成功后没有第二次错误；再出错才会进入跳曲分支
        assertEquals(
            MusicErrorRecoveryAction.SKIP_TO_NEXT,
            recovery.onError(itemId = firstId, hasNext = true),
        )
    }

    @Test
    fun `重试仍失败且有下一首 跳下一首`() {
        val recovery = MusicPlaybackRecovery()
        recovery.onError(itemId = firstId, hasNext = true)

        assertEquals(
            MusicErrorRecoveryAction.SKIP_TO_NEXT,
            recovery.onError(itemId = firstId, hasNext = true),
        )
    }

    @Test
    fun `队尾没有下一首时停止`() {
        val recovery = MusicPlaybackRecovery()
        recovery.onError(itemId = firstId, hasNext = false)

        assertEquals(
            MusicErrorRecoveryAction.STOP,
            recovery.onError(itemId = firstId, hasNext = false),
        )
    }

    @Test
    fun `同一曲目只重试一次 不循环重试`() {
        val recovery = MusicPlaybackRecovery()
        assertEquals(MusicErrorRecoveryAction.RETRY, recovery.onError(firstId, hasNext = true))
        repeat(5) {
            assertEquals(
                MusicErrorRecoveryAction.SKIP_TO_NEXT,
                recovery.onError(firstId, hasNext = true),
            )
        }
    }

    @Test
    fun `成功切到下一首后重置重试记忆`() {
        val recovery = MusicPlaybackRecovery()
        recovery.onError(itemId = firstId, hasNext = true)
        recovery.onItemChanged(secondId)

        assertEquals(
            MusicErrorRecoveryAction.RETRY,
            recovery.onError(itemId = secondId, hasNext = true),
        )
        // 再回到第一首仍是新的一轮，可以再重试一次
        recovery.onItemChanged(firstId)
        assertEquals(
            MusicErrorRecoveryAction.RETRY,
            recovery.onError(itemId = firstId, hasNext = true),
        )
    }

    @Test
    fun `没有曲目 id 时直接停止`() {
        val recovery = MusicPlaybackRecovery()
        assertEquals(MusicErrorRecoveryAction.STOP, recovery.onError(itemId = null, hasNext = true))
    }

    @Test
    fun `不可用条目自动跳过 取第一首可播`() {
        val items =
            listOf(
                item(firstId, "missing", uri = "content://media/1").copy(unavailable = true),
                item(secondId, "playable"),
            )

        assertEquals(1, firstPlayableQueueIndex(items, fromIndex = 0))
        assertEquals(1, firstPlayableQueueIndex(items, fromIndex = 1))
        assertNull(firstPlayableQueueIndex(items, fromIndex = 2))
        assertNull(firstPlayableQueueIndex(emptyList(), fromIndex = 0))
    }

    @Test
    fun `本地 content 源文件缺失或无权限才算不可用`() {
        assertTrue(
            isLocalSourceUnavailableError(
                "content://media/external/audio/1",
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            )
        )
        assertTrue(
            isLocalSourceUnavailableError(
                "content://media/external/audio/1",
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            )
        )
        // 网络源同类错误仍应走重试链
        assertFalse(
            isLocalSourceUnavailableError(
                "https://example.test/a.mp3",
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            )
        )
        // 本地源的其他 IO 错误（如网络抖动）不判定为文件缺失
        assertFalse(
            isLocalSourceUnavailableError(
                "content://media/external/audio/1",
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            )
        )
        assertFalse(
            isLocalSourceUnavailableError(null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        )
    }

    @Test
    fun `缓冲中 保留播放意图`() {
        assertTrue(isMusicBuffering(Player.STATE_BUFFERING, playWhenReady = true))
        assertFalse(isMusicBuffering(Player.STATE_BUFFERING, playWhenReady = false))
        assertFalse(isMusicBuffering(Player.STATE_READY, playWhenReady = true))
        assertFalse(isMusicBuffering(Player.STATE_IDLE, playWhenReady = true))
    }
}
