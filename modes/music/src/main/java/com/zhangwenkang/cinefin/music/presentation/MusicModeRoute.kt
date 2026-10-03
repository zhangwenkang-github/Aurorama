package com.zhangwenkang.cinefin.music.presentation

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
