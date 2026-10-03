package com.zhangwenkang.cinefin.presentation.settings

import com.zhangwenkang.cinefin.settings.domain.models.Preference as PreferenceBackend
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceIntInput
import org.junit.Assert.assertEquals
import org.junit.Test

/** W57 设置数值输入范围钳制（并发 1–8 / 限速 0–100 共用同一入口）。 */
class PreferenceIntInputTest {

    private fun input(range: IntRange?) =
        PreferenceIntInput(
            nameStringResource = 0,
            backendPreference = PreferenceBackend("test_int_input", 0),
            valueRange = range,
        )

    @Test
    fun `无范围时不钳制`() {
        assertEquals(-5, input(null).coerceValue(-5))
    }

    @Test
    fun `越界输入按范围收敛`() {
        val concurrency = input(1..8)
        assertEquals(1, concurrency.coerceValue(0))
        assertEquals(5, concurrency.coerceValue(5))
        assertEquals(8, concurrency.coerceValue(99))

        val speedLimit = input(0..100)
        assertEquals(0, speedLimit.coerceValue(-10))
        assertEquals(100, speedLimit.coerceValue(500))
    }
}
