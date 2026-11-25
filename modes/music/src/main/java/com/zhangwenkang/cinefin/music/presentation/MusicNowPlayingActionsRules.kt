package com.zhangwenkang.cinefin.music.presentation

/**
 * W3-T18 · §4.5：全屏播放页底部辅助行（单入口原则）。
 *
 * 左「音效」/ 中「歌词」/ 右「队列」——全屏播放页里这三个功能各自只保留这一个入口。
 */
internal enum class NowPlayingBottomAction {
    EFFECTS,
    LYRICS,
    QUEUE,
}

internal fun nowPlayingBottomActions(): List<NowPlayingBottomAction> =
    listOf(
        NowPlayingBottomAction.EFFECTS,
        NowPlayingBottomAction.LYRICS,
        NowPlayingBottomAction.QUEUE,
    )

/**
 * W3-T18 · §4.5：顶栏 ⋮ 菜单只保留「底部辅助行没有的功能」——播放模式 / 收藏 / 桌面歌词 / 睡眠定时。
 *
 * 队列与音效已收敛到底部辅助行，菜单不再重复；没有曲目元数据时「收藏」不可用，不渲染。
 */
internal enum class NowPlayingMenuAction {
    PLAY_MODE,
    FAVORITE,
    LYRIC_OVERLAY,
    SLEEP_TIMER,
}

internal fun nowPlayingMenuActions(hasSongMeta: Boolean): List<NowPlayingMenuAction> = buildList {
    add(NowPlayingMenuAction.PLAY_MODE)
    if (hasSongMeta) add(NowPlayingMenuAction.FAVORITE)
    add(NowPlayingMenuAction.LYRIC_OVERLAY)
    add(NowPlayingMenuAction.SLEEP_TIMER)
}
