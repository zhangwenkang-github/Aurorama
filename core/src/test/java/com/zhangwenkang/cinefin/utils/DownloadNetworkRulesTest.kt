package com.zhangwenkang.cinefin.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** W57 下载网络策略纯函数单测（Wi-Fi / 以太网不看计费标记；其余网络看开关）。 */
class DownloadNetworkRulesTest {

    @Test
    fun `Wi-Fi 与以太网不看计费标记直接允许`() {
        assertEquals(
            DownloadNetworkDecision.ALLOW,
            DownloadNetworkRules.decide(
                isWifiOrEthernet = true,
                isCellular = false,
                isRoaming = false,
                allowMobileData = false,
                allowRoaming = false,
            ),
        )
    }

    @Test
    fun `蜂窝未允许移动数据时等待`() {
        assertEquals(
            DownloadNetworkDecision.WAIT_FOR_MOBILE_DATA,
            DownloadNetworkRules.decide(
                isWifiOrEthernet = false,
                isCellular = true,
                isRoaming = false,
                allowMobileData = false,
                allowRoaming = true,
            ),
        )
    }

    @Test
    fun `蜂窝漫游未允许漫游时等待`() {
        assertEquals(
            DownloadNetworkDecision.WAIT_FOR_ROAMING,
            DownloadNetworkRules.decide(
                isWifiOrEthernet = false,
                isCellular = true,
                isRoaming = true,
                allowMobileData = true,
                allowRoaming = false,
            ),
        )
    }

    @Test
    fun `蜂窝允许移动数据与漫游时允许`() {
        assertEquals(
            DownloadNetworkDecision.ALLOW,
            DownloadNetworkRules.decide(
                isWifiOrEthernet = false,
                isCellular = true,
                isRoaming = true,
                allowMobileData = true,
                allowRoaming = true,
            ),
        )
    }

    @Test
    fun `非 Wi-Fi 非蜂窝网络按移动数据开关判定`() {
        assertEquals(
            DownloadNetworkDecision.WAIT_FOR_MOBILE_DATA,
            DownloadNetworkRules.decide(
                isWifiOrEthernet = false,
                isCellular = false,
                isRoaming = false,
                allowMobileData = false,
                allowRoaming = true,
            ),
        )
    }
}
