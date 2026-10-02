package com.zhangwenkang.cinefin.presentation.offline

/** 冷启动落点（W36：离线模式优先走离线首页，不依赖登录态）。 */
internal enum class StartDestinationKind {
    OFFLINE_HOME,
    HOME,
    USERS,
    SERVERS,
    WELCOME,
}

/**
 * 导航门控纯函数（单测覆盖）：
 *
 * - 离线模式（无论是否有服务器 / 账号）→ 离线首页：用户可能刚从欢迎页点「离线模式」进来， 也可能上次退出时仍在离线模式；
 * - 在线：保持原有「有服务器+当前服务器+当前用户 → 首页，否则依次回退」的规则。
 */
internal fun resolveStartDestinationKind(
    isOfflineMode: Boolean,
    hasServers: Boolean,
    hasCurrentServer: Boolean,
    hasCurrentUser: Boolean,
): StartDestinationKind =
    when {
        isOfflineMode -> StartDestinationKind.OFFLINE_HOME
        hasServers && hasCurrentServer && hasCurrentUser -> StartDestinationKind.HOME
        hasServers && hasCurrentServer -> StartDestinationKind.USERS
        hasServers -> StartDestinationKind.SERVERS
        else -> StartDestinationKind.WELCOME
    }
