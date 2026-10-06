package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.settings.domain.PlayerDecodeFallback
import com.zhangwenkang.cinefin.settings.domain.PlayerStreamingQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---------- W18：手动选内核不关闭回退链（用户实测反馈） ----------

    @Test
    fun manualExoPlayer_codecFailureStillDescends() {
        // 解码面板手动切 ExoPlayer：硬解报「解不了这个格式」→ 仍要按链路降到服务器转码
        assertEquals(
            PlayerDecodeFallback.STAGE_SERVER_TRANSCODE,
            PlayerDecodeFallback.stageAfterFailure(
                stage = PlayerDecodeFallback.STAGE_NONE,
                backend = PlayerDecodeFallback.BACKEND_EXOPLAYER,
                bitratePreference = PlayerStreamingQuality.AUTO,
                codecCapabilityError = true,
            ),
        )
        // 「原始画质」= 用户明确只直连：跳过服务器转码，直接落本地软解
        assertEquals(
            PlayerDecodeFallback.STAGE_LOCAL_SOFTWARE,
            PlayerDecodeFallback.stageAfterFailure(
                stage = PlayerDecodeFallback.STAGE_NONE,
                backend = PlayerDecodeFallback.BACKEND_EXOPLAYER,
                bitratePreference = PlayerStreamingQuality.ORIGINAL,
                codecCapabilityError = true,
            ),
        )
    }

    @Test
    fun manualExoPlayer_networkErrorDoesNotSwitchKernel() {
        // 第 1 档的网络 / DRM 类错误换内核救不了：直接报错，不进回退链
        assertNull(
            PlayerDecodeFallback.stageAfterFailure(
                stage = PlayerDecodeFallback.STAGE_NONE,
                backend = PlayerDecodeFallback.BACKEND_EXOPLAYER,
                bitratePreference = PlayerStreamingQuality.AUTO,
                codecCapabilityError = false,
            )
        )
    }

    @Test
    fun manualMpv_failureAlsoDescends() {
        // 手动切 mpv：mpv 上报的错误没有错误码，同样按链路下降（自动档 → 服务器转码）
        assertEquals(
            PlayerDecodeFallback.STAGE_SERVER_TRANSCODE,
            PlayerDecodeFallback.stageAfterFailure(
                stage = PlayerDecodeFallback.STAGE_NONE,
                backend = PlayerDecodeFallback.BACKEND_MPV,
                bitratePreference = PlayerStreamingQuality.AUTO,
                codecCapabilityError = false,
            ),
        )
        // 原始画质 / 具体 Mbps：本地已经不可能再硬解，直接落 mpv 软解
        assertEquals(
            PlayerDecodeFallback.STAGE_LOCAL_SOFTWARE,
            PlayerDecodeFallback.stageAfterFailure(
                stage = PlayerDecodeFallback.STAGE_NONE,
                backend = PlayerDecodeFallback.BACKEND_MPV,
                bitratePreference = 8L,
                codecCapabilityError = false,
            ),
        )
    }

    @Test
    fun serverTranscodeStageFailure_anyNonNetworkErrorFallsToLocalSoftware() {
        // 第 2 档（服务器转码流）失败不挑错误码：解码失败等继续降到本地软解（网络错误除外，见下一条）
        for (codecError in listOf(true, false)) {
            assertEquals(
                PlayerDecodeFallback.STAGE_LOCAL_SOFTWARE,
                PlayerDecodeFallback.stageAfterFailure(
                    stage = PlayerDecodeFallback.STAGE_SERVER_TRANSCODE,
                    backend = PlayerDecodeFallback.BACKEND_EXOPLAYER,
                    bitratePreference = PlayerStreamingQuality.AUTO,
                    codecCapabilityError = codecError,
                    networkError = false,
                ),
            )
        }
    }

    @Test
    fun networkError_neverFallsBackToAnotherBackend() {
        // W73（#8）：网络 / IO 错误换内核救不了，任何档位都不回退（调用方原地重试 / 重开转码会话）
        assertNull(
            PlayerDecodeFallback.stageAfterFailure(
                stage = PlayerDecodeFallback.STAGE_SERVER_TRANSCODE,
                backend = PlayerDecodeFallback.BACKEND_EXOPLAYER,
                bitratePreference = PlayerStreamingQuality.AUTO,
                codecCapabilityError = false,
                networkError = true,
            )
        )
        assertNull(
            PlayerDecodeFallback.stageAfterFailure(
                stage = PlayerDecodeFallback.STAGE_NONE,
                backend = PlayerDecodeFallback.BACKEND_EXOPLAYER,
                bitratePreference = PlayerStreamingQuality.AUTO,
                codecCapabilityError = true,
                networkError = true,
            )
        )
        assertNull(
            PlayerDecodeFallback.stageAfterFailure(
                stage = PlayerDecodeFallback.STAGE_NONE,
                backend = PlayerDecodeFallback.BACKEND_MPV,
                bitratePreference = PlayerStreamingQuality.AUTO,
                codecCapabilityError = false,
                networkError = true,
            )
        )
    }

    @Test
    fun onlyAllLinksFailed_reportsError() {
        // 第 3 档（本地软解）再失败 = 链路全败：返回 null，由调用方显示错误卡片
        assertNull(
            PlayerDecodeFallback.stageAfterFailure(
                stage = PlayerDecodeFallback.STAGE_LOCAL_SOFTWARE,
                backend = PlayerDecodeFallback.BACKEND_EXOPLAYER,
                bitratePreference = PlayerStreamingQuality.AUTO,
                codecCapabilityError = true,
            )
        )
        assertNull(
            PlayerDecodeFallback.stageAfterFailure(
                stage = PlayerDecodeFallback.STAGE_LOCAL_SOFTWARE,
                backend = PlayerDecodeFallback.BACKEND_MPV,
                bitratePreference = PlayerStreamingQuality.AUTO,
                codecCapabilityError = false,
            )
        )
    }

    @Test
    fun duplicateFailureReport_isDedupedSoOneFailureAdvancesOnlyOneStage() {
        // mpv 一次打开失败会连发 2–3 条 END_FILE：只有第一条能推进档位，否则 0 → 2 跳过服务器转码
        val key = PlayerDecodeFallback.failureKey(PlayerDecodeFallback.BACKEND_MPV, 0)
        assertTrue(
            PlayerDecodeFallback.isDuplicateFailure(
                lastKey = key,
                lastHandledAtMs = 1_000L,
                backend = PlayerDecodeFallback.BACKEND_MPV,
                stage = 0,
                nowMs = 1_800L,
            )
        )
        // 换了内核 / 档位，或过了去重窗口 = 新的失败，不吞
        assertFalse(
            PlayerDecodeFallback.isDuplicateFailure(
                lastKey = key,
                lastHandledAtMs = 1_000L,
                backend = PlayerDecodeFallback.BACKEND_EXOPLAYER,
                stage = 0,
                nowMs = 1_800L,
            )
        )
        assertFalse(
            PlayerDecodeFallback.isDuplicateFailure(
                lastKey = key,
                lastHandledAtMs = 1_000L,
                backend = PlayerDecodeFallback.BACKEND_MPV,
                stage = 1,
                nowMs = 1_800L,
            )
        )
        assertFalse(
            PlayerDecodeFallback.isDuplicateFailure(
                lastKey = key,
                lastHandledAtMs = 1_000L,
                backend = PlayerDecodeFallback.BACKEND_MPV,
                stage = 0,
                nowMs = 1_000L + PlayerDecodeFallback.DUPLICATE_FAILURE_WINDOW_MS,
            )
        )
        assertFalse(
            PlayerDecodeFallback.isDuplicateFailure(
                lastKey = null,
                lastHandledAtMs = 0L,
                backend = PlayerDecodeFallback.BACKEND_MPV,
                stage = 0,
                nowMs = 100L,
            )
        )
    }

    // ---------- W19：失败自动回退开关 + 循环保护（重启守卫） ----------

    @Test
    fun autoFallbackDisabled_neverTakesOverSoErrorCardShows() {
        // 关 = 强制所选内核：即使链路还能降级（自动档 0 → 1），也不接管、不重启
        assertNull(
            PlayerDecodeFallback.fallbackDecision(
                autoFallbackEnabled = false,
                candidateStage = PlayerDecodeFallback.STAGE_SERVER_TRANSCODE,
                restartGuardExceeded = false,
            )
        )
        // 开 = 原样采用链路判定
        assertEquals(
            PlayerDecodeFallback.STAGE_SERVER_TRANSCODE,
            PlayerDecodeFallback.fallbackDecision(
                autoFallbackEnabled = true,
                candidateStage = PlayerDecodeFallback.STAGE_SERVER_TRANSCODE,
                restartGuardExceeded = false,
            ),
        )
        // 链路本身已用尽（第 3 档失败）：开 / 关都进错误卡片
        assertNull(
            PlayerDecodeFallback.fallbackDecision(
                autoFallbackEnabled = true,
                candidateStage = null,
                restartGuardExceeded = false,
            )
        )
    }

    @Test
    fun restartGuard_countsSameMediaAndSameTargetStage() {
        // 第一次重启（同一媒体 + 目标 1）：计数 1
        val first = PlayerDecodeFallback.recordRestart(null, "ep-1", 1)
        assertEquals(PlayerDecodeFallback.RestartGuard("ep-1", 1, 1), first)
        assertFalse(first.exceeded())
        // 同一媒体 + 同一目标再来：计数递增，达到上限（2）仍允许
        val second = PlayerDecodeFallback.recordRestart(first, "ep-1", 1)
        assertEquals(2, second.attempts)
        assertFalse(second.exceeded())
        // 第 3 次 = 判定循环，交给错误卡片（不再重启）
        val third = PlayerDecodeFallback.recordRestart(second, "ep-1", 1)
        assertTrue(third.exceeded())
        // 换目标档位（正常的 1 → 2 降级）或换媒体：从 1 重新计数
        assertEquals(1, PlayerDecodeFallback.recordRestart(second, "ep-1", 2).attempts)
        assertEquals(1, PlayerDecodeFallback.recordRestart(second, "ep-2", 1).attempts)
        // 循环保护叠加在决策上：超限 → 不接管
        assertNull(
            PlayerDecodeFallback.fallbackDecision(
                autoFallbackEnabled = true,
                candidateStage = PlayerDecodeFallback.STAGE_SERVER_TRANSCODE,
                restartGuardExceeded = third.exceeded(),
            )
        )
    }

    @Test
    fun restartGuard_roundTripsThroughPreferenceString() {
        val guard = PlayerDecodeFallback.RestartGuard("media-9", 2, 1)
        val raw = PlayerDecodeFallback.formatGuard(guard)
        assertEquals(guard, PlayerDecodeFallback.parseGuard(raw))
        // 空 / 损坏数据按「没有历史」处理（不误伤正常链路）
        assertNull(PlayerDecodeFallback.parseGuard(""))
        assertNull(PlayerDecodeFallback.parseGuard(null))
        assertNull(PlayerDecodeFallback.parseGuard("media-9|2"))
        assertNull(PlayerDecodeFallback.parseGuard("|2|1"))
        assertNull(PlayerDecodeFallback.parseGuard("media-9|2|0"))
        assertNull(PlayerDecodeFallback.parseGuard("media-9|x|1"))
    }
}
