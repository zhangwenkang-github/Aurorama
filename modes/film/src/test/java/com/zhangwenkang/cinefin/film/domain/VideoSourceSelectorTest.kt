package com.zhangwenkang.cinefin.film.domain

import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoSourceSelectorTest {

    @Test
    fun `优先使用 preferred mediaSourceId`() {
        val sources =
            listOf(
                source("remote", FindroidSourceType.REMOTE),
                source("local", FindroidSourceType.LOCAL),
                source("preferred", FindroidSourceType.REMOTE),
            )

        assertEquals("preferred", selectSourceForMetadata(sources, "preferred")?.id)
    }

    @Test
    fun `缺失 preferred 时回落到本地源与播放器一致`() {
        val sources =
            listOf(
                source("remote", FindroidSourceType.REMOTE),
                source("local", FindroidSourceType.LOCAL),
            )

        assertEquals("local", selectSourceForMetadata(sources, "missing")?.id)
        assertEquals("local", selectSourceForMetadata(sources, null)?.id)
    }

    @Test
    fun `没有本地源时使用首个源且空列表安全`() {
        val sources =
            listOf(
                source("remote-a", FindroidSourceType.REMOTE),
                source("remote-b", FindroidSourceType.REMOTE),
            )

        assertEquals("remote-a", selectSourceForMetadata(sources, null)?.id)
        assertNull(selectSourceForMetadata(emptyList(), null))
    }

    private fun source(id: String, type: FindroidSourceType) =
        FindroidSource(
            id = id,
            name = "$id.mkv",
            type = type,
            path = "",
            size = 0L,
            mediaStreams = emptyList(),
        )
}
