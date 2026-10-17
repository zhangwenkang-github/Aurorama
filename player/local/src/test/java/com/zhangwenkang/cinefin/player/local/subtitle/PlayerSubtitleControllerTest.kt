package com.zhangwenkang.cinefin.player.local.subtitle

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.language.LanguageMatcher
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerSubtitleSource
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** controller 级换路语义：主→次 / 次→主清空原路，但不把「用户显式关闭」写脏。 */
class PlayerSubtitleControllerTest {

    private val sources =
        listOf(
            source(index = 3, title = "简体", language = "zh-Hans"),
            source(index = 4, title = "English", language = "en", codec = "subrip"),
        )

    private fun source(
        index: Int,
        title: String,
        language: String,
        codec: String = "ass",
    ) =
        PlayerSubtitleSource(
            index = index,
            title = title,
            language = language,
            uri = "file:///cinefin-test-$index.$codec",
            codec = codec,
        )

    @Test
    fun `主轨移到次路再移回主路都不写用户关闭态`() {
        val scope = CoroutineScope(Job())
        val controller = PlayerSubtitleController(scope, AppPreferences(FakeSharedPreferences()))
        try {
            controller.reset("media", sources, autoSecondary = false)
            val primary = controller.overlayState.value.primaryIndex
            assertNotNull(primary)

            controller.assignSecondary(primary!!)
            val secondaryOnly = controller.overlayState.value
            assertNull(secondaryOnly.primaryIndex)
            assertEquals(primary, secondaryOnly.secondaryIndex)
            assertFalse(secondaryOnly.disabledByUser)

            controller.assignPrimary(primary)
            val primaryOnly = controller.overlayState.value
            assertEquals(primary, primaryOnly.primaryIndex)
            assertNull(primaryOnly.secondaryIndex)
            assertFalse(primaryOnly.disabledByUser)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `显式关闭主字幕仍写用户关闭态`() {
        val scope = CoroutineScope(Job())
        val controller = PlayerSubtitleController(scope, AppPreferences(FakeSharedPreferences()))
        try {
            controller.reset("media", sources, autoSecondary = false)
            controller.selectPrimary(null)

            assertTrue(controller.overlayState.value.disabledByUser)
            assertNull(controller.overlayState.value.primaryIndex)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `手动 mpv 主字幕会写入主语言优先级`() {
        val scope = CoroutineScope(Job())
        val preferences = AppPreferences(FakeSharedPreferences())
        val controller = PlayerSubtitleController(scope, preferences)
        try {
            controller.reset("media", sources, autoSecondary = false)
            controller.rememberPrimaryLanguage(4)

            val priority =
                LanguageMatcher.parsePriority(
                    preferences.getValue(preferences.preferredSubtitleLanguages),
                    emptyList(),
                )
            assertEquals("en", priority.first())
        } finally {
            scope.cancel()
        }
    }
}

/** JUnit-only 测试用的内存 SharedPreferences；只实现 AppPreferences 实际读取 / 写入的入口。 */
private class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String> =
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet()
            ?: defValues
            ?: mutableSetOf()

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit
}

private class FakeEditor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {
    private val pending = mutableMapOf<String, Any?>()
    private val removed = mutableSetOf<String>()

    override fun putString(key: String?, value: String?): SharedPreferences.Editor = also {
        pending[key.orEmpty()] = value
    }

    override fun putStringSet(
        key: String?,
        values: MutableSet<String>?,
    ): SharedPreferences.Editor = also { pending[key.orEmpty()] = values?.toSet() }

    override fun putInt(key: String?, value: Int): SharedPreferences.Editor = also {
        pending[key.orEmpty()] = value
    }

    override fun putLong(key: String?, value: Long): SharedPreferences.Editor = also {
        pending[key.orEmpty()] = value
    }

    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = also {
        pending[key.orEmpty()] = value
    }

    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = also {
        pending[key.orEmpty()] = value
    }

    override fun remove(key: String?): SharedPreferences.Editor = also { removed += key.orEmpty() }

    override fun clear(): SharedPreferences.Editor = also {
        pending.clear()
        removed.addAll(values.keys)
    }

    override fun commit(): Boolean {
        removed.forEach(values::remove)
        values.putAll(pending)
        pending.clear()
        removed.clear()
        return true
    }

    override fun apply() {
        commit()
    }
}
