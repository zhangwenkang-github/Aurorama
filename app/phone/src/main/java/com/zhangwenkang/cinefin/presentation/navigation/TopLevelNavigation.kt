package com.zhangwenkang.cinefin.presentation.navigation

/**
 * W56「顶层图标统一回对应主页」：点击落点判定（纯函数，单测覆盖）。
 *
 * 用户 2026-10-04 拍板：任何模式下进入二级界面（音乐全屏播放 / 歌词页、库内容页、 视频 / 书架详情、临时库等）后，再点底栏 / 侧轨对应图标 = 直接回该模式主页；
 * 已在主页时停在原地（不重复导航、不闪烁）。
 */
internal enum class TopLevelTapAction {
    /** 已在入口主页、且页内没有覆盖层：什么都不做。 */
    Stay,
    /** 已在入口主页、但页内有覆盖层（音乐全屏播放 / 歌词页）：只收起覆盖层，不导航。 */
    CollapseOverlay,
    /** 位于二级页或其它入口：走既有顶层导航（弹回入口根页 / 切换入口）。 */
    Navigate,
}

internal fun topLevelTapAction(
    isOnEntryHome: Boolean,
    hasInPageOverlay: Boolean,
): TopLevelTapAction =
    when {
        !isOnEntryHome -> TopLevelTapAction.Navigate
        hasInPageOverlay -> TopLevelTapAction.CollapseOverlay
        else -> TopLevelTapAction.Stay
    }
