package com.zhangwenkang.cinefin.presentation.player

import com.zhangwenkang.cinefin.settings.domain.PlaybackSpeedOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W78（#39）：倍速档位统一为共享常量并补 1.75×。 */
class PlaybackSpeedOptionsTest {

    @Test
    fun `播放页档位升序且包含 1_75 倍`() {
        val speeds = PlaybackSpeedOptions.PLAYBACK

        assertTrue("档位表必须含 1.75×", speeds.contains(1.75f))
        assertEquals("档位必须升序且无重复", speeds.sorted(), speeds)
        assertEquals("档位数必须去重", speeds.toSet().size, speeds.size)
        assertTrue("必须保留 1×（默认档）", speeds.contains(1f))
    }

    @Test
    fun `长按倍速档位含 1_75 倍且是播放档位的子集`() {
        val longPress = PlaybackSpeedOptions.LONG_PRESS

        assertTrue(longPress.contains(1.75f))
        assertEquals(longPress.sorted(), longPress)
        assertTrue(
            "长按档位必须是播放档位的子集（同一批数值，不允许各写一份）",
            PlaybackSpeedOptions.PLAYBACK.containsAll(longPress),
        )
        // 长按倍速是"加速"语义，不应出现小于 1× 的档位
        assertTrue(longPress.all { speed -> speed >= 1f })
    }

    /**
     * 关键回归：1.75 必须原样落盘。
     *
     * 若用 `String.format("%.1f")` 会写成 `"1.8"`，`PlayerGestureHelper` 读回 `toFloatOrNull()` 得到 1.8 ——
     * 档位与选定值不再一致（且长按速度被悄悄改掉）。
     */
    @Test
    fun `落盘值必须能无损读回且与旧资源数组兼容`() {
        assertEquals("1.75", PlaybackSpeedOptions.storageValue(1.75f))
        assertEquals("1.5", PlaybackSpeedOptions.storageValue(1.5f))
        // 与旧 `gesture_speed_multiplier_values` 资源数组逐字一致（存量偏好无需迁移）
        assertEquals("2.0", PlaybackSpeedOptions.storageValue(2f))
        assertEquals("3.0", PlaybackSpeedOptions.storageValue(3f))

        PlaybackSpeedOptions.LONG_PRESS.forEach { speed ->
            assertEquals(
                "落盘值必须能无损解析回原档位",
                speed,
                PlaybackSpeedOptions.storageValue(speed).toFloatOrNull(),
            )
        }
    }

    @Test
    fun `档位文案整数不带小数`() {
        assertEquals("1×", PlaybackSpeedOptions.label(1f))
        assertEquals("2×", PlaybackSpeedOptions.label(2f))
        assertEquals("1.5×", PlaybackSpeedOptions.label(1.5f))
        assertEquals("1.75×", PlaybackSpeedOptions.label(1.75f))
        assertEquals("0.25×", PlaybackSpeedOptions.label(0.25f))
    }
}
