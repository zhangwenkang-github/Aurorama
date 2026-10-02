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
 *
 * 顺序（W8-R3 用户反馈 4，覆盖 D22 ③ / D28 的排布）：**首页 → 音乐 → 书架 → 媒体库（二级分组）→ 下载 → [服务器控制台 / 媒体资料管理器] →
 * 客户端设置**。
 */
fun navEntryKeys(
    isAdministrator: Boolean,
    librariesLoaded: Boolean,
    hasMusicLibrary: Boolean,
    hasBooksLibrary: Boolean,
): List<NavEntryKey> = buildList {
    add(NavEntryKey.Home)
    if (!librariesLoaded || hasMusicLibrary) add(NavEntryKey.Music)
    if (!librariesLoaded || hasBooksLibrary) add(NavEntryKey.Bookshelf)
    add(NavEntryKey.Media)
    add(NavEntryKey.Downloads)
    if (isAdministrator) {
        add(NavEntryKey.Console)
        add(NavEntryKey.Metadata)
    }
    add(NavEntryKey.Settings)
}

/**
 * 「媒体库」二级分组默认收起（W8-R3 用户反馈 4）：覆盖 W7-R3 的默认展开。
 *
 * 抽屉与侧轨共用这一份默认值——先在侧栏里看到「首页 / 音乐 / 书架 / 媒体库」四条一级入口， 需要时再用「媒体库」行尾的箭头展开出服务器实际的库列表（离线模式没有库列表，只留一级入口）。
 */
const val MEDIA_GROUP_DEFAULT_EXPANDED: Boolean = false

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

/**
 * 侧栏分组（W42）：导航区按「内容 / 管理」分成两组，组间 12dp 空隙 + 细分隔线； 「客户端设置」固定在底部设置区（发丝线与导航区分隔，开关自己不能被关掉，见
 * [visibleRailKeys]）。
 */
enum class RailGroup {
    Content,
    Manage,
    Pinned,
}

/** 目的地 → 侧栏分组。 */
fun railGroupOf(key: NavEntryKey): RailGroup =
    when (key) {
        NavEntryKey.Home,
        NavEntryKey.Media,
        NavEntryKey.Music,
        NavEntryKey.Bookshelf,
        NavEntryKey.Downloads -> RailGroup.Content
        NavEntryKey.Console,
        NavEntryKey.Metadata -> RailGroup.Manage
        NavEntryKey.Settings -> RailGroup.Pinned
    }

/**
 * 组间分隔索引（纯逻辑，单测覆盖）：返回「其后应插入分组空隙 + 细分隔线」的条目下标。
 *
 * 底部固定区（[RailGroup.Pinned]）不产生分组分隔——它与导航区之间由 `pinnedTailCount` 的发丝线负责， 两个发丝线叠在一起会把 72dp 折叠轨切得太碎。
 */
fun railGroupBreaks(keys: List<NavEntryKey>): Set<Int> {
    val breaks = mutableSetOf<Int>()
    keys.forEachIndexed { index, key ->
        val next = keys.getOrNull(index + 1) ?: return@forEachIndexed
        val group = railGroupOf(key)
        val nextGroup = railGroupOf(next)
        if (group == RailGroup.Pinned || nextGroup == RailGroup.Pinned) return@forEachIndexed
        if (group != nextGroup) breaks.add(index)
    }
    return breaks
}
