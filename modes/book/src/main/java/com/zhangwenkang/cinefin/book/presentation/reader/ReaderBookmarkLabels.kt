package com.zhangwenkang.cinefin.book.presentation.reader

import com.zhangwenkang.cinefin.repository.normalizedProgression
import java.util.Locale

/**
 * 书签标签：优先使用 Readium Locator 带的章节标题，退化为整书百分比。
 *
 * 纯函数，便于 JVM 单测覆盖边界（空标题 / 百分号格式 / 进度夹取）。
 */
fun bookmarkLabel(locatorTitle: String?, progression: Double): String {
    val percent = formatProgressionPercent(progression)
    val title = locatorTitle?.trim().orEmpty()
    return when {
        title.isEmpty() -> percent
        title.contains(percent) -> title
        else -> "$title · $percent"
    }
}

/** 整书进度转显示文本：`0.12345` → `12.3%`（固定 `Locale.US` 小数点，便于断言）。 */
fun formatProgressionPercent(progression: Double): String =
    String.format(Locale.US, "%.1f%%", normalizedProgression(progression) * 100.0)

/** 本地书籍大小显示文本（应用私有目录，二进制单位）。 */
fun formatBookSize(sizeBytes: Long): String {
    if (sizeBytes < 1024) return "$sizeBytes B"
    val kilobytes = sizeBytes / 1024.0
    if (kilobytes < 1024) return String.format(Locale.US, "%.0f KB", kilobytes)
    val megabytes = kilobytes / 1024.0
    return String.format(Locale.US, "%.1f MB", megabytes)
}
