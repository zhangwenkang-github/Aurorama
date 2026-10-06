package com.zhangwenkang.cinefin.presentation.film.components

import androidx.annotation.DrawableRes
import com.zhangwenkang.cinefin.core.R
import com.zhangwenkang.cinefin.core.presentation.theme.MediaBook
import com.zhangwenkang.cinefin.core.presentation.theme.MediaColors
import com.zhangwenkang.cinefin.core.presentation.theme.MediaFilm
import com.zhangwenkang.cinefin.core.presentation.theme.MediaMusic
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidBoxSet
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidShow

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

/**
 * W74（#3）：媒体库占位底色叠加强度——`surfaceContainerHigh` 之上叠 `media.base` 的透明度。
 *
 * 沿用 D81（W70b 本地媒体库）口径：12–18% 取中，库卡 / 详情封面卡 / 条目缩略图三处同值。
 */
internal const val LibraryPlaceholderBaseTintAlpha = 0.14f

/**
 * W74（#3）：服务器媒体库 → 无封面占位的媒体色域（纯函数，单测覆盖）。
 *
 * 与 D81 本地库口径一致：音乐 = 音乐域、书籍 = 阅读域，其余（影视 / 家庭视频 / 混合库 / 播放列表 / 合集 / 文件夹）= 影视域（混合沿用既有 Neutral =
 * 影视色口径）。无色板新增。
 */
internal fun libraryPlaceholderMedia(type: CollectionType): MediaColors =
    when (type) {
        CollectionType.Music -> MediaMusic
        CollectionType.Books -> MediaBook
        else -> MediaFilm
    }

/**
 * W74（#17）：影视域条目卡（视频页聚合 / 临时库网格）的无图占位图标（纯函数，单测覆盖）。
 *
 * 家庭视频（`BaseItemKind.VIDEO`）与电影一样映射到 `FindroidMovie`，共用胶片图标； 剧集 = 电视图标、合集 =
 * 星标，其余落通用库图标——与纯黑卡片对应的「图标 + 域色底」占位配套。
 */
@DrawableRes
internal fun videoItemPlaceholderIconRes(item: FindroidItem): Int =
    when (item) {
        is FindroidShow -> R.drawable.ic_tv
        is FindroidBoxSet -> R.drawable.ic_star
        is FindroidEpisode,
        is FindroidSeason,
        is FindroidMovie -> R.drawable.ic_film
        else -> R.drawable.ic_library
    }
