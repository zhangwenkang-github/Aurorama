package com.zhangwenkang.cinefin.book.presentation.reader

import android.view.KeyEvent

/**
 * 阅读页键位动作（W6 T4 / READER-10/44，定稿三 §1）。
 *
 * 只描述「动作」，不依赖具体引擎：EPUB 交给 Readium 导航器，PDF / CBZ 交给页序列视图。
 */
internal enum class ReaderKeyAction {
    PreviousPage,
    NextPage,
    ScrollUp,
    ScrollDown,
    ToggleControls,
}

/** 滚动档一次按键的滚动距离（dp，约一行正文）。 */
internal const val READER_KEY_SCROLL_LINE_DP: Float = 24f

/**
 * 物理键 → 阅读动作（纯函数，null = 不消费、交给系统）。
 *
 * - 音量上下键：仅 [volumeKeysPaging] 打开时消费；关闭时返回 null，让系统继续调音量。
 * - 分页 / 双栏档：←/↑/PgUp = 上一页，→/↓/PgDn = 下一页。
 * - 滚动档：同一组键改为向上 / 向下滚动。
 * - Enter / 小键盘 Enter / 方向键中键 = 控制层开合。
 */
internal fun readerKeyAction(
    keyCode: Int,
    volumeKeysPaging: Boolean,
    scrollMode: Boolean,
): ReaderKeyAction? {
    val backward = if (scrollMode) ReaderKeyAction.ScrollUp else ReaderKeyAction.PreviousPage
    val forward = if (scrollMode) ReaderKeyAction.ScrollDown else ReaderKeyAction.NextPage
    return when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeysPaging) backward else null
        KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeysPaging) forward else null
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_PAGE_UP -> backward
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_PAGE_DOWN -> forward
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER -> ReaderKeyAction.ToggleControls
        else -> null
    }
}
