package com.zhangwenkang.cinefin.utils

/** W57 下载网络策略判定结果（纯函数）。 */
enum class DownloadNetworkDecision {
    ALLOW,
    WAIT_FOR_MOBILE_DATA,
    WAIT_FOR_ROAMING,
}

/**
 * W57 下载网络策略（纯函数，单测覆盖）。
 *
 * 用户口径：Wi-Fi / 以太网直接允许（**不看系统计费标记**，修复「家庭 Wi-Fi 被判计费 → 永远等待」）； 其余网络（蜂窝等）仍看「允许移动数据」与「漫游」开关，未允许 → 等待。
 */
object DownloadNetworkRules {

    fun decide(
        isWifiOrEthernet: Boolean,
        isCellular: Boolean,
        isRoaming: Boolean,
        allowMobileData: Boolean,
        allowRoaming: Boolean,
    ): DownloadNetworkDecision =
        when {
            isWifiOrEthernet -> DownloadNetworkDecision.ALLOW
            !allowMobileData -> DownloadNetworkDecision.WAIT_FOR_MOBILE_DATA
            isCellular && isRoaming && !allowRoaming -> DownloadNetworkDecision.WAIT_FOR_ROAMING
            else -> DownloadNetworkDecision.ALLOW
        }
}
