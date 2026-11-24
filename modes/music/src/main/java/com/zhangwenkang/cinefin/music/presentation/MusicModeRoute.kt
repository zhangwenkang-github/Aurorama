package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.core.presentation.components.LibrarySelectorOption
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicArtist
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.music.data.groupAlbums
import com.zhangwenkang.cinefin.music.data.groupArtists
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * 音乐模式路由契约（W1 R2）。
 *
 * 本目的地 = **音乐 Tab / 本地曲目起播**等入口：用「客户端设置 → 音乐库」偏好（偏好为空 = 自动，服务器上全部音乐库）。 侧栏 / 抽屉点**具体音乐库**走
 * [MusicLibraryRoute]（W53 Bug B1：两个目的地分开，避免同目的地不同参数被 `restoreState` 用旧参数顶掉——踩坑 30 同类）。
 */
@Serializable data object MusicModeRoute

/**
 * W82 #25：搜索结果里的服务器 / 本地曲目 → 音乐页并起播（返回仍回搜索结果）。
 *
 * W1-T08：[kind] / [title] 带上结果卡片的服务器条目类型（`BaseItemKind` 名称）与显示标题——专辑 / 艺术家 / 歌单
 * 结果要按类型进对应详情层；歌曲、本地曲目与已下载音乐保持「进音乐页并起播」。
 */
@Serializable
data class MusicItemRoute(
    val itemId: String,
    val queueItemIds: String? = null,
    val kind: String? = null,
    val title: String? = null,
)

/** W1-T08：音乐搜索结果在音乐页内要打开的详情层（`null` = 按曲目起播）。 */
internal sealed interface MusicRouteDetailTarget {
    data class Album(val name: String) : MusicRouteDetailTarget

    data class Artist(val name: String) : MusicRouteDetailTarget

    data class Playlist(val id: UUID) : MusicRouteDetailTarget
}

/**
 * 搜索结果条目类型 → 音乐页详情层（纯函数，单测覆盖）。
 *
 * 专辑 / 艺术家在客户端按名称聚合（服务器音乐库没有独立的专辑实体，见 `MusicRepository`），所以只有标题可用； 歌单直接用服务器条目 id。
 */
internal fun musicRouteDetailTarget(
    kind: String?,
    title: String?,
    itemId: UUID,
): MusicRouteDetailTarget? {
    val name = title?.takeIf { it.isNotBlank() }
    return when (kind?.uppercase()) {
        "MUSIC_ALBUM" -> name?.let { MusicRouteDetailTarget.Album(it) }
        "MUSIC_ARTIST" -> name?.let { MusicRouteDetailTarget.Artist(it) }
        "PLAYLIST" -> MusicRouteDetailTarget.Playlist(itemId)
        else -> null
    }
}

/** 专辑定位：优先已聚合列表（key = 专辑名），缺失时按曲目现聚合一份。 */
internal fun findMusicAlbum(
    albums: List<MusicAlbum>,
    songs: List<MusicSong>,
    name: String,
): MusicAlbum? =
    albums.firstOrNull { it.name == name }
        ?: groupAlbums(songs.filter { song -> song.albumName == name }).firstOrNull()

/** 艺术家定位：与 [MusicModeViewModel.openArtistByName] 同口径（已聚合列表 → 按曲目现聚合）。 */
internal fun findMusicArtist(
    artists: List<MusicArtist>,
    songs: List<MusicSong>,
    name: String,
): MusicArtist? =
    artists.firstOrNull { it.name == name }
        ?: groupArtists(songs.filter { song -> song.artist == name }).firstOrNull()

/** 下载页多选播放参数：保持选择顺序、去重，非法整体参数回落到首个曲目。 */
internal fun parseMusicQueueIds(raw: String?, fallback: UUID): List<UUID> {
    val parsed =
        raw?.split(',')
            ?.mapNotNull { token ->
                token
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            }
            .orEmpty()
            .distinct()
    return parsed.ifEmpty { listOf(fallback) }
}

/** 按下载页选择顺序组装曲目；同一 id 优先服务器曲库，再回落本地媒体库。 */
internal fun orderedMusicSongs(
    itemIds: List<UUID>,
    serverSongs: List<MusicSong>,
    localSongs: List<MusicSong>,
): List<MusicSong> {
    val serverById = serverSongs.associateBy { it.itemId }
    val localById = localSongs.associateBy { it.itemId }
    return itemIds.mapNotNull { id -> serverById[id] ?: localById[id] }
}

/**
 * 「指定音乐库」的音乐模式目的地（W53 Bug B1）。
 *
 * 服务器上可能有多个同类型音乐库（如「音乐」「音乐测试」），从侧栏 / 抽屉点进来时必须按点击的库加载、 顶栏显示该库名，不能落到「客户端设置 → 音乐库」偏好的那一个。
 */
@Serializable data class MusicLibraryRoute(val libraryId: String, val libraryName: String)

/** 路由参数字段名（SavedStateHandle 取参用，必须与 [MusicLibraryRoute] 属性名一致）。 */
const val MUSIC_ROUTE_LIBRARY_ID: String = "libraryId"

/** 音乐库 id 解析（纯逻辑，单测覆盖）：**路由参数优先**，其次客户端设置「音乐库」，都没有 / 非法 → null（自动）。 */
internal fun resolveMusicLibraryId(
    routeLibraryId: String?,
    preferredLibraryId: String?,
): UUID? =
    sequenceOf(routeLibraryId, preferredLibraryId)
        .mapNotNull { raw -> raw?.takeIf { it.isNotBlank() } }
        .mapNotNull { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
        .firstOrNull()

/** W66：「全部音乐库」选项文案（chip 与菜单一致；null = 不限定 parentId = 服务器全部音乐）。 */
internal const val ALL_MUSIC_LIBRARIES_LABEL: String = "全部音乐库"

/** W66：音乐库选择候选（纯函数，单测覆盖）：「全部音乐库」+ 各音乐库（服务器顺序，含项目数副文案）。 */
internal fun musicLibraryOptions(libraries: List<FindroidCollection>): List<LibrarySelectorOption> =
    buildList {
        add(LibrarySelectorOption(id = null, label = ALL_MUSIC_LIBRARIES_LABEL))
        libraries.forEach { library ->
            add(
                LibrarySelectorOption(
                    id = library.id,
                    label = library.name,
                    detail = library.itemCount?.let { count -> "共 $count 项" },
                )
            )
        }
    }

/** W66：服务器音乐库列表过滤（纯函数，单测覆盖）。 */
internal fun pickMusicLibraries(libraries: List<FindroidCollection>): List<FindroidCollection> =
    libraries.filter {
        it.type == CollectionType.Music
    }

/** W66：按偏好解析当前选中音乐库——非法 / 已失效 / 为空 → null（全部音乐库，chip 不点亮）。 */
internal fun resolveSelectedMusicLibrary(
    libraries: List<FindroidCollection>,
    preferredLibraryId: String?,
): FindroidCollection? {
    val id =
        preferredLibraryId
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() } ?: return null
    return libraries.firstOrNull { it.id == id }
}
