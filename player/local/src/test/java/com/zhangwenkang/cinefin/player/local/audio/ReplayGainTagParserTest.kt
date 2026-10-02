package com.zhangwenkang.cinefin.player.local.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W30-MUSIC-FX：ReplayGain 标签解析（FLAC VorbisComment / ID3v2 TXXX / 本机覆盖）单测。 */
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
