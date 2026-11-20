package com.zhangwenkang.cinefin.player.local.mpv

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W5-T10 L7：content:// 字幕临时副本的命名——保留扩展名、去掉非法字符、不同 URI 不互撞。 */
class MpvContentSubtitleResolverTest {

    @Test
    fun `保留原扩展名`() {
        val name =
            mpvLocalSubtitleCopyName(
                "content://com.android.externalstorage.documents/document/primary%3AMovies%2Fx.mkv",
                "Demo.zh.srt",
            )
        assertTrue(name.endsWith("-Demo.zh.srt"))
    }

    @Test
    fun `没有显示名时回退到 URI 尾段`() {
        val name = mpvLocalSubtitleCopyName("content://docs/tree/a/b/Demo.ass", displayName = null)
        assertTrue(name.endsWith("-Demo.ass"))
    }

    @Test
    fun `非法文件名字符被替换`() {
        val name = mpvLocalSubtitleCopyName("content://docs/1", "a:b*c?.srt")
        assertTrue(name.endsWith("-a_b_c_.srt"))
        assertTrue(name.none { it in ":*?\\/\"<>|" })
    }

    @Test
    fun `不同 URI 同名文件不互撞`() {
        val first = mpvLocalSubtitleCopyName("content://docs/a/Demo.srt", "Demo.srt")
        val second = mpvLocalSubtitleCopyName("content://docs/b/Demo.srt", "Demo.srt")
        assertNotEquals(first, second)
    }

    @Test
    fun `都取不到名字时仍有默认扩展名`() {
        val name = mpvLocalSubtitleCopyName("content://", null)
        assertTrue(name.endsWith("-subtitle.srt"))
    }
}
