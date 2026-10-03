package com.zhangwenkang.cinefin.presentation.navigation

import com.zhangwenkang.cinefin.LibraryRoute
import com.zhangwenkang.cinefin.TemporaryLibraryRoute
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.local.LocalLibrary
import com.zhangwenkang.cinefin.local.LocalLibraryType
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

/** 侧栏「本地媒体库」子分组的行（W53B）：本地库只在本机索引里，名称 + 项目数取自本地库数据。 */
data class SidebarLocalLibrary(
    val id: Long,
    val name: String,
    val type: LocalLibraryType,
    val itemCount: Int,
)

/**
 * 本地库列表 → 侧栏「本地媒体库」子分组行（W53B，纯函数 + 单测）。
 *
 * 只保留库级「在媒体库显示」打开的库（关掉的库不出现）；返回空列表时调用方把**整组**（标题 + 分隔 + 行）一起隐藏。顺序沿用仓库返回顺序（`createdAt` / id）。
 */
fun sidebarLocalLibraries(libraries: List<LocalLibrary>): List<SidebarLocalLibrary> =
    libraries
        .filter { it.visibleInLibrary }
        .map { library ->
            SidebarLocalLibrary(
                id = library.id,
                name = library.name,
                type = library.type,
                itemCount = library.itemCount,
            )
        }

/**
 * 侧轨展开态二级子项的「项目数」尾标是否显示（W53B，纯函数 + 单测）：**完整名称优先**。
 *
 * 名称放得下、且名称 + 间距 + 项目数一起放得下才显示项目数；否则省略项目数（不把库名截断成「音乐测…」）。 名称本身超出可用宽度时同样返回 false——此时名称仍走既有省略表现，属
 * 168dp 轨宽的尺寸边界。
 */
fun libraryChildCountVisible(
    labelWidthDp: Float,
    nameWidthDp: Float,
    countWidthDp: Float,
    gapDp: Float = RAIL_LIBRARY_COUNT_GAP_DP,
): Boolean = nameWidthDp <= labelWidthDp && nameWidthDp + gapDp + countWidthDp <= labelWidthDp

/** 侧轨展开态宽度（§4.2 / W46：168dp；与 `CinefinSideNavigation` 的 `Modifier.width` 同源）。 */
const val RAIL_EXPANDED_WIDTH_DP: Float = 168f

/** 侧轨导航列左右内边距（与 `CinefinSideNavigation` 的 `padding(horizontal)` 同源）。 */
const val RAIL_COLUMN_PADDING_DP: Float = 10f

/** 二级子项左缩进（`CinefinSpacing.Space4`）。 */
const val RAIL_SUBITEM_INDENT_DP: Float = 16f

/** 导航条目左右内边距（core `CinefinNavigationItem` 的 `padding(horizontal = 14.dp)`）。 */
const val NAV_ITEM_PADDING_DP: Float = 14f

/** 导航图标边长（core `NavIconWithBadge` 的 24dp）。 */
const val NAV_ICON_SIZE_DP: Float = 24f

/** 图标与文字间距（`CinefinSpacing.Space3`）。 */
const val NAV_ICON_GAP_DP: Float = 12f

/** 名称与项目数之间的最小留白（不足时省略项目数，宁可少画也不要挤）。 */
const val RAIL_LIBRARY_COUNT_GAP_DP: Float = 8f

/**
 * 侧轨展开态二级子项的文字可用宽度（dp，W53B 纯函数 + 单测）。
 *
 * 尺寸链 = 展开宽 [RAIL_EXPANDED_WIDTH_DP] − 导航列左右内边距 2×[RAIL_COLUMN_PADDING_DP] − 子项左缩进
 * [RAIL_SUBITEM_INDENT_DP] − 条目左右内边距 2×[NAV_ITEM_PADDING_DP] − 图标 [NAV_ICON_SIZE_DP] − 图标与文字间距
 * [NAV_ICON_GAP_DP]（168 − 20 − 16 − 28 − 24 − 12 = 68dp）。 任一处尺寸改动都要同步这里的常量（同 core
 * `rememberPageGutter` 的阈值同步约定）。
 */
fun railLibraryLabelWidthDp(): Float =
    RAIL_EXPANDED_WIDTH_DP -
        2 * RAIL_COLUMN_PADDING_DP -
        RAIL_SUBITEM_INDENT_DP -
        2 * NAV_ITEM_PADDING_DP -
        NAV_ICON_SIZE_DP -
        NAV_ICON_GAP_DP

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
