package com.zhangwenkang.cinefin.presentation.film.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W73（#10）季的取图回落链（纯函数）：季自身图优先、缺省回落剧集图，两者都没有才是空 （服务器的「未知季」虚拟分组不生成任何图片）。 */
class ItemImageFallbackTest {
    @Test
    fun seasonPoster_prefersOwnPoster_thenShowPoster() {
        assertEquals("own", seasonPosterImage("own", "show"))
        assertEquals("show", seasonPosterImage(null, "show"))
        assertNull(seasonPosterImage(null, null))
    }

    @Test
    fun seasonBackdrop_ownBackdrop_thenShowBackdrop_thenPosters() {
        assertEquals("ownBd", seasonBackdropImage("ownBd", "showBd", "own", "show"))
        assertEquals("showBd", seasonBackdropImage(null, "showBd", "own", "show"))
        assertEquals("own", seasonBackdropImage(null, null, "own", "show"))
        assertEquals("show", seasonBackdropImage(null, null, null, "show"))
        assertNull(seasonBackdropImage(null, null, null, null))
    }
}
