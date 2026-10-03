package com.zhangwenkang.cinefin.presentation.navigation

import com.zhangwenkang.cinefin.LibraryRoute
import com.zhangwenkang.cinefin.TemporaryLibraryRoute
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.models.CollectionType

/** 顶层目的地：底部 Tab 顺序与侧栏可见性开关都按它寻址（W6-R6N）。 */
enum class NavEntryKey {
    Home,
    Video,
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
 * 1. **视频 / 音乐 / 书架**：服务器确认没有对应类型的库时隐藏入口；库列表还没拿到（冷启动竞态 / 拉取失败） 时保持可见，由页面显示空态——不能把网络故障翻译成"没有对应类型的库"。
 * 2. **服务器控制台 / 元数据管理器**：只对管理员出现（W5-R3I 的门控不变）。
 * 3. 其余（首页 / 媒体库 / 下载 / 客户端设置）常驻。
 *
 * 顺序（W53 用户 2026-10-03 确认，覆盖 W8-R3 的排布）：**首页 → 视频 → 音乐 → 书架 → 媒体库（二级分组）→ 下载 → [服务器控制台 / 媒体资料管理器] →
 * 客户端设置**。
 */
fun navEntryKeys(
    isAdministrator: Boolean,
    librariesLoaded: Boolean,
    hasVideoLibrary: Boolean,
    hasMusicLibrary: Boolean,
    hasBooksLibrary: Boolean,
): List<NavEntryKey> = buildList {
    add(NavEntryKey.Home)
    if (!librariesLoaded || hasVideoLibrary) add(NavEntryKey.Video)
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
        NavEntryKey.Video -> visibility.video
        NavEntryKey.Media -> visibility.media
        NavEntryKey.Music -> visibility.music
        NavEntryKey.Bookshelf -> visibility.bookshelf
        NavEntryKey.Downloads -> visibility.downloads
        NavEntryKey.Console -> visibility.console
        NavEntryKey.Metadata -> visibility.metadata
        NavEntryKey.Settings -> true
    }
}

/**
 * 手机底部 Tab 的固定顺序（W53 用户 2026-10-03 确认）：**首页 / 视频 / 音乐 / 书架**。
 *
 * 「媒体库」移出底栏，仍保留在侧栏 / 抽屉（它是二级分组，展开后是服务器实际库列表）。
 */
val bottomNavKeys: List<NavEntryKey> =
    listOf(NavEntryKey.Home, NavEntryKey.Video, NavEntryKey.Music, NavEntryKey.Bookshelf)

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
        NavEntryKey.Video,
        NavEntryKey.Media,
        NavEntryKey.Music,
        NavEntryKey.Bookshelf,
        NavEntryKey.Downloads -> RailGroup.Content
        NavEntryKey.Console,
        NavEntryKey.Metadata -> RailGroup.Manage
        NavEntryKey.Settings -> RailGroup.Pinned
    }

/**
 * 侧栏 / 抽屉点某个服务器媒体库时的落点模式页（W53 追加「临时库视图」；纯逻辑，单测覆盖）。
 *
 * - **视频库**（movies / tvshows / homevideos / 混合「其他」）→ 视频页直显该库内容网格；
 * - **音乐库** → 音乐模式按该库加载；
 * - **书籍库** → 书架按该库加载；
 * - **其余**（Playlists 等）→ 通用库内容页。
 *
 * null = 不是模式页库（走通用库内容页）。
 */
enum class TemporaryLibraryKind {
    Video,
    Music,
    Books,
}

/** 侧栏库子项 → 临时库视图归属（纯逻辑，单测覆盖）。 */
fun temporaryLibraryKindOf(type: CollectionType): TemporaryLibraryKind? =
    when (type) {
        CollectionType.Movies,
        CollectionType.TvShows,
        CollectionType.HomeVideos,
        // 「其他」= Jellyfin 混合库（collectionType 为 null），用户口径归视频页。
        CollectionType.Mixed -> TemporaryLibraryKind.Video
        CollectionType.Music -> TemporaryLibraryKind.Music
        CollectionType.Books -> TemporaryLibraryKind.Books
        else -> null
    }

/**
 * 临时库视图顶栏的「类型」文案资源（纯逻辑，单测覆盖）。
 *
 * 与 [temporaryLibraryKindOf] 分开：通用库内容页（Playlists / 合集 / 文件夹等）也要显示类型。
 */
fun libraryTypeLabelRes(type: CollectionType): Int =
    when (type) {
        CollectionType.Movies -> CoreR.string.library_type_movies
        CollectionType.TvShows -> CoreR.string.library_type_tvshows
        CollectionType.HomeVideos -> CoreR.string.library_type_homevideos
        CollectionType.Music -> CoreR.string.library_type_music
        CollectionType.Books -> CoreR.string.library_type_books
        CollectionType.Playlists -> CoreR.string.library_type_playlists
        CollectionType.BoxSets -> CoreR.string.library_type_boxsets
        else -> CoreR.string.library_type_mixed
    }

/**
 * 侧栏 / 抽屉点某个服务器媒体库时的落点路由（纯函数，单测覆盖）。
 *
 * 模式页库（视频 / 音乐 / 书籍）返回 [TemporaryLibraryRoute]（临时库视图：不写偏好、返回键先回默认库）； 其余类型返回 [LibraryRoute]（参数里带 id
 * / 名称 / 类型，导航侧必须按参数新建条目——不能走统一入口的 `saveState + restoreState`，否则旧条目会把新参数顶掉，W53 Bug B2）。
 */
fun libraryEntryRoute(
    libraryId: String,
    libraryName: String,
    libraryType: CollectionType,
): Any {
    val kind = temporaryLibraryKindOf(libraryType)
    return if (kind != null) {
        TemporaryLibraryRoute(
            libraryId = libraryId,
            libraryName = libraryName,
            kind = kind,
            libraryType = libraryType.type,
        )
    } else {
        LibraryRoute(
            libraryId = libraryId,
            libraryName = libraryName,
            libraryType = libraryType,
        )
    }
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
