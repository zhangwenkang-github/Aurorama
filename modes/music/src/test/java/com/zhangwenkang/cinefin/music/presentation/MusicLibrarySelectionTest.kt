package com.zhangwenkang.cinefin.music.presentation

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W53 Bug B1：音乐库解析优先级 = 路由参数 > 客户端设置「音乐库」 > 自动（null）。 */
class MusicLibrarySelectionTest {
    private val routeLibrary = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val preferredLibrary = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @Test
    fun routeLibraryWinsOverPreference() {
        assertEquals(
            routeLibrary,
            resolveMusicLibraryId(
                routeLibraryId = routeLibrary.toString(),
                preferredLibraryId = preferredLibrary.toString(),
            ),
        )
    }

    @Test
    fun fallsBackToPreferenceWhenRouteIsMissingOrInvalid() {
        assertEquals(
            preferredLibrary,
            resolveMusicLibraryId(
                routeLibraryId = null,
                preferredLibraryId = preferredLibrary.toString(),
            ),
        )
        assertEquals(
            preferredLibrary,
            resolveMusicLibraryId(
                routeLibraryId = "  ",
                preferredLibraryId = preferredLibrary.toString(),
            ),
        )
        assertEquals(
            preferredLibrary,
            resolveMusicLibraryId(
                routeLibraryId = "not-a-uuid",
                preferredLibraryId = preferredLibrary.toString(),
            ),
        )
    }

    @Test
    fun automaticWhenNeitherIsSet() {
        assertNull(resolveMusicLibraryId(routeLibraryId = null, preferredLibraryId = null))
        assertNull(resolveMusicLibraryId(routeLibraryId = "", preferredLibraryId = ""))
    }
}
