package com.zhangwenkang.cinefin.settings.domain

/**
 * TXT 分章规则（READER-37，W6-T7）。
 *
 * 默认正则覆盖三类章头，逐行匹配、命中必须落在行首（允许前置空白）：
 * - 中文章头：`第[0-9０-９一二三四五六七八九十百千万零两]+[章节回卷]`，后接任意章名（如「第一章 风起」）；
 * - 中文头尾篇：序章 / 楔子 / 前言 / 后记 / 尾声 / 终章 / 番外N；
 * - 英文：`Chapter N`（罗马数字与阿拉伯数字，大小写不敏感）。
 *
 * 用户可在 `AppPreferences.readerTxtChapterRegex` 改这条正则；非法正则或空串一律回落默认值。
 */
object TxtChapterRules {

    /** 默认分章正则（纯 JVM 正则，无 Android 依赖，`TxtToEpub` 与设置项共用）。 */
    const val DEFAULT_CHAPTER_REGEX =
        "^(?:第\\s*[0-9０-９一二三四五六七八九十百千万零两]+\\s*[章节回卷]" +
            "|序章|楔子|前言|后记|尾声|终章|番外(?:篇)?[0-9一二三四五六七八九十]*" +
            "|(?i:chapter)\\s+[0-9ivxlcdm]+).*"

    /** 空串 / 非法正则 → 默认值；合法则原样使用（用户自定义优先）。 */
    fun resolve(raw: String?): String {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty()) return DEFAULT_CHAPTER_REGEX
        return if (runCatching { Regex(candidate) }.isSuccess) candidate else DEFAULT_CHAPTER_REGEX
    }
}
