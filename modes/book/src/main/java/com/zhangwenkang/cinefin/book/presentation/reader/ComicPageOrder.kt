package com.zhangwenkang.cinefin.book.presentation.reader

import com.zhangwenkang.cinefin.local.LocalThumbnailRules
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
 * 过滤出图片条目并按阅读顺序排序（CBZ 页序）。
 *
 * 自然序比较与本地缩略图（W49「CBZ 封面自然序」）共用 data 层同一份实现，避免两处排序口径漂移。
 */
internal fun orderComicPageNames(entryNames: List<String>): List<String> =
    LocalThumbnailRules.sortedComicPageNames(entryNames.filter(::isComicPageEntry))
