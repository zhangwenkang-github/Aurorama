package com.zhangwenkang.cinefin.presentation.player

import com.zhangwenkang.cinefin.settings.domain.PlayerStreamingQuality
import com.zhangwenkang.cinefin.settings.domain.QualityPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 码率档位映射的回归测试（W86-A #42）。
 *
 * 档位同时被 data 层（构造 PlaybackInfo 请求）与播放页 UI（档位显示）使用：
 * - 自动 / 原始画质都必须保留「不限码率」（1 Gbps）的语义，不能把 0 当成 0 bps 发给服务器；
 * - 具体档位规范值统一为 bps；旧整数 Mbps 只允许通过一次性迁移进入新语义；
 * - 原始画质必须明确禁用转码（enableTranscoding=false）。
 */
class PlayerStreamingQualityTest {

    private val expectedPresets =
        listOf(
            QualityPreset(bitsPerSecond = 120_000_000L, labelHeight = 2160),
            QualityPreset(bitsPerSecond = 80_000_000L, labelHeight = 2160),
            QualityPreset(bitsPerSecond = 60_000_000L, labelHeight = 1080),
            QualityPreset(bitsPerSecond = 40_000_000L, labelHeight = 1080),
            QualityPreset(bitsPerSecond = 20_000_000L, labelHeight = 1080),
            QualityPreset(bitsPerSecond = 15_000_000L, labelHeight = 1080),
            QualityPreset(bitsPerSecond = 10_000_000L, labelHeight = 1080),
            QualityPreset(bitsPerSecond = 8_000_000L, labelHeight = 720),
            QualityPreset(bitsPerSecond = 6_000_000L, labelHeight = 720),
            QualityPreset(bitsPerSecond = 4_000_000L, labelHeight = 720),
            QualityPreset(bitsPerSecond = 3_000_000L, labelHeight = 480),
            QualityPreset(bitsPerSecond = 1_500_000L, labelHeight = 480),
            QualityPreset(bitsPerSecond = 720_000L, labelHeight = 480),
            QualityPreset(bitsPerSecond = 420_000L, labelHeight = 360),
        )

    @Test
    fun qualityPresets_matchAndroidPhoneV273SnapshotInOrder() {
        assertEquals(
            "必须使用 Android phone v2.7.3 的完整档表与固定顺序",
            expectedPresets,
            PlayerStreamingQuality.qualityPresets(),
        )
        assertEquals(
            "每个码率只能出现一次",
            expectedPresets.size,
            PlayerStreamingQuality.qualityPresets().map { it.bitsPerSecond }.distinct().size,
        )
    }

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
    fun presetBitrates_requestOnlyTheirExactBitsPerSecondValue() {
        PlayerStreamingQuality.qualityPresets().forEach { preset ->
            assertEquals(
                "${preset.bitsPerSecond} bps 档位必须以原值映射请求上限",
                preset.bitsPerSecond,
                PlayerStreamingQuality.maxStreamingBitrate(preset.bitsPerSecond),
            )
            assertTrue(
                "${preset.bitsPerSecond} bps 档位要请求服务器转码",
                PlayerStreamingQuality.transcodingEnabled(preset.bitsPerSecond) &&
                    PlayerStreamingQuality.requestsTranscoding(preset.bitsPerSecond),
            )
        }
    }

    @Test
    fun displayLabel_containsOfficialHeightAndBitrate() {
        assertEquals("2160p · 120 Mbps", PlayerStreamingQuality.bitrateLabel(120_000_000L))
        assertEquals("1080p · 40 Mbps", PlayerStreamingQuality.bitrateLabel(40_000_000L))
        assertEquals("720p · 8 Mbps", PlayerStreamingQuality.bitrateLabel(8_000_000L))
        assertEquals("480p · 1.5 Mbps", PlayerStreamingQuality.bitrateLabel(1_500_000L))
        assertEquals("480p · 720 kbps", PlayerStreamingQuality.bitrateLabel(720_000L))
        assertEquals("360p · 420 kbps", PlayerStreamingQuality.bitrateLabel(420_000L))
    }

    @Test
    fun label_keepsAutoAndOriginalSentinelsForResourceText() {
        assertEquals("0", PlayerStreamingQuality.bitrateLabel(PlayerStreamingQuality.AUTO))
        assertEquals("-1", PlayerStreamingQuality.bitrateLabel(PlayerStreamingQuality.ORIGINAL))
        assertEquals(
            PlayerStreamingQuality.AUTO,
            PlayerStreamingQuality.migrateLegacyMbpsToBps(PlayerStreamingQuality.AUTO),
        )
        assertEquals(
            PlayerStreamingQuality.ORIGINAL,
            PlayerStreamingQuality.migrateLegacyMbpsToBps(PlayerStreamingQuality.ORIGINAL),
        )
    }

    @Test
    fun legacyIntegerMbpsValues_migrateToNearestOfficialPresetWithoutExceedingRequest() {
        val expected =
            mapOf(
                1L to 720_000L,
                2L to 1_500_000L,
                3L to 3_000_000L,
                5L to 4_000_000L,
                8L to 8_000_000L,
                12L to 10_000_000L,
                20L to 20_000_000L,
                40L to 40_000_000L,
            )

        expected.forEach { (legacyMbps, expectedBps) ->
            assertEquals(
                "$legacyMbps Mbps 旧值必须先按旧语义换算，再向下对齐官方档",
                expectedBps,
                PlayerStreamingQuality.migrateLegacyMbpsToBps(legacyMbps),
            )
        }
    }

    @Test
    fun migratedEightMbps_isNotMultipliedAgain() {
        val migrated =
            PlayerStreamingQuality.resolveStoredPreference(
                legacyValue = 8L,
                bitsPerSecondValue = PlayerStreamingQuality.AUTO,
                migratedToBitsPerSecond = false,
            )

        assertEquals(8_000_000L, migrated)
        assertEquals(8_000_000L, PlayerStreamingQuality.maxStreamingBitrate(migrated))
        assertEquals(
            "已经迁移的 8,000,000 bps 重读时不得再乘 1,000,000",
            8_000_000L,
            PlayerStreamingQuality.resolveStoredPreference(
                legacyValue = 8L,
                bitsPerSecondValue = 8_000_000L,
                migratedToBitsPerSecond = true,
            ),
        )
    }

    @Test
    fun newSubMbpsValues_roundTripWithoutLegacyConversion() {
        listOf(1_500_000L, 720_000L, 420_000L).forEach { bps ->
            val stored =
                PlayerStreamingQuality.resolveStoredPreference(
                    legacyValue = 1L,
                    bitsPerSecondValue = bps,
                    migratedToBitsPerSecond = true,
                )

            assertEquals(bps, stored)
            assertEquals(bps, PlayerStreamingQuality.maxStreamingBitrate(stored))
        }
    }
}
