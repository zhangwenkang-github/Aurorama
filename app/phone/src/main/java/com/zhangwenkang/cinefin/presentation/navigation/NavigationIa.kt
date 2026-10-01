package com.zhangwenkang.cinefin.presentation.navigation

/** 顶层目的地：底部 Tab 顺序与侧栏可见性开关都按它寻址（W6-R6N）。 */
enum class NavEntryKey {
    Home,
    Media,
    Music,
    Bookshelf,
    Downloads,
    Console,
    Metadata,
    Settings,
}

/**
 * 顶层目的地门控（纯逻辑，单测覆盖）：
 *
 * 1. **音乐 / 书架**：服务器确认没有对应类型的库时隐藏入口；库列表还没拿到（冷启动竞态 / 拉取失败） 时保持可见，由页面显示空态——不能把网络故障翻译成"没有音乐库"。
 * 2. **服务器控制台 / 元数据管理器**：只对管理员出现（W5-R3I 的门控不变）。
 * 3. 其余（首页 / 媒体库 / 下载 / 客户端设置）常驻。
 */
fun navEntryKeys(
    isAdministrator: Boolean,
    librariesLoaded: Boolean,
    hasMusicLibrary: Boolean,
    hasBooksLibrary: Boolean,
): List<NavEntryKey> = buildList {
    add(NavEntryKey.Home)
    add(NavEntryKey.Media)
    if (!librariesLoaded || hasMusicLibrary) add(NavEntryKey.Music)
    if (!librariesLoaded || hasBooksLibrary) add(NavEntryKey.Bookshelf)
    add(NavEntryKey.Downloads)
    if (isAdministrator) {
        add(NavEntryKey.Console)
        add(NavEntryKey.Metadata)
    }
    add(NavEntryKey.Settings)
}

/**
 * 侧栏（平板侧轨 / 抽屉）可见性过滤。
 *
 * 客户端设置常驻：它是"侧栏显示"开关自己的入口，允许关掉会把用户锁在侧栏之外。
 */
fun visibleRailKeys(
    keys: List<NavEntryKey>,
    visibility: SidebarVisibility,
): List<NavEntryKey> = keys.filter { key ->
    when (key) {
        NavEntryKey.Home -> visibility.home
        NavEntryKey.Media -> visibility.media
        NavEntryKey.Music -> visibility.music
        NavEntryKey.Bookshelf -> visibility.bookshelf
        NavEntryKey.Downloads -> visibility.downloads
        NavEntryKey.Console -> visibility.console
        NavEntryKey.Metadata -> visibility.metadata
        NavEntryKey.Settings -> true
    }
}

/** 手机底部 Tab 的固定顺序（用户反馈：手机四条 Tab 行为不变）。 */
val bottomNavKeys: List<NavEntryKey> =
    listOf(NavEntryKey.Home, NavEntryKey.Music, NavEntryKey.Bookshelf, NavEntryKey.Media)
