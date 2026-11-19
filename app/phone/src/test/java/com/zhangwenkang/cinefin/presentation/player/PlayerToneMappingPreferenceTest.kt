package com.zhangwenkang.cinefin.presentation.player

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.player.local.domain.PlayerExtraPreferences
import com.zhangwenkang.cinefin.player.local.domain.PlayerToneMapping
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.PlayerDecodeFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 色调映射偏好的读写回归（W4-T3b / VIDEO-09）。
 *
 * 走真实的 [PlayerSettingsController] + [AppPreferences]（内存版 SharedPreferences）：面板写、重开面板读，两端必须是同一个值；
 * 非法值不能落盘成脏值。Exo 置灰与否的判定在 `PlayerToneMapping.isAvailableForBackend`（player/local 单测覆盖），这里只钉面板侧接线。
 */
class PlayerToneMappingPreferenceTest {

    private val preferences = AppPreferences(FakeSharedPreferences())

    @Test
    fun defaultsToBt2390WhenNothingStored() {
        val controller = PlayerSettingsController(preferences) { PlayerDecodeFallback.BACKEND_MPV }
        assertEquals(PlayerToneMapping.BT_2390, controller.state.toneMapping)
    }

    @Test
    fun panelWrite_isPersistedAndReadBackByANewController() {
        val controller = PlayerSettingsController(preferences) { PlayerDecodeFallback.BACKEND_MPV }
        controller.setToneMapping(PlayerToneMapping.HABLE)

        assertEquals(PlayerToneMapping.HABLE, controller.state.toneMapping)
        assertEquals(
            PlayerToneMapping.HABLE,
            preferences.getValue(PlayerExtraPreferences.toneMapping),
        )

        // 重开播放页 / 重开面板 = 新控制器，值必须还在
        val reopened = PlayerSettingsController(preferences) { PlayerDecodeFallback.BACKEND_MPV }
        assertEquals(PlayerToneMapping.HABLE, reopened.state.toneMapping)
    }

    @Test
    fun unknownValueIsNormalizedBeforeItReachesThePreference() {
        val controller = PlayerSettingsController(preferences) { PlayerDecodeFallback.BACKEND_MPV }
        controller.setToneMapping("reinhard")

        assertEquals(PlayerToneMapping.BT_2390, controller.state.toneMapping)
        assertEquals(
            PlayerToneMapping.BT_2390,
            preferences.getValue(PlayerExtraPreferences.toneMapping),
        )
    }

    @Test
    fun exoBackendDisablesTheToneMappingGroup() {
        // 面板按「实际生效内核」置灰：Exo 时三行都点不动，mpv 时恢复
        assertFalse(PlayerToneMapping.isAvailableForBackend(PlayerDecodeFallback.BACKEND_EXOPLAYER))
        assertTrue(PlayerToneMapping.isAvailableForBackend(PlayerDecodeFallback.BACKEND_MPV))
    }
}

/** 内存版 SharedPreferences（与既有播放器单测同款最小实现）。 */
private class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String, defValue: String?): String? =
        values[key] as String? ?: defValue

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            if (value == null) removals += key else updates[key] = value
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply {
            if (values == null) removals += key else updates[key] = values.toSet()
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }

        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        private fun applyChanges() {
            if (clearRequested) values.clear()
            removals.forEach { values.remove(it) }
            updates.forEach { (key, value) -> values[key] = value }
        }
    }
}
