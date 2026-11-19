package com.zhangwenkang.cinefin.player.local.subtitle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VIDEO-11：Exo 路径下建议切 mpv 的编码判定。
 *
 * 命中集合 = ASS/SSA（样式与特效）+ PGS/VobSub 等图形字幕；SRT/WebVTT 等纯文本不提示。
 */
class SubtitleCodecHintsTest {

    @Test
    fun `ASS 与 SSA 触发提示`() {
        assertTrue(isMpvPreferredSubtitleCodec("ass"))
        assertTrue(isMpvPreferredSubtitleCodec("SSA"))
        assertTrue(isMpvPreferredSubtitleCodec(" ass "))
    }

    @Test
    fun `图形字幕编码触发提示`() {
        listOf(
                "pgs",
                "pgssub",
                "hdmv_pgs_subtitle",
                "vobsub",
                "dvdsub",
                "dvbsub",
                "xsub",
                "dvb_teletext",
            )
            .forEach { codec -> assertTrue(codec, isMpvPreferredSubtitleCodec(codec)) }
    }

    @Test
    fun `纯文本字幕不触发提示`() {
        listOf("subrip", "srt", "webvtt", "vtt", "mov_text", "text", "").forEach { codec ->
            assertFalse(codec, isMpvPreferredSubtitleCodec(codec))
        }
    }
}
