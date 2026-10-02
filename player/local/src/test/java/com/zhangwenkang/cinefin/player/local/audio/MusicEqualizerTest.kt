package com.zhangwenkang.cinefin.player.local.audio

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W30-MUSIC-FX：均衡器数学层（预设编解码 + peaking biquad 频响）单测。 */
class MusicEqualizerTest {

    @Test
    fun `bands round trip keeps values`() {
        val bands = listOf(6f, -3.5f, 0f, 12f, -12f)
        val encoded = encodeMusicEqualizerBands(bands)
        val decoded = parseMusicEqualizerBands(encoded)
        assertEquals(bands.size, decoded.size)
        bands.forEachIndexed { index, value -> assertEquals(value, decoded[index], 0.05f) }
    }

    @Test
    fun `sanitize clamps out of range and fills missing`() {
        val bands = sanitizeMusicEqualizerBands(listOf(99f, -99f, Float.NaN))
        assertEquals(MUSIC_EQUALIZER_MAX_GAIN_DB, bands[0], 0.001f)
        assertEquals(MUSIC_EQUALIZER_MIN_GAIN_DB, bands[1], 0.001f)
        assertEquals(0f, bands[2], 0.001f)
        assertEquals(0f, bands[3], 0.001f)
        assertEquals(0f, bands[4], 0.001f)
    }

    @Test
    fun `preset bands resolve and custom uses custom values`() {
        val bass = musicEqualizerBands(MusicEqualizerPreset.BASS, listOf(0f, 0f, 0f, 0f, 0f))
        assertTrue("低音增强的 60 Hz 段应为正增益", bass[0] > 0f)
        val custom = musicEqualizerBands(MusicEqualizerPreset.CUSTOM, listOf(1f, 2f, 3f, 4f, 5f))
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f), custom)
    }

    /** +6 dB @1 kHz 的 peaking 滤波器对 1 kHz 正弦的增益应约为 2×（±0.15）。 */
    @Test
    fun `peaking filter boosts center frequency by configured gain`() {
        val sampleRate = 44_100
        val frequency = 1_000f
        val coefficient = peakingEqualizerCoefficients(sampleRate, frequency, 6f, q = 1f)
        val filter = BiquadFilter().apply { setCoefficients(coefficient) }
        var inputSquares = 0.0
        var outputSquares = 0.0
        for (index in 0 until sampleRate) {
            val input = (sin(2.0 * PI * frequency * index / sampleRate) * 0.5).toFloat()
            val output = filter.process(input)
            if (index > sampleRate / 10) {
                inputSquares += input.toDouble() * input
                outputSquares += output.toDouble() * output
            }
        }
        val ratio = sqrt(outputSquares / inputSquares)
        assertEquals(10.0.pow(6.0 / 20.0), ratio, 0.15)
    }

    /** 0 dB 的滤波器应近似直通（系数退化为单位增益）。 */
    @Test
    fun `zero gain filter is near unity`() {
        val sampleRate = 44_100
        val coefficient = peakingEqualizerCoefficients(sampleRate, 1_000f, 0f, q = 1f)
        val filter = BiquadFilter().apply { setCoefficients(coefficient) }
        var inputSquares = 0.0
        var outputSquares = 0.0
        for (index in 0 until sampleRate / 2) {
            val input = (sin(2.0 * PI * 1_000f * index / sampleRate) * 0.5).toFloat()
            val output = filter.process(input)
            if (index > sampleRate / 20) {
                inputSquares += input.toDouble() * input
                outputSquares += output.toDouble() * output
            }
        }
        val ratio = sqrt(outputSquares / inputSquares)
        assertEquals(1.0, ratio, 0.02)
    }
}
