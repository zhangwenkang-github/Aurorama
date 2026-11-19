package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.settings.domain.PlayerDecodeFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 色调映射偏好映射的回归（W4-T3b / VIDEO-09）。
 *
 * 三件事：① 偏好值 → mpv `tone-mapping` 属性值同名直传；② 非法值回落默认 `bt.2390`，绝不把脏值写进内核； ③ 仅 mpv 内核可调 （Exo 时面板置灰）。
 */
class PlayerToneMappingTest {

    @Test
    fun defaultIsBt2390() {
        assertEquals(PlayerToneMapping.BT_2390, PlayerToneMapping.normalize(null))
        assertEquals(
            PlayerToneMapping.BT_2390,
            PlayerExtraPreferences.toneMapping.defaultValue,
        )
        assertEquals(
            listOf("bt.2390", "mobius", "hable"),
            PlayerToneMapping.all,
        )
    }

    @Test
    fun preferenceValues_passThroughToMpvProperty() {
        PlayerToneMapping.all.forEach { mode ->
            assertEquals(mode, PlayerToneMapping.normalize(mode))
            assertEquals(mode, PlayerToneMapping.mpvPropertyValue(mode))
        }
    }

    @Test
    fun unknownPreferenceValue_fallsBackInsteadOfReachingMpv() {
        assertEquals(PlayerToneMapping.BT_2390, PlayerToneMapping.normalize("reinhard"))
        assertEquals(PlayerToneMapping.BT_2390, PlayerToneMapping.normalize(""))
        assertEquals(PlayerToneMapping.BT_2390, PlayerToneMapping.mpvPropertyValue("HABLE"))
    }

    @Test
    fun onlyMpvBackendCanChangeToneMapping() {
        assertTrue(PlayerToneMapping.isAvailableForBackend(PlayerDecodeFallback.BACKEND_MPV))
        assertFalse(PlayerToneMapping.isAvailableForBackend(PlayerDecodeFallback.BACKEND_EXOPLAYER))
        assertFalse(PlayerToneMapping.isAvailableForBackend(null))
        assertFalse(PlayerToneMapping.isAvailableForBackend("未知"))
    }
}
