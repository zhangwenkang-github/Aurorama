package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicSong
import java.util.UUID

/**
 * 歌曲行封面来源（W79b · #33）。
 *
 * 曲目自身封面优先：服务器曲目是音频条目的 Primary 图，本地曲目已在索引阶段落好「内嵌标签 → 同目录封面」链。 个别服务器曲目没有 Primary
 * 图时，用同专辑已加载的封面兜底；这张表由曲库快照聚合，不产生额外网络请求。
 */
internal fun resolveSongArtworkUri(
    imageUri: String?,
    albumName: String,
    albumArtwork: Map<String, String>,
): String? =
    imageUri?.takeIf { it.isNotBlank() } ?: albumArtwork[albumName]?.takeIf { it.isNotBlank() }

/** 专辑名 → 封面 URI（只取有图的专辑；同名专辑保留首个有效封面）。 */
internal fun albumArtworkUris(albums: List<MusicAlbum>): Map<String, String> = buildMap {
    albums.forEach { album ->
        val uri = album.imageUri?.takeIf { it.isNotBlank() } ?: return@forEach
        putIfAbsent(album.name, uri)
    }
}

/**
 * 当前播放曲在歌曲列表中的下标（含列表头占位）。
 *
 * 队列为空、当前曲不在当前筛选列表时返回 `-1` —— 调用方静默跳过定位。
 */
internal fun songListAutoLocateIndex(
    songs: List<MusicSong>,
    currentItemId: UUID?,
    headerCount: Int = 0,
): Int {
    if (currentItemId == null) return -1
    val index = songs.indexOfFirst { it.itemId == currentItemId }
    return if (index >= 0) index + headerCount else -1
}

/** #34：只有「歌曲 Tab 可见 + 找得到当前曲 + 用户尚未手动滚动」时才允许自动定位。 */
internal fun shouldAutoLocateSong(
    enabled: Boolean,
    targetIndex: Int,
    manualScrolled: Boolean,
): Boolean = enabled && targetIndex >= 0 && !manualScrolled

/**
 * 目标行已布局时，把它补到完整可见所需的最小滚动距离。
 *
 * 返回值沿用 LazyList 内容坐标系：负数向上滚、正数向下滚；完整可见时恒为 0，保证点击可见行不会产生任何列表位移。
 */
internal fun songListMinimalRevealDelta(
    viewportStart: Int,
    viewportEnd: Int,
    itemOffset: Int,
    itemSize: Int,
): Int {
    if (viewportEnd <= viewportStart || itemSize <= 0) return 0
    return when {
        itemOffset < viewportStart -> itemOffset - viewportStart
        itemOffset + itemSize > viewportEnd -> itemOffset + itemSize - viewportEnd
        else -> 0
    }
}

/**
 * 目标行尚未布局时，一次 `animateScrollToItem` 的目标 offset。
 *
 * 目标在下方时贴住视口底部、在上方时贴住视口顶部，不做居中；因此外部切歌不会出现「先到中间再回弹」。
 */
internal fun songListRevealScrollOffset(
    targetIndex: Int,
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    viewportHeight: Int,
    itemHeight: Int,
): Int {
    if (viewportHeight <= 0 || itemHeight <= 0) return 0
    return when {
        // 正 offset 表示目标继续向视口上方移动：目标在上方时把它的底边贴到视口顶部。
        targetIndex < firstVisibleIndex -> itemHeight
        // 目标在下方时取负值：item 起点落在视口底部上方一行高，底边贴住视口底部。
        targetIndex > lastVisibleIndex ->
            if (itemHeight >= viewportHeight) 0 else -(viewportHeight - itemHeight)
        else -> 0
    }
}

/** 进入歌曲 Tab 时的一次性居中 offset（只用于首次定位，后续切歌不再居中）。 */
internal fun songListCenteredScrollOffset(viewportHeight: Int, itemHeight: Int): Int {
    if (viewportHeight <= 0 || itemHeight <= 0) return 0
    return -((viewportHeight - itemHeight) / 2).coerceAtLeast(0)
}

/**
 * 歌曲行序号文案（W79b-FIX）：至少两位，三位 / 四位数不截断。
 *
 * 列表上限 1000 首时最大为「1000」，渲染侧必须单行显示。
 */
internal fun songRowIndexLabel(index: Int): String =
    index.coerceAtLeast(0).toString().padStart(2, '0')
