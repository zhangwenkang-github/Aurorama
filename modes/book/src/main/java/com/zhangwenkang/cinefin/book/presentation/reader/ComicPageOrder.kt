package com.zhangwenkang.cinefin.book.presentation.reader

import java.util.Locale

/**
 * CBZ 中可作为漫画页的位图扩展名（与 Readium `ArchiveSniffer` 的 cbzExtensions 对齐）。
 *
 * 字体 / `acbf` / `xml` / 说明文本都不是页面，一律排除；否则会像 Readium 那样把整包判定为 "非漫画包"（READER_PLAN §8 踩坑 15）。
 */
private val COMIC_IMAGE_EXTENSIONS =
    setOf(
        "bmp",
        "dib",
        "gif",
        "jif",
        "jfi",
        "jfif",
        "jpg",
        "jpeg",
        "png",
        "tif",
        "tiff",
        "webp",
    )

/** 目录 / 隐藏文件 / 元数据条目都不是页面。 */
internal fun isComicPageEntry(name: String): Boolean {
    if (name.isEmpty() || name.endsWith("/")) return false
    val segments = name.split('/')
    if (segments.any { it.startsWith(".") || it == "__MACOSX" }) return false
    val fileName = segments.last()
    if (fileName.equals("Thumbs.db", ignoreCase = true)) return false
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in COMIC_IMAGE_EXTENSIONS
}

/**
 * 自然序比较：把连续数字当数值比较，`2.jpg` 排在 `10.jpg` 之前（普通字符串序会反过来）。
 *
 * 大小写不敏感；后缀用于保证包含关系的稳定排序（`a.jpg` < `a1.jpg`）。
 */
internal fun compareComicPageNames(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var iEnd = i
            while (iEnd < a.length && a[iEnd].isDigit()) iEnd++
            var jEnd = j
            while (jEnd < b.length && b[jEnd].isDigit()) jEnd++
            val numberA = a.substring(i, iEnd).trimStart('0')
            val numberB = b.substring(j, jEnd).trimStart('0')
            val byLength = numberA.length.compareTo(numberB.length)
            if (byLength != 0) return byLength
            val byDigits = numberA.compareTo(numberB)
            if (byDigits != 0) return byDigits
            i = iEnd
            j = jEnd
        } else {
            val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (byChar != 0) return byChar
            i++
            j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}

/** 过滤出图片条目并按阅读顺序排序（CBZ 页序）。 */
internal fun orderComicPageNames(entryNames: List<String>): List<String> =
    entryNames.filter(::isComicPageEntry).sortedWith(::compareComicPageNames)
