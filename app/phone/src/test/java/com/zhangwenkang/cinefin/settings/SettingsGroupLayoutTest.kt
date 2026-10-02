package com.zhangwenkang.cinefin.settings

import com.zhangwenkang.cinefin.settings.domain.models.Preference as PreferenceBackend
import com.zhangwenkang.cinefin.settings.presentation.models.Preference
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceCategory
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceSwitch
import com.zhangwenkang.cinefin.settings.presentation.settings.SettingsGroup
import com.zhangwenkang.cinefin.settings.presentation.settings.buildTopLevelPreferenceGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W42 客户端设置 5 组 IA：分桶顺序 / 组内顺序 / 不丢条目（纯逻辑）。 */
class SettingsGroupLayoutTest {
    private fun category(nameRes: Int) = PreferenceCategory(nameStringResource = nameRes)

    private fun toggle(nameRes: Int) =
        PreferenceSwitch(
            nameStringResource = nameRes,
            backendPreference = PreferenceBackend("pref_test", false),
        )

    private val items: List<Preference> =
        listOf(
            category(R.string.settings_category_servers),
            category(R.string.settings_category_network),
            category(R.string.settings_category_libraries),
            toggle(R.string.settings_local_library_visible),
            category(R.string.settings_category_downloads_cache),
            category(R.string.settings_category_player),
            category(R.string.settings_category_music),
            category(R.string.settings_category_desktop_lyrics),
            toggle(R.string.settings_music_resume_queue),
            category(R.string.settings_category_language),
            category(R.string.settings_category_interface),
            category(R.string.settings_category_appearance),
            category(R.string.settings_category_sidebar),
            toggle(R.string.settings_hide_bottom_bar),
            category(R.string.settings_category_device),
            toggle(R.string.offline_mode),
            category(R.string.about),
        )

    @Test
    fun fiveGroupsInConfirmedOrder() {
        val groups = buildTopLevelPreferenceGroups(items)

        assertEquals(
            listOf(
                R.string.settings_group_account_server,
                R.string.settings_group_media,
                R.string.settings_group_playback,
                R.string.settings_group_interface,
                R.string.settings_group_other,
            ),
            groups.map { it.nameStringResource },
        )
        assertEquals(5, SettingsGroup.entries.size)
    }

    @Test
    fun eachGroupKeepsDeclaredRowOrder() {
        val groups = buildTopLevelPreferenceGroups(items)
        val rows = groups.map { group -> group.preferences.map { it.nameStringResource } }

        assertEquals(
            listOf(
                listOf(R.string.settings_category_servers, R.string.settings_category_network),
                listOf(
                    R.string.settings_category_libraries,
                    R.string.settings_local_library_visible,
                    R.string.settings_category_downloads_cache,
                ),
                listOf(
                    R.string.settings_category_player,
                    R.string.settings_category_music,
                    R.string.settings_category_desktop_lyrics,
                    R.string.settings_music_resume_queue,
                ),
                listOf(
                    R.string.settings_category_language,
                    R.string.settings_category_interface,
                    R.string.settings_category_appearance,
                    R.string.settings_category_sidebar,
                    R.string.settings_hide_bottom_bar,
                ),
                listOf(
                    R.string.settings_category_device,
                    R.string.offline_mode,
                    R.string.about,
                ),
            ),
            rows,
        )
    }

    @Test
    fun nothingIsLostAndUnknownEntriesFallIntoOther() {
        val extra = category(R.string.settings_category_cache)
        val groups = buildTopLevelPreferenceGroups(items + extra)
        val flattened = groups.flatMap { it.preferences }.map { it.nameStringResource }

        assertEquals(items.size + 1, flattened.size)
        assertEquals(flattened.toSet(), (items + extra).map { it.nameStringResource }.toSet())
        // 未在布局里列出的条目并入「其他」，不静默丢失。
        assertTrue(
            groups.last().preferences.any {
                it.nameStringResource == R.string.settings_category_cache
            }
        )
    }
}
