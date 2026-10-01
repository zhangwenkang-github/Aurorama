package com.zhangwenkang.cinefin.presentation.player

import com.zhangwenkang.cinefin.settings.domain.PlayerStreamingQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 码率档位映射的回归测试（W12 反馈 B）。
 *
 * 档位同时被 data 层（构造 PlaybackInfo 请求）与播放页 UI（档位显示）使用：
 * - 自动 / 原始画质都必须保留「不限码率」（1 Gbps）的语义，不能把 0 当成 0 bps 发给服务器；
 * - 只有具体 Mbps 才请求服务器转码；
 * - 原始画质必须明确禁用转码（enableTranscoding=false）。
 */
class PlayerStreamingQualityTest {

    @Test
    fun auto_keepsUnlimitedBitrateAndAllowsTranscoding() {
        assertEquals(
            "自动档必须是不限码率（不等于 0 bps）",
            PlayerStreamingQuality.UNLIMITED_BITRATE,
            PlayerStreamingQuality.maxStreamingBitrate(PlayerStreamingQuality.AUTO),
        )
        assertTrue(
            "自动档允许服务器在必要时转码",
            PlayerStreamingQuality.transcodingEnabled(PlayerStreamingQuality.AUTO),
        )
        assertFalse(
            "自动档不主动请求转码",
            PlayerStreamingQuality.requestsTranscoding(PlayerStreamingQuality.AUTO),
        )
    }

    @Test
    fun original_keepsUnlimitedBitrateButDisablesTranscoding() {
        assertEquals(
            PlayerStreamingQuality.UNLIMITED_BITRATE,
            PlayerStreamingQuality.maxStreamingBitrate(PlayerStreamingQuality.ORIGINAL),
        )
        assertFalse(
            "原始画质 = 只直连，禁用服务器转码",
            PlayerStreamingQuality.transcodingEnabled(PlayerStreamingQuality.ORIGINAL),
        )
    }

    @Test
    fun presetBitrates_mapToBitsPerSecondAndRequestTranscoding() {
        PlayerStreamingQuality.PRESET_MBPS.forEach { mbps ->
            val value = mbps.toLong()
            assertEquals(
                "$mbps Mbps 档位必须换算成 bps",
                mbps.toLong() * 1_000_000L,
                PlayerStreamingQuality.maxStreamingBitrate(value),
            )
            assertTrue(
                "$mbps Mbps 档位要请求服务器转码",
                PlayerStreamingQuality.transcodingEnabled(value) &&
                    PlayerStreamingQuality.requestsTranscoding(value),
            )
        }
    }

    @Test
    fun presetLadder_isAscendingAndCoversCommonBitrates() {
        val presets = PlayerStreamingQuality.PRESET_MBPS

        assertTrue("档位必须递增", presets.zipWithNext().all { (a, b) -> a < b })
        assertTrue("要覆盖低码率（≤2 Mbps）", presets.first() <= 2)
        assertTrue("要覆盖高码率（≥20 Mbps）", presets.last() >= 20)
        assertFalse("档位里不能混入 0（那是自动档的语义）", presets.contains(0))
    }

    @Test
    fun label_onlyPrintsMbpsForConcretePresets() {
        assertEquals("8 Mbps", PlayerStreamingQuality.bitrateLabel(8L))
        assertEquals("-1", PlayerStreamingQuality.bitrateLabel(PlayerStreamingQuality.ORIGINAL))
    }
}
