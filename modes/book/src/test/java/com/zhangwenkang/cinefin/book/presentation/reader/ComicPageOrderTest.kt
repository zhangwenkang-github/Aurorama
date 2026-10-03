package com.zhangwenkang.cinefin.book.presentation.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ComicPageOrderTest {

    @Test
    fun `过滤目录 隐藏文件 与元数据条目`() {
        val names =
            listOf(
                "Fonts/",
                "Fonts/caveatbrush-regular.ttf",
                "AndasGame.acbf",
                "ComicInfo.xml",
                "Thumbs.db",
                "__MACOSX/cover.jpg",
                ".hidden.jpg",
                "01.jpg",
                "02.png",
            )
        assertEquals(listOf("01.jpg", "02.png"), orderComicPageNames(names))
    }

    @Test
    fun `自然序把 2 排在 10 前面`() {
        val names = listOf("10.jpg", "2.jpg", "1.jpg", "11.jpg", "3.jpg")
        assertEquals(
            listOf("1.jpg", "2.jpg", "3.jpg", "10.jpg", "11.jpg"),
            orderComicPageNames(names),
        )
    }

    @Test
    fun `封面感叹号排序在前且大小写不敏感`() {
        assertEquals(
            listOf("!cover.jpg", "Page1.JPG", "page2.jpg"),
            orderComicPageNames(listOf("page2.jpg", "Page1.JPG", "!cover.jpg")),
        )
    }

    @Test
    fun `Anda's Game 结构只把 24 张图当页`() {
        val names =
            listOf(
                "!cover.jpg",
                "AndasGame.acbf",
                "ComicInfo.xml",
                "Fonts/",
                "Fonts/caveatbrush-regular.ttf",
                "Fonts/FreeMonoBold.otf",
                "Fonts/SIL Open Font License-caveat.txt",
            ) + (1..23).map { "%02d.jpg".format(it) }
        val pages = orderComicPageNames(names)
        assertEquals(24, pages.size)
        assertEquals("!cover.jpg", pages.first())
        assertEquals("23.jpg", pages.last())
        assertFalse(pages.any { it.endsWith(".otf") || it.endsWith(".txt") })
    }
}
