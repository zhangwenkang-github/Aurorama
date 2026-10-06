package com.zhangwenkang.cinefin.player.local.domain

/**
 * W76-Q5：播放页真正结束（`PlayerViewModel.onCleared` → `releasePlayer`）时，是否对播放器**当前条目**补发一条
 * `Sessions/Playing/Stopped`。
 *
 * 只给「本页自己启动 / 播放的视频会话」补发。视频播放页经锁屏 / 通知误入（`attachToExistingSession`）时，实例上挂的
 * 是**音乐条目**，后台音乐归音乐侧管理——这里再补一条 Stopped 会把仍在播的音乐算成已停止 （W76-B10 遗留③；W68 D59 只挡了 `onPause` /
 * `updatePlaybackProgress`）。
 *
 * 判定口径与 [musicSessionActionOnVideoStart] 一致：用「当前媒体项是否音乐条目」（[MUSIC_MEDIA_EXTRA] 标记）， 不信任
 * `PlayerHolder.musicSessionActive`（视频抢用实例时标志可能还没清零）。
 *
 * @param currentItemIsMusic 播放器当前媒体项是否为音乐条目（本页自己起播的视频会话恒为 false）
 * @return true = 补发 Stopped（视频条目）；false = 不补发（音乐条目，归音乐侧上报）
 */
internal fun shouldReportStopOnPlayerExit(currentItemIsMusic: Boolean): Boolean =
    !currentItemIsMusic
