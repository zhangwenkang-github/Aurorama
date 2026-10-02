package com.zhangwenkang.cinefin.presentation.offline

import org.junit.Assert.assertEquals
import org.junit.Test

/** W36：导航门控纯函数——离线模式优先落离线首页，在线保持原有登录态回退规则。 */
class OfflineNavigationTest {

    @Test
    fun offlineModeAlwaysStartsAtOfflineHome() {
        assertEquals(
            StartDestinationKind.OFFLINE_HOME,
            resolveStartDestinationKind(
                isOfflineMode = true,
                hasServers = false,
                hasCurrentServer = false,
                hasCurrentUser = false,
            ),
        )
        assertEquals(
            StartDestinationKind.OFFLINE_HOME,
            resolveStartDestinationKind(
                isOfflineMode = true,
                hasServers = true,
                hasCurrentServer = true,
                hasCurrentUser = true,
            ),
        )
    }

    @Test
    fun onlineModeKeepsExistingFallbackChain() {
        assertEquals(
            StartDestinationKind.HOME,
            resolveStartDestinationKind(false, true, true, true),
        )
        assertEquals(
            StartDestinationKind.USERS,
            resolveStartDestinationKind(false, true, true, false),
        )
        assertEquals(
            StartDestinationKind.SERVERS,
            resolveStartDestinationKind(false, true, false, false),
        )
        assertEquals(
            StartDestinationKind.WELCOME,
            resolveStartDestinationKind(false, false, false, false),
        )
    }
}
