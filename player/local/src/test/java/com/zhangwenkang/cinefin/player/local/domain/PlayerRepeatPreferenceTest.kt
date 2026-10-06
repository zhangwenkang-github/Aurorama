package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W74 U2-B（决策 D-F8）：循环 / 随机持久化的取值与默认值回归。
 *
 * 覆盖两件事：① 面板四档 ↔ 内核 (repeatMode, shuffle) 的换算；② 偏好键默认值 = 顺序 / 关（用户拍板）。
 */
class PlayerRepeatPreferenceTest {

    @Test
    fun `默认值是顺序播放且不随机`() {
        assertEquals(
            "默认循环模式 = 顺序（0，与 Player.REPEAT_MODE_OFF 对齐）",
            Player.REPEAT_MODE_OFF,
            PlayerExtraPreferences.repeatMode.defaultValue,
        )
        assertFalse("默认不随机", PlayerExtraPreferences.shuffle.defaultValue)
        assertEquals(
            "新键名不能改（已落盘语义）",
            "pref_player_repeat_mode",
            PlayerExtraPreferences.repeatMode.backendName,
        )
        assertEquals(
            "新键名不能改（已落盘语义）",
            "pref_player_shuffle",
            PlayerExtraPreferences.shuffle.backendName,
        )
    }

    @Test
    fun `面板四档换算成内核取值`() {
        assertEquals(Player.REPEAT_MODE_OFF, PlayerRepeatChoice.OFF.toRepeatMode())
        assertFalse(PlayerRepeatChoice.OFF.toShuffleEnabled())

        assertEquals(Player.REPEAT_MODE_ALL, PlayerRepeatChoice.ALL.toRepeatMode())
        assertFalse(PlayerRepeatChoice.ALL.toShuffleEnabled())

        assertEquals(Player.REPEAT_MODE_ONE, PlayerRepeatChoice.ONE.toRepeatMode())
        assertFalse(PlayerRepeatChoice.ONE.toShuffleEnabled())

        // 随机 = 一轮播完接着下一轮（ALL）+ 内核 shuffle
        assertEquals(Player.REPEAT_MODE_ALL, PlayerRepeatChoice.SHUFFLE.toRepeatMode())
        assertTrue(PlayerRepeatChoice.SHUFFLE.toShuffleEnabled())
    }

    @Test
    fun `内核取值反推面板档位`() {
        assertEquals(
            PlayerRepeatChoice.OFF,
            PlayerRepeatChoice.of(Player.REPEAT_MODE_OFF, shuffleEnabled = false),
        )
        assertEquals(
            PlayerRepeatChoice.ALL,
            PlayerRepeatChoice.of(Player.REPEAT_MODE_ALL, shuffleEnabled = false),
        )
        assertEquals(
            PlayerRepeatChoice.ONE,
            PlayerRepeatChoice.of(Player.REPEAT_MODE_ONE, shuffleEnabled = false),
        )
        // shuffle 打开时优先显示随机（单集循环 + 随机不是合法组合，与面板判定一致）
        assertEquals(
            PlayerRepeatChoice.SHUFFLE,
            PlayerRepeatChoice.of(Player.REPEAT_MODE_ALL, shuffleEnabled = true),
        )
        assertEquals(
            PlayerRepeatChoice.SHUFFLE,
            PlayerRepeatChoice.of(Player.REPEAT_MODE_ONE, shuffleEnabled = true),
        )
        // 未知值兜底成顺序，避免面板出现「四档都不选中」
        assertEquals(
            PlayerRepeatChoice.OFF,
            PlayerRepeatChoice.of(repeatMode = 99, shuffleEnabled = false),
        )
    }

    @Test
    fun `四档与内核取值往返一致`() {
        PlayerRepeatChoice.entries.forEach { choice ->
            assertEquals(
                "$choice 往返后应还原",
                choice,
                PlayerRepeatChoice.of(choice.toRepeatMode(), choice.toShuffleEnabled()),
            )
        }
    }
}
