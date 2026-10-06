package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.player.core.domain.models.PlayerMediaInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerMediaInfoFormatTest {

    @Test
    fun `取不到的字段统一显示破折号`() {
        assertEquals(MEDIA_INFO_UNKNOWN, PlayerMediaInfoFormat.container(null))
        assertEquals(MEDIA_INFO_UNKNOWN, PlayerMediaInfoFormat.codec("  "))
        assertEquals(MEDIA_INFO_UNKNOWN, PlayerMediaInfoFormat.resolution(null, 1080))
        assertEquals(MEDIA_INFO_UNKNOWN, PlayerMediaInfoFormat.resolution(0, 0))
        assertEquals(MEDIA_INFO_UNKNOWN, PlayerMediaInfoFormat.bitrate(null))
        assertEquals(MEDIA_INFO_UNKNOWN, PlayerMediaInfoFormat.bitrate(0))
        assertEquals(MEDIA_INFO_UNKNOWN, PlayerMediaInfoFormat.frameRate(null))
        assertEquals(MEDIA_INFO_UNKNOWN, PlayerMediaInfoFormat.frameRate(0f))
        assertEquals(MEDIA_INFO_UNKNOWN, PlayerMediaInfoFormat.hdr(""))
        assertEquals(MEDIA_INFO_UNKNOWN, PlayerMediaInfoFormat.audio(null, null, null, null))
        assertEquals(MEDIA_INFO_UNKNOWN, PlayerMediaInfoFormat.fileSize(null))
        assertEquals(MEDIA_INFO_UNKNOWN, PlayerMediaInfoFormat.fileSize(0L))
        assertEquals(MEDIA_INFO_UNKNOWN, PlayerMediaInfoFormat.path(null))
    }

    @Test
    fun `容器与编码归一化`() {
        assertEquals("MKV", PlayerMediaInfoFormat.container("matroska"))
        assertEquals("MP4", PlayerMediaInfoFormat.container("mov,mp4,m4a,3gp"))
        assertEquals("HLS", PlayerMediaInfoFormat.container("m3u8"))
        assertEquals("MPEG-TS", PlayerMediaInfoFormat.container("mpegts"))
        assertEquals("FLV", PlayerMediaInfoFormat.container("FLV"))

        assertEquals("HEVC（H.265）", PlayerMediaInfoFormat.codec("hevc"))
        assertEquals("HEVC（H.265）", PlayerMediaInfoFormat.codec("hvc1.1.6.L93.B0"))
        assertEquals("H.264（AVC）", PlayerMediaInfoFormat.codec("avc1.640028"))
        assertEquals("AV1", PlayerMediaInfoFormat.codec("av01"))
        assertEquals("VP9", PlayerMediaInfoFormat.codec("video/x-vnd.on2.vp9"))
        assertEquals("AAC", PlayerMediaInfoFormat.codec("audio/mp4a-latm"))
        assertEquals("E-AC-3", PlayerMediaInfoFormat.codec("ec-3"))
        assertEquals("DTS", PlayerMediaInfoFormat.codec("dts"))
    }

    @Test
    fun `数值格式化`() {
        assertEquals("1920 × 1080", PlayerMediaInfoFormat.resolution(1920, 1080))
        assertEquals("8.4 Mbps", PlayerMediaInfoFormat.bitrate(8_400_000))
        assertEquals("640 kbps", PlayerMediaInfoFormat.bitrate(640_000))
        assertEquals("820 kbps", PlayerMediaInfoFormat.bitrate(819_500))
        assertEquals("24 fps", PlayerMediaInfoFormat.frameRate(24f))
        assertEquals("23.976 fps", PlayerMediaInfoFormat.frameRate(23.976f))
        assertEquals("4.31 GB", PlayerMediaInfoFormat.fileSize(4_628_000_000L))
        assertEquals("700.0 MB", PlayerMediaInfoFormat.fileSize(734_003_200L))
        assertEquals("512 KB", PlayerMediaInfoFormat.fileSize(524_288L))
    }

    @Test
    fun `音频格式三要素与降级`() {
        assertEquals(
            "AAC · 2 声道 · 128 kbps",
            PlayerMediaInfoFormat.audio("aac", 2, 128_000, 48_000),
        )
        assertEquals("AC-3 · 6 声道 · 48.0 kHz", PlayerMediaInfoFormat.audio("ac3", 6, null, 48_000))
        assertEquals("FLAC", PlayerMediaInfoFormat.audio("flac", null, null, null))
    }

    @Test
    fun `路径去掉鉴权参数只留主机之后的部分`() {
        assertEquals(
            "/Videos/abc/stream.mkv",
            PlayerMediaInfoFormat.path(
                "https://jellyfins.zhangwenkang.com/Videos/abc/stream.mkv?api_key=secret&static=true"
            ),
        )
        assertEquals(
            "/Videos/abc/stream.mkv",
            PlayerMediaInfoFormat.path("http://host/Videos/abc/stream.mkv"),
        )
        assertEquals("Videos/abc", PlayerMediaInfoFormat.path("Videos/abc"))
    }

    @Test
    fun `HDR 判定覆盖双内核取值`() {
        // ExoPlayer：Media3 C.COLOR_TRANSFER_ST2084 = 6 / HLG = 7 / COLOR_SPACE_BT2020 = 6
        assertEquals("HDR10", hdrFromVideo(codecs = "hev1.2.4", colorTransfer = 6, colorSpace = 6))
        assertEquals("HLG", hdrFromVideo(codecs = "hev1.2.4", colorTransfer = 7, colorSpace = 6))
        assertEquals("SDR", hdrFromVideo(codecs = "avc1.640028", colorTransfer = 3, colorSpace = 1))
        assertEquals(
            "杜比视界",
            hdrFromVideo(codecs = "dvhe.05.06", colorTransfer = 6, colorSpace = 6),
        )
        assertNull(hdrFromVideo(codecs = null, colorTransfer = null, colorSpace = null))

        // mpv：video-params/gamma 与 primaries
        assertEquals("HDR10", hdrFromMpv("pq", "bt.2020", false))
        assertEquals("HLG", hdrFromMpv("hlg", "bt.2020", false))
        assertEquals("HDR（BT.2020）", hdrFromMpv(null, "bt.2020", false))
        assertEquals("SDR", hdrFromMpv("bt.1886", "bt.709", false))
        assertEquals("杜比视界", hdrFromMpv(null, null, true))
        assertNull(hdrFromMpv(null, null, false))

        // Jellyfin 媒体源侧
        assertEquals("杜比视界", hdrFromJellyfin("DOVIWithHDR10", null))
        assertEquals("HDR10", hdrFromJellyfin("HDR10", null))
        assertEquals("HLG", hdrFromJellyfin("HLG", null))
        assertEquals("SDR", hdrFromJellyfin("SDR", null))
        assertNull(hdrFromJellyfin("Unknown", null))
    }

    @Test
    fun `声道布局解析`() {
        assertEquals(2, channelsFromLayout("stereo"))
        assertEquals(6, channelsFromLayout("5.1"))
        assertEquals(8, channelsFromLayout("7.1"))
        assertEquals(4, channelsFromLayout("quad"))
        assertEquals(2, channelsFromLayout("2"))
        assertNull(channelsFromLayout(null))
        assertNull(channelsFromLayout(""))
    }

    @Test
    fun `合并快照以内核优先并保留媒体源独有字段`() {
        val source =
            PlayerMediaInfo(
                container = "mkv",
                videoCodec = "hevc",
                width = 1920,
                height = 1080,
                fileSizeBytes = 4_000_000_000L,
                path = "https://host/Videos/1/stream.mkv",
            )
        val kernel = PlayerMediaInfo(videoCodec = "avc1", frameRate = 23.976f, hdr = "SDR")

        val merged = mergePlayerMediaInfo(source, kernel)!!
        assertEquals("avc1", merged.videoCodec)
        assertEquals(23.976f, merged.frameRate!!, 0.001f)
        assertEquals("SDR", merged.hdr)
        assertEquals("mkv", merged.container)
        assertEquals(1920, merged.width)
        assertEquals(4_000_000_000L, merged.fileSizeBytes)
        assertEquals("https://host/Videos/1/stream.mkv", merged.path)

        assertEquals(source, mergePlayerMediaInfo(source, null))
        assertEquals(kernel, mergePlayerMediaInfo(null, kernel))
        assertNull(mergePlayerMediaInfo(null, null))
    }

    @Test
    fun `从地址推断容器`() {
        assertEquals("mkv", inferContainerFromUri("https://host/Videos/1/stream.mkv?api_key=x"))
        assertEquals("m3u8", inferContainerFromUri("https://host/Videos/1/master.m3u8"))
        assertNull(inferContainerFromUri("https://host/Videos/1/stream"))
        assertNull(inferContainerFromUri(null))
    }

    @Test
    fun `日志脱敏只替换鉴权 query 的值`() {
        // 普通直链：只动 api_key，其余 query 与 path 逐字保留
        assertEquals(
            "https://jellyfins.zhangwenkang.com/Videos/abc/stream.mkv?api_key=***&static=true",
            redactUrlSecrets(
                "https://jellyfins.zhangwenkang.com/Videos/abc/stream.mkv?api_key=SECRET&static=true"
            ),
        )
        // HLS 转码链：转码参数（非敏感）保持原样
        assertEquals(
            "http://host:8096/videos/1/master.m3u8?api_key=***&VideoCodec=h264&maxWidth=1920",
            redactUrlSecrets(
                "http://host:8096/videos/1/master.m3u8?api_key=abc123&VideoCodec=h264&maxWidth=1920"
            ),
        )
        // 多个鉴权参数：全部替换，参数顺序不变
        assertEquals(
            "https://host/x?api_key=***&b=1&X-Emby-Token=***",
            redactUrlSecrets("https://host/x?api_key=aaa&b=1&X-Emby-Token=bbb"),
        )
        // 无值的参数名保持原样（没有可泄露的值）
        assertEquals("https://host/x?api_key", redactUrlSecrets("https://host/x?api_key"))
    }

    @Test
    fun `日志脱敏命中大小写与分隔符混写`() {
        assertEquals(
            "https://host/videos/1/stream?ApiKey=***&X-Emby-Token=***&X-Emby-Authorization=***",
            redactUrlSecrets(
                "https://host/videos/1/stream?ApiKey=abc&X-Emby-Token=def&X-Emby-Authorization=ghi"
            ),
        )
        assertEquals(
            "https://host/videos/1/stream?API-KEY=***&Access_Token=***",
            redactUrlSecrets("https://host/videos/1/stream?API-KEY=abc&Access_Token=def"),
        )
    }

    @Test
    fun `日志脱敏对无鉴权参数与非法输入原样返回不崩溃`() {
        assertEquals(
            "https://host/Videos/1/stream.mkv?static=true",
            redactUrlSecrets("https://host/Videos/1/stream.mkv?static=true"),
        )
        assertEquals(
            "https://host/Videos/1/stream.mkv",
            redactUrlSecrets("https://host/Videos/1/stream.mkv"),
        )
        assertEquals("", redactUrlSecrets(""))
        assertEquals("   ", redactUrlSecrets("   "))
        assertEquals("not a url", redactUrlSecrets("not a url"))
        assertEquals("https://host/videos/1?", redactUrlSecrets("https://host/videos/1?"))
        assertEquals("api_key=abc", redactUrlSecrets("api_key=abc"))
        // fragment 不参与参数解析，但原样保留
        assertEquals(
            "https://host/videos/1/stream?api_key=***#t=10",
            redactUrlSecrets("https://host/videos/1/stream?api_key=abc#t=10"),
        )
    }
}
