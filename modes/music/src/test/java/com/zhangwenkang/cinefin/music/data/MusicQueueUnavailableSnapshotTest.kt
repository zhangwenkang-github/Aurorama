package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.database.music.StoredQueue
import com.zhangwenkang.cinefin.database.music.StoredQueueItem
import com.zhangwenkang.cinefin.database.music.StoredQueueState
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import com.zhangwenkang.cinefin.player.core.domain.models.RepeatMode
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W5-T8（MUSIC-49）：队列快照新增「不可用」列的兼容性（旧快照缺字段按可播恢复）。 */
class MusicQueueUnavailableSnapshotTest {

    private val itemId = UUID.fromString("33333333-3333-4333-8333-333333333333")

    private fun state() =
        StoredQueueState(
            currentIndex = 0,
            positionMs = 0L,
            repeatMode = RepeatMode.OFF.name,
            shuffleEnabled = false,
            source = QueueSource.MANUAL.name,
            sourceId = null,
            savedAt = 0L,
        )

    /** 旧 v4 快照行没有 unavailable 字段：按默认值 false 恢复，不得改变队列结构。 */
    private fun legacyItem() =
        StoredQueueItem(
            position = 0,
            itemId = itemId.toString(),
            name = "缺失文件",
            mediaSourceId = "local",
            playbackPositionMs = 0L,
            mediaSourceUri = "content://media/external/audio/1",
            thumbnailUri = null,
            indexNumber = null,
        )

    @Test
    fun `旧快照缺不可用字段时按可播恢复`() {
        val restored = StoredQueue(items = listOf(legacyItem()), state = state()).toSnapshot()!!

        assertFalse(restored.queue.items.single().unavailable)
    }

    @Test
    fun `不可用标记写入快照并原样恢复`() {
        val stored =
            StoredQueue(
                items = listOf(legacyItem().copy(unavailable = true)),
                state = state(),
            )

        assertTrue(stored.items.single().unavailable)
        assertTrue(stored.toSnapshot()!!.queue.items.single().unavailable)
    }

    @Test
    fun `队列回写不可用标记`() {
        val queue =
            com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue(
                items =
                    listOf(
                        PlayerItem(
                            name = "缺失文件",
                            itemId = itemId,
                            mediaSourceId = "local",
                            playbackPosition = 0L,
                            mediaSourceUri = "content://media/external/audio/1",
                            unavailable = true,
                        )
                    ),
                source = QueueSource.MANUAL,
            )

        val stored = queue.toStored(positionMs = 0L, savedAt = 0L)

        assertTrue(stored.items.single().unavailable)
    }
}
