package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.player.core.domain.models.PlayerMediaInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerMediaInfoSectionsTest {

    @Test
    fun `current 缺少宽度时显示未知且不借用 source 的 4K`() {
        val sections =
            playerMediaInfoSections(
                source = PlayerMediaInfo(width = 3840, height = 2160),
                current = PlayerMediaInfo(height = 2160),
            )

        assertEquals("3840 × 2160", sections.sourceResolution)
        assertEquals(MEDIA_INFO_UNKNOWN, sections.currentResolution)
    }

    @Test
    fun `当前流尚未就绪时保持未知`() {
        val sections =
            playerMediaInfoSections(
                source = PlayerMediaInfo(width = 3840, height = 2160),
                current = null,
            )

        assertEquals(MEDIA_INFO_UNKNOWN, sections.currentResolution)
        assertEquals(MEDIA_INFO_UNKNOWN, sections.currentCodec)
    }
}
