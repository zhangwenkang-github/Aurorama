package com.zhangwenkang.cinefin.settings.domain

object Constants {
    // Player - Media Segments
    object PlayerMediaSegmentsAutoSkip {
        const val ALWAYS = "always"
        const val PIP = "pip"
    }

    /** 字幕显示模式 */
    object SubtitleMode {
        /** 自动：命中偏好语言才显示，否则不显示 */
        const val AUTO = "auto"

        /** 始终显示：没有命中偏好语言时也选择一条字幕 */
        const val ALWAYS = "always"

        /** 关闭：默认不加载字幕 */
        const val OFF = "off"
    }

    // Network
    const val NETWORK_DEFAULT_REQUEST_TIMEOUT = 30_000L
    const val NETWORK_DEFAULT_CONNECT_TIMEOUT = 6_000L
    const val NETWORK_DEFAULT_SOCKET_TIMEOUT = 10_000L
}
