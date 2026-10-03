package com.zhangwenkang.cinefin.settings.presentation.settings

import androidx.annotation.StringRes
import com.zhangwenkang.cinefin.settings.R
import com.zhangwenkang.cinefin.settings.presentation.models.Preference
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceGroup

/**
 * 客户端设置的一级分组（W42，用户 2026-10-03 确认的 5 组 IA）。
 *
 * 顺序即展示顺序；标题走「小号次级灰」的分组头样式（见 `SettingsGroupCard`）。
 */
enum class SettingsGroup(@param:StringRes val titleRes: Int) {
    AccountServer(R.string.settings_group_account_server),
    Media(R.string.settings_group_media),
    Playback(R.string.settings_group_playback),
    Interface(R.string.settings_group_interface),
    Other(R.string.settings_group_other),
}

/**
 * 组内条目的固定顺序：按条目的 `nameStringResource` 寻址（**不按对象**）， 因此同一条目定义可以来自任意原始分类块，只要资源名对得上就会被收进对应分组。
 *
 * W46：「音乐」子页下线（音乐库选择移入「媒体库」子页），播放与音乐组只保留播放器 / 桌面歌词 / 恢复播放队列。
 */
val SETTINGS_GROUP_LAYOUT: List<Pair<SettingsGroup, List<Int>>> =
    listOf(
        SettingsGroup.AccountServer to
            listOf(
                R.string.settings_category_servers,
                R.string.settings_category_network,
                // TV 端专用（手机侧被 supportedDeviceTypes 过滤掉，手机入口由账号卡承接）。
                R.string.users,
            ),
        SettingsGroup.Media to
            listOf(
                R.string.settings_category_libraries,
                R.string.settings_local_library_visible,
                R.string.settings_category_downloads_cache,
            ),
        SettingsGroup.Playback to
            listOf(
                R.string.settings_category_player,
                R.string.settings_category_desktop_lyrics,
                R.string.settings_music_resume_queue,
            ),
        SettingsGroup.Interface to
            listOf(
                R.string.settings_category_language,
                R.string.settings_category_interface,
                R.string.settings_category_appearance,
                R.string.settings_category_sidebar,
                R.string.settings_hide_bottom_bar,
            ),
        SettingsGroup.Other to
            listOf(
                R.string.settings_category_device,
                R.string.offline_mode,
                R.string.about,
            ),
    )

/**
 * 把平铺的顶层条目按 [SETTINGS_GROUP_LAYOUT] 拼装成 5 组（纯逻辑，单测覆盖）。
 *
 * 规则：
 * 1. 每个 key 只消费**第一个**匹配条目，消费掉的条目不会重复出现在别的组；
 * 2. 未在布局里列出的条目（TV 等形态追加的项）全部并入「其他」，不静默丢失；
 * 3. 空分组不会产生（例如离线时被过滤掉的项），调用方仍需按 [PreferenceGroup.preferences] 过滤空组。
 */
fun buildTopLevelPreferenceGroups(preferences: List<Preference>): List<PreferenceGroup> {
    val remaining = preferences.toMutableList()
    val groups = SETTINGS_GROUP_LAYOUT.map { (group, keys) ->
        val items = keys.mapNotNull { key ->
            val index = remaining.indexOfFirst { it.nameStringResource == key }
            if (index >= 0) remaining.removeAt(index) else null
        }
        PreferenceGroup(nameStringResource = group.titleRes, preferences = items)
    }
    if (remaining.isEmpty()) return groups
    return groups.mapIndexed { index, group ->
        if (index == groups.lastIndex) {
            group.copy(preferences = group.preferences + remaining)
        } else {
            group
        }
    }
}
