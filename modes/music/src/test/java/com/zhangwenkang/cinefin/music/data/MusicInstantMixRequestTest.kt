package com.zhangwenkang.cinefin.music.data

import java.util.UUID
import org.jellyfin.sdk.model.api.ItemFields
import org.junit.Assert.assertEquals
import org.junit.Test

/** W5-T12（MUSIC-14）：InstantMix 请求形状——itemId / userId / limit 透传与映射所需字段。 */
class MusicInstantMixRequestTest {

    private val seed = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val user = UUID.fromString("44444444-4444-4444-8444-444444444444")

    @Test
    fun `种子与批次大小原样透传`() {
        val request = instantMixRequest(itemId = seed, userId = user, limit = 10)

        assertEquals(seed, request.itemId)
        assertEquals(user, request.userId)
        assertEquals(10, request.limit ?: -1)
    }

    @Test
    fun `请求带上映射所需字段`() {
        val request = instantMixRequest(itemId = seed, userId = user, limit = 3)

        assertEquals(listOf(ItemFields.MEDIA_STREAMS), request.fields)
        assertEquals(true, request.enableImages)
        assertEquals(true, request.enableUserData)
    }
}
