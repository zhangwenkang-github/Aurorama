package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.core.presentation.components.LibrarySelectorOption
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
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
