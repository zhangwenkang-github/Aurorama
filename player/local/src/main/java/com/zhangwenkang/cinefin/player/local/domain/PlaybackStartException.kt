package com.zhangwenkang.cinefin.player.local.domain

/**
 * W76-B11：起播前置条件不满足（解析失败 / 结果为空）。
 *
 * 旧实现遇到这类情况在 [PlaylistManager.getInitialItem] 里静默 `return null`：播放页拿不到起播条目，停在 `00:00/00:00` +
 * 队列为空的空载态，**没有任何提示**。
 *
 * 现在改成抛这个异常：播放页（`PlayerViewModel.initializePlayer`）会把 [message] 当作 `Toast` 直接提示用户， 所以 [message]
 * 必须是面向用户的简体中文文案，不能是内部术语。
 */
class PlaybackStartException(message: String) : IllegalStateException(message)
