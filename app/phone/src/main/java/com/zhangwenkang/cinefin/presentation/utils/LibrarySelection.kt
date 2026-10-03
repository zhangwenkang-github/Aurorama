package com.zhangwenkang.cinefin.presentation.utils

import java.util.UUID

/**
 * 库选择偏好的窄映射（W54-C）：字符串偏好 ↔ 库 id。
 *
 * 两处选择器（视频页 / 书架页）都只把「服务器库 id」落盘： `null` = 不限定（视频页「全部库」/ 书架页「自动」），空串与解析失败的旧值一律 视为不限定——服务器删库 /
 * 换库后不会把页面卡在「找不到的库」上。
 */
internal fun parseStoredLibraryId(raw: String?): UUID? =
    raw?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }

/** [parseStoredLibraryId] 的反向映射：`null`（不限定）落盘成偏好里的 null，而不是空串。 */
internal fun storedLibraryIdValue(id: UUID?): String? = id?.toString()
