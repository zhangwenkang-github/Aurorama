package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.settings.domain.PlayerDecodeFallback
import com.zhangwenkang.cinefin.settings.domain.PlayerStreamingQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码回退链的回归测试（W16 · 用户拍板：本地硬解 → 服务器解码/转码 → 本地软解）。
 *
 * 这组断言就是「顺序不能反」的守卫：W12 的旧顺序（服务器转码优先）一旦被改回去，这里立刻红。
 */
class PlayerDecodeFallbackTest {

    @Test
    fun autoQuality_escalatesToServerTranscodeFirst() {
        assertEquals(
            PlayerDecodeFallback.STAGE_SERVER_TRANSCODE,
            PlayerDecodeFallback.nextStage(
                PlayerDecodeFallback.STAGE_NONE,
                PlayerStreamingQuality.AUTO,
            ),
        )
        assertTrue(
            PlayerDecodeFallback.forcesServerTranscode(PlayerDecodeFallback.STAGE_SERVER_TRANSCODE)
        )
        assertFalse(
            PlayerDecodeFallback.forcesLocalSoftware(PlayerDecodeFallback.STAGE_SERVER_TRANSCODE)
        )
    }

    @Test
    fun explicitMbpsAlreadyTranscodes_jumpsStraightToLocalSoftware() {
        // 用户显式选了具体 Mbps：服务器本来就在转码，硬解再失败只能落到本地软解
        assertEquals(
            PlayerDecodeFallback.STAGE_LOCAL_SOFTWARE,
            PlayerDecodeFallback.nextStage(PlayerDecodeFallback.STAGE_NONE, 8L),
        )
        assertTrue(
            PlayerDecodeFallback.forcesLocalSoftware(PlayerDecodeFallback.STAGE_LOCAL_SOFTWARE)
        )
    }

    @Test
    fun originalQuality_neverForcesServerTranscode() {
        // 原始画质 = 用户明确只直连：不回退到服务器转码
        assertFalse(
            PlayerDecodeFallback.serverTranscodeNext(
                PlayerDecodeFallback.STAGE_NONE,
                PlayerStreamingQuality.ORIGINAL,
            )
        )
        assertEquals(
            PlayerDecodeFallback.STAGE_LOCAL_SOFTWARE,
            PlayerDecodeFallback.nextStage(
                PlayerDecodeFallback.STAGE_NONE,
                PlayerStreamingQuality.ORIGINAL,
            ),
        )
    }

    @Test
    fun softwareStageIsTerminal_noFurtherEscalation() {
        // 已经到软解档：再报错也不再推进（防两个内核来回跳 / 死循环）
        assertEquals(
            PlayerDecodeFallback.STAGE_LOCAL_SOFTWARE,
            PlayerDecodeFallback.nextStage(
                PlayerDecodeFallback.STAGE_LOCAL_SOFTWARE,
                PlayerStreamingQuality.AUTO,
            ),
        )
    }

    @Test
    fun unknownStage_normalizesToHardware() {
        assertEquals(PlayerDecodeFallback.STAGE_NONE, PlayerDecodeFallback.normalize(99))
        assertEquals(PlayerDecodeFallback.STAGE_NONE, PlayerDecodeFallback.normalize(null))
        assertEquals(
            PlayerDecodeFallback.STAGE_SERVER_TRANSCODE,
            PlayerDecodeFallback.nextStage(99, PlayerStreamingQuality.AUTO),
        )
    }

    @Test
    fun priorityOrder_matchesUserDecision() {
        assertEquals(
            listOf("本地硬解", "服务器解码/转码", "本地软解"),
            PlayerDecodeFallback.PRIORITY,
        )
    }
}
