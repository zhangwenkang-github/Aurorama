package com.zhangwenkang.cinefin.music.presentation

/**
 * W3-T15：歌曲行右侧动作（§4.2）。
 *
 * compact（<600dp）= 收藏 + ⋮；≥600dp = 加歌单 + 收藏 + ⋮。
 */
internal enum class MusicSongRowAction {
    ADD_TO_PLAYLIST,
    FAVORITE,
    MORE,
}

/**
 * W3-T15：歌曲上下文菜单动作（顺序即菜单顺序，§4.2 官方全量）。
 *
 * [PLAY] = 立即起播；[PLAY_NEXT] / [ENQUEUE] 插入「接下来播放」队首 / 队尾；[REFRESH_METADATA] 本期占位（T20 接入）。
 */
enum class MusicSongAction {
    PLAY,
    PLAY_NEXT,
    ENQUEUE,
    ADD_TO_PLAYLIST,
    FAVORITE,
    DOWNLOAD,
    VIEW_ALBUM,
    VIEW_ARTIST,
    REFRESH_METADATA,
    AUDIO_INFO,
    MOVE_UP,
    MOVE_DOWN,
}

/** W3-T15：队列动作的插入位置（复用现有 queue 语义：插队首 = 下一首播放，队尾 = 加入队列）。 */
internal enum class MusicQueueInsertion {
    UP_NEXT_HEAD,
    UP_NEXT_TAIL,
}

/** W3-T15：行右侧动作列表（纯函数，档位口径见 [musicSongRowWideLayout]）。 */
internal fun musicSongRowActions(
    wideLayout: Boolean,
    canAddToPlaylist: Boolean,
): List<MusicSongRowAction> =
    if (wideLayout && canAddToPlaylist) {
        listOf(
            MusicSongRowAction.ADD_TO_PLAYLIST,
            MusicSongRowAction.FAVORITE,
            MusicSongRowAction.MORE,
        )
    } else {
        listOf(MusicSongRowAction.FAVORITE, MusicSongRowAction.MORE)
    }

/**
 * W3-T15：上下文菜单动作序列（纯函数）。
 *
 * 固定项：播放 / 下一首播放 / 加入队列 / 收藏；可选：加入歌单、下载、查看专辑、查看艺术家； 固定项：刷新元数据（T20 占位）；可选：音频信息；歌单详情追加：上移 / 下移。
 */
internal fun musicSongMenuActions(
    showAddToPlaylist: Boolean,
    showDownload: Boolean,
    showViewAlbum: Boolean,
    showViewArtist: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    showAudioInfo: Boolean,
): List<MusicSongAction> = buildList {
    add(MusicSongAction.PLAY)
    add(MusicSongAction.PLAY_NEXT)
    add(MusicSongAction.ENQUEUE)
    if (showAddToPlaylist) add(MusicSongAction.ADD_TO_PLAYLIST)
    add(MusicSongAction.FAVORITE)
    if (showDownload) add(MusicSongAction.DOWNLOAD)
    if (showViewAlbum) add(MusicSongAction.VIEW_ALBUM)
    if (showViewArtist) add(MusicSongAction.VIEW_ARTIST)
    add(MusicSongAction.REFRESH_METADATA)
    if (showAudioInfo) add(MusicSongAction.AUDIO_INFO)
    if (canMoveUp) add(MusicSongAction.MOVE_UP)
    if (canMoveDown) add(MusicSongAction.MOVE_DOWN)
}

/** W3-T15：上下文菜单项文案（[MusicSongAction.DOWNLOAD] 沿用既有下载态文案，null = 不渲染）。 */
internal fun musicSongActionLabel(
    action: MusicSongAction,
    isFavorite: Boolean,
    downloadLabel: String? = null,
): String? =
    when (action) {
        MusicSongAction.PLAY -> "播放"
        MusicSongAction.PLAY_NEXT -> "下一首播放"
        MusicSongAction.ENQUEUE -> "加入队列"
        MusicSongAction.ADD_TO_PLAYLIST -> "加入歌单"
        MusicSongAction.FAVORITE -> if (isFavorite) "取消收藏" else "收藏"
        MusicSongAction.DOWNLOAD -> downloadLabel
        MusicSongAction.VIEW_ALBUM -> "查看专辑"
        MusicSongAction.VIEW_ARTIST -> "查看艺术家"
        MusicSongAction.REFRESH_METADATA -> "刷新元数据"
        MusicSongAction.AUDIO_INFO -> "音频信息"
        MusicSongAction.MOVE_UP -> "上移"
        MusicSongAction.MOVE_DOWN -> "下移"
    }

/** W3-T15：队列动作 → 插入位置；非队列动作返回 null。 */
internal fun musicSongQueueInsertion(action: MusicSongAction): MusicQueueInsertion? =
    when (action) {
        MusicSongAction.PLAY_NEXT -> MusicQueueInsertion.UP_NEXT_HEAD
        MusicSongAction.ENQUEUE -> MusicQueueInsertion.UP_NEXT_TAIL
        else -> null
    }

/**
 * W3-T15：行右侧三图标的宽度档位。
 *
 * 口径与项目既有形态分级一致（`screenWidthDp < 600` = compact，见 CinefinNavigation / DownloadsScreen）。
 */
internal fun musicSongRowWideLayout(screenWidthDp: Int): Boolean = screenWidthDp >= 600
