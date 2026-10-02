package com.zhangwenkang.cinefin.player.local.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W30-MUSIC-FX + W35：ReplayGain 标签解析（FLAC / ID3v2 / M4A·MP4 free-form / 本机覆盖）单测。 */
class ReplayGainTagParserTest {

    @Test
    fun `flac vorbis comment parses track and album gain`() {
        val bytes =
            flacWithComments(
                listOf(
                    "TITLE=Test",
                    "REPLAYGAIN_TRACK_GAIN=-7.25 dB",
                    "REPLAYGAIN_ALBUM_GAIN=-5.00 dB",
                )
            )
        val result = parseReplayGainBytes(bytes)
        assertEquals(-7.25f, result?.trackGainDb ?: 0f, 0.001f)
        assertEquals(-5.00f, result?.albumGainDb ?: 0f, 0.001f)
        assertEquals(ReplayGainSource.EMBEDDED, result?.source)
    }

    @Test
    fun `flac without replaygain returns null`() {
        val bytes = flacWithComments(listOf("TITLE=Test", "ARTIST=Someone"))
        assertNull(parseReplayGainBytes(bytes))
    }

    @Test
    fun `truncated flac returns null without crash`() {
        val bytes = flacWithComments(listOf("REPLAYGAIN_TRACK_GAIN=-7.25 dB")).copyOf(12)
        assertNull(parseReplayGainBytes(bytes))
    }

    @Test
    fun `id3 txxx replaygain parses`() {
        val bytes = id3WithTxxx("REPLAYGAIN_TRACK_GAIN", "-9.5 dB")
        val result = parseReplayGainBytes(bytes)
        assertEquals(-9.5f, result?.trackGainDb ?: 0f, 0.001f)
        assertNull(result?.albumGainDb)
    }

    @Test
    fun `override text parses track album and comments`() {
        val result =
            parseReplayGainOverride(
                """
                # 手动指定
                track=-6.5
                album=-8.0
                """
                    .trimIndent()
            )
        assertEquals(-6.5f, result?.trackGainDb ?: 0f, 0.001f)
        assertEquals(-8.0f, result?.albumGainDb ?: 0f, 0.001f)
        assertEquals(ReplayGainSource.LOCAL_OVERRIDE, result?.source)
        assertNull(parseReplayGainOverride("# 只有注释"))
    }

    @Test
    fun `gain text handles units and invalid input`() {
        assertEquals(-7.23f, parseGainDb("-7.23 dB") ?: 0f, 0.001f)
        assertEquals(3.5f, parseGainDb("+3.5") ?: 0f, 0.001f)
        assertEquals(-2.0f, parseGainDb("-2.0dB") ?: 0f, 0.001f)
        assertNull(parseGainDb("loud"))
        assertNull(parseGainDb("999"))
    }

    @Test
    fun `mp4 freeform atoms parse track and album gain`() {
        val bytes =
            mp4WithFreeform(
                "REPLAYGAIN_TRACK_GAIN" to "-7.25 dB",
                "REPLAYGAIN_ALBUM_GAIN" to "-5.00 dB",
            )
        val result = parseMp4ReplayGain(bytes)
        assertEquals(true, result.moovFound)
        assertEquals("-7.25 dB", result.tags?.get("REPLAYGAIN_TRACK_GAIN"))
        assertEquals("-5.00 dB", result.tags?.get("REPLAYGAIN_ALBUM_GAIN"))
        assertEquals(-7.25f, result.toTrackReplayGain()?.trackGainDb ?: 0f, 0.001f)
        assertEquals(ReplayGainSource.EMBEDDED, result.toTrackReplayGain()?.source)
    }

    @Test
    fun `mp4 freeform name is case insensitive and unit suffix is handled`() {
        val bytes = mp4WithFreeform("replaygain_track_gain" to "-3.0 dB")
        val result = parseMp4ReplayGain(bytes)
        assertEquals(-3.0f, result.toTrackReplayGain()?.trackGainDb ?: 0f, 0.001f)
    }

    @Test
    fun `mp4 non replaygain freeform is ignored`() {
        val bytes = mp4WithFreeform("TITLE" to "Test")
        val result = parseMp4ReplayGain(bytes)
        assertEquals(true, result.moovFound)
        assertNull(result.tags)
        assertNull(result.toTrackReplayGain())
    }

    @Test
    fun `mp4 with moov at end is located via top level box chain`() {
        // ftyp + mdat + moov：模拟非 faststart 文件，头部窗口装不下 mdat，靠声明长度算出 moov 偏移。
        val ftyp = mp4Box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII) + ByteArray(4))
        val mdat = mp4Box("mdat", ByteArray(300))
        val moov = mp4Moov("REPLAYGAIN_TRACK_GAIN" to "-4.5 dB")
        val file = ftyp + mdat + moov
        val prefix = file.copyOf(ftyp.size + 16)
        val scan = scanMp4TopLevelBoxes(prefix)
        assertEquals(-1, scan.moovStart)
        assertEquals((ftyp.size + mdat.size).toLong(), scan.nextBoxOffset)
        val window = file.copyOfRange(scan.nextBoxOffset!!.toInt(), file.size)
        val result = parseMp4ReplayGain(window)
        assertEquals(true, result.moovFound)
        assertEquals(-4.5f, result.toTrackReplayGain()?.trackGainDb ?: 0f, 0.001f)
    }

    @Test
    fun `truncated mp4 returns null without crash`() {
        val bytes = mp4WithFreeform("REPLAYGAIN_TRACK_GAIN" to "-4.5 dB").copyOf(12)
        val result = parseMp4ReplayGain(bytes)
        assertEquals(false, result.moovFound)
        assertNull(result.tags)
        assertNull(result.toTrackReplayGain())
    }

    @Test
    fun `override encode and parse round trip`() {
        val text = encodeReplayGainOverride(-6.5f, -8.0f)
        val parsed = parseReplayGainOverride(text ?: "")
        assertEquals(-6.5f, parsed?.trackGainDb ?: 0f, 0.001f)
        assertEquals(-8.0f, parsed?.albumGainDb ?: 0f, 0.001f)
        assertNull(encodeReplayGainOverride(null, null))
        val trackOnly = encodeReplayGainOverride(3.0f, null)
        assertEquals("track=3.0\n", trackOnly)
    }

    private fun flacWithComments(comments: List<String>): ByteArray {
        val body = buildVorbisComment("w30-test", comments)
        val blockHeader =
            byteArrayOf(
                (0x80 or 4).toByte(),
                ((body.size shr 16) and 0xFF).toByte(),
                ((body.size shr 8) and 0xFF).toByte(),
                (body.size and 0xFF).toByte(),
            )
        val streamInfoHeader = byteArrayOf(0x00, 0, 0, 34)
        return "fLaC".toByteArray(Charsets.US_ASCII) +
            streamInfoHeader +
            ByteArray(34) +
            blockHeader +
            body
    }

    private fun buildVorbisComment(vendor: String, comments: List<String>): ByteArray {
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        val output = ArrayList<Byte>()
        output.addAll(littleEndian(vendorBytes.size).toList())
        output.addAll(vendorBytes.toList())
        output.addAll(littleEndian(comments.size).toList())
        comments.forEach { comment ->
            val commentBytes = comment.toByteArray(Charsets.UTF_8)
            output.addAll(littleEndian(commentBytes.size).toList())
            output.addAll(commentBytes.toList())
        }
        return output.toByteArray()
    }

    private fun littleEndian(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )

    private fun mp4WithFreeform(vararg entries: Pair<String, String>): ByteArray =
        mp4Box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII) + ByteArray(4)) + mp4Moov(*entries)

    private fun mp4Moov(vararg entries: Pair<String, String>): ByteArray =
        mp4Box(
            "moov",
            mp4Box(
                "udta",
                mp4FullBox(
                    "meta",
                    mp4Box("hdlr", byteArrayOf(0, 0, 0, 0) + ByteArray(8)) +
                        mp4Box(
                            "ilst",
                            entries
                                .map { (name, value) -> mp4Freeform(name, value) }
                                .reduce { left, right -> left + right },
                        ),
                ),
            ),
        )

    /** `----` free-form 原子：`mean` + `name` + `data`（type 1 = UTF-8）。 */
    private fun mp4Freeform(name: String, value: String): ByteArray =
        mp4Box(
            "----",
            mp4FullBox("mean", "com.apple.iTunes".toByteArray(Charsets.UTF_8)) +
                mp4FullBox("name", name.toByteArray(Charsets.UTF_8)) +
                mp4Box(
                    "data",
                    byteArrayOf(0, 0, 0, 1) +
                        byteArrayOf(0, 0, 0, 0) +
                        value.toByteArray(Charsets.UTF_8),
                ),
        )

    private fun mp4Box(type: String, payload: ByteArray): ByteArray {
        val size = payload.size + 8
        return byteArrayOf(
            ((size shr 24) and 0xFF).toByte(),
            ((size shr 16) and 0xFF).toByte(),
            ((size shr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte(),
        ) + type.toByteArray(Charsets.US_ASCII) + payload
    }

    private fun mp4FullBox(type: String, payload: ByteArray): ByteArray =
        mp4Box(type, ByteArray(4) + payload)

    private fun id3WithTxxx(description: String, value: String): ByteArray {
        val payload =
            byteArrayOf(3) +
                description.toByteArray(Charsets.UTF_8) +
                byteArrayOf(0) +
                value.toByteArray(Charsets.UTF_8)
        val frame =
            "TXXX".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(
                    ((payload.size shr 24) and 0xFF).toByte(),
                    ((payload.size shr 16) and 0xFF).toByte(),
                    ((payload.size shr 8) and 0xFF).toByte(),
                    (payload.size and 0xFF).toByte(),
                ) +
                byteArrayOf(0, 0) +
                payload
        val size = frame.size
        val synchsafe =
            byteArrayOf(
                ((size shr 21) and 0x7F).toByte(),
                ((size shr 14) and 0x7F).toByte(),
                ((size shr 7) and 0x7F).toByte(),
                (size and 0x7F).toByte(),
            )
        return "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(3, 0, 0) + synchsafe + frame
    }
}
