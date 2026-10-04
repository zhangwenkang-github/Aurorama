package com.zhangwenkang.cinefin.presentation.film.components

import androidx.annotation.DrawableRes
import com.zhangwenkang.cinefin.core.R
import com.zhangwenkang.cinefin.models.CollectionType

/**
 * W69c：首页「最新 · <库名>」走廊按库类型选无图卡片的占位图标（纯函数，单测覆盖）。
 *
 * - 书籍库 → `ic_book`（配合 `BookCoverProvider` 生成封面：生成中就绪前先露书图标 + 媒体色底）；
 * - 音乐库 → `ic_music`（音符 + 媒体色底，与 W59 下载页 / W68 媒体会话占位同口径）；
 * - 其余库（影视等）→ null（保持原有服务器图 / 深色底行为，不引入类型占位）。
 */
@DrawableRes
fun libraryPlaceholderIconRes(type: CollectionType): Int? =
    when (type) {
        CollectionType.Books -> R.drawable.ic_book
        CollectionType.Music -> R.drawable.ic_music
        else -> null
    }
