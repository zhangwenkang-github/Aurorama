package com.zhangwenkang.cinefin.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** W80b：自定义 HTTP 调用的统一鉴权头构造（服务器只接受 `Authorization: MediaBrowser Token="…"`）。 */
class JellyfinAuthTest {

    @Test
    fun `头值为带引号的 MediaBrowser Token 形态`() {
        assertEquals("MediaBrowser Token=\"abc123\"", JellyfinAuth.headerValue("abc123"))
        assertEquals("Authorization", JellyfinAuth.HEADER)
        assertEquals("X-Emby-Token", JellyfinAuth.LEGACY_HEADER)
    }

    @Test
    fun `单值头表只在有令牌时给头`() {
        assertEquals(
            mapOf("Authorization" to "MediaBrowser Token=\"t\""),
            JellyfinAuth.headers("t"),
        )
        assertTrue(JellyfinAuth.headers(null).isEmpty())
        assertTrue(JellyfinAuth.headers("   ").isEmpty())
    }

    @Test
    fun `多值头表与单值同源`() {
        assertEquals(
            mapOf("Authorization" to listOf("MediaBrowser Token=\"t\"")),
            JellyfinAuth.headerMap("t"),
        )
        assertTrue(JellyfinAuth.headerMap(null).isEmpty())
    }

    @Test
    fun `并入头表时保留其它头`() {
        val original =
            mapOf(
                "Range" to listOf("bytes=0-1023"),
                "Accept" to listOf("*/*"),
            )
        val merged = JellyfinAuth.merge(original, "abc123")
        assertEquals(listOf("bytes=0-1023"), merged["Range"])
        assertEquals(listOf("*/*"), merged["Accept"])
        assertEquals(listOf("MediaBrowser Token=\"abc123\""), merged[JellyfinAuth.HEADER])
        assertFalse(original.containsKey(JellyfinAuth.HEADER))
    }

    @Test
    fun `并入头表时大小写不敏感地替换同名头并清掉历史头`() {
        val original =
            mapOf(
                "authorization" to listOf("stale"),
                "x-emby-token" to listOf("legacy"),
            )
        val merged = JellyfinAuth.merge(original, "new")
        assertEquals(1, merged.size)
        assertEquals(listOf("MediaBrowser Token=\"new\""), merged[JellyfinAuth.HEADER])
    }

    @Test
    fun `没有令牌时原表原样返回`() {
        val original = mapOf("Range" to listOf("bytes=0-"))
        assertSame(original, JellyfinAuth.merge(original, null))
        assertSame(original, JellyfinAuth.merge(original, "  "))
    }
}
