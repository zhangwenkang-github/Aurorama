package com.zhangwenkang.cinefin.player.local.audio

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W30-MUSIC-FX / W5-T6：均衡器数学层（10 段频率表 + 预设 + 迁移 + peaking biquad 频响）单测。 */
class MusicEqualizerTest {

    @Test
    fun `十段中心频率为 31_25 Hz 到 16 kHz 一倍频程`() {
        assertEquals(
            listOf(
                31.25f,
                62.5f,
                125f,
                250f,
                500f,
                1_000f,
                2_000f,
                4_000f,
                8_000f,
                16_000f,
            ),
            MusicEqualizerFrequencies,
        )
        assertEquals(10, MUSIC_EQUALIZER_BAND_COUNT)
        assertEquals(MUSIC_EQUALIZER_BAND_COUNT, MusicEqualizerFrequencies.size)
    }

    @Test
    fun `预设表为十段且数值写死`() {
        val expected =
            mapOf(
                MusicEqualizerPreset.FLAT to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
                MusicEqualizerPreset.BASS to listOf(7f, 7f, 6f, 5f, 3.5f, 2f, 1f, 0f, 0f, 0f),
                MusicEqualizerPreset.VOCAL to
                    listOf(-3f, -3f, -1.5f, 0f, 1.5f, 3f, 3.5f, 4f, 2f, 1f),
                MusicEqualizerPreset.ROCK to listOf(5f, 5f, 4f, 3f, 0.5f, -0.5f, 1.5f, 3f, 4f, 5f),
                MusicEqualizerPreset.POP to listOf(-1f, -1f, 0.5f, 2f, 3f, 4f, 3f, 2f, 0f, -1f),
                MusicEqualizerPreset.CLASSICAL to
                    listOf(4f, 4f, 3.5f, 3f, 1.5f, 0f, 1.5f, 3f, 3.5f, 4f),
                MusicEqualizerPreset.JAZZ to listOf(4f, 3f, 1f, 2f, -2f, -2f, 0f, 1f, 3f, 4f),
                MusicEqualizerPreset.TREBLE_BOOST to
                    listOf(0f, 0f, 0f, 0f, -1f, -1f, 0f, 2f, 5f, 7f),
                MusicEqualizerPreset.CUSTOM to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            )
        expected.forEach { (preset, gains) ->
            assertEquals("${preset.key} 应为十段", MUSIC_EQUALIZER_BAND_COUNT, preset.gainsDb.size)
            assertEquals("${preset.key} 预设数值", gains, preset.gainsDb)
            assertEquals(MUSIC_EQUALIZER_BAND_COUNT, expected.getValue(preset).size)
        }
        assertEquals("自定义", MusicEqualizerPreset.CUSTOM.label)
        assertEquals(
            MusicEqualizerPreset.TREBLE_BOOST,
            MusicEqualizerPreset.fromKey("treble_boost"),
        )
        assertEquals(MusicEqualizerPreset.JAZZ, MusicEqualizerPreset.fromKey("jazz"))
    }

    /** 既有 6 预设 = 旧 5 段按 log2 频率插值后取 0.5 dB 网格的结果（表数值写死，这里反向钉死口径）。 */
    @Test
    fun `既有预设等于旧五段插值取整结果`() {
        val legacy =
            mapOf(
                MusicEqualizerPreset.FLAT to listOf(0f, 0f, 0f, 0f, 0f),
                MusicEqualizerPreset.BASS to listOf(7f, 5f, 2f, 0f, 0f),
                MusicEqualizerPreset.VOCAL to listOf(-3f, 0f, 3f, 4f, 1f),
                MusicEqualizerPreset.ROCK to listOf(5f, 3f, -1f, 3f, 5f),
                MusicEqualizerPreset.POP to listOf(-1f, 2f, 4f, 2f, -1f),
                MusicEqualizerPreset.CLASSICAL to listOf(4f, 3f, 0f, 3f, 4f),
            )
        legacy.forEach { (preset, bands) ->
            val interpolated =
                interpolateMusicEqualizerBands(
                        sourceFrequencies = LegacyMusicEqualizerFrequencies,
                        sourceGainsDb = bands,
                    )
                    .map(::quantizeMusicEqualizerGain)
            assertEquals("${preset.key} 插值结果应与预设表一致", preset.gainsDb, interpolated)
        }
    }

    @Test
    fun `bands round trip keeps values`() {
        val bands = listOf(6f, -3.5f, 0f, 12f, -12f, 1f, 2f, 3f, 4f, 5f)
        val encoded = encodeMusicEqualizerBands(bands)
        val decoded = parseMusicEqualizerBands(encoded)
        assertEquals(MUSIC_EQUALIZER_BAND_COUNT, decoded.size)
        bands.forEachIndexed { index, value -> assertEquals(value, decoded[index], 0.05f) }
    }

    @Test
    fun `sanitize clamps out of range and fills missing`() {
        val bands = sanitizeMusicEqualizerBands(listOf(99f, -99f, Float.NaN))
        assertEquals(MUSIC_EQUALIZER_BAND_COUNT, bands.size)
        assertEquals(MUSIC_EQUALIZER_MAX_GAIN_DB, bands[0], 0.001f)
        assertEquals(MUSIC_EQUALIZER_MIN_GAIN_DB, bands[1], 0.001f)
        (2 until MUSIC_EQUALIZER_BAND_COUNT).forEach { index ->
            assertEquals(0f, bands[index], 0.001f)
        }
    }

    @Test
    fun `quantize uses 0_5 dB grid`() {
        assertEquals(0.5f, quantizeMusicEqualizerGain(0.26f), 0.0001f)
        assertEquals(0.5f, quantizeMusicEqualizerGain(0.74f), 0.0001f)
        assertEquals(1.0f, quantizeMusicEqualizerGain(0.76f), 0.0001f)
        assertEquals(-2.0f, quantizeMusicEqualizerGain(-1.8f), 0.0001f)
        assertEquals(12f, quantizeMusicEqualizerGain(99f), 0.0001f)
        assertEquals(-12f, quantizeMusicEqualizerGain(-99f), 0.0001f)
        assertEquals(0f, quantizeMusicEqualizerGain(Float.NaN), 0.0001f)
    }

    @Test
    fun `preset bands resolve and custom uses custom values`() {
        val bass = musicEqualizerBands(MusicEqualizerPreset.BASS, List(10) { 0f })
        assertEquals(MusicEqualizerPreset.BASS.gainsDb, bass)
        val custom = musicEqualizerBands(MusicEqualizerPreset.CUSTOM, listOf(1f, 2f, 3f, 4f, 5f))
        assertEquals(10, custom.size)
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f, 0f, 0f, 0f, 0f, 0f), custom)
    }

    @Test
    fun `interpolate maps legacy anchors to octave grid`() {
        val interpolated =
            interpolateMusicEqualizerBands(
                sourceFrequencies = listOf(100f, 400f),
                sourceGainsDb = listOf(0f, 12f),
                targetFrequencies = listOf(50f, 100f, 200f, 400f, 800f),
            )
        assertEquals(0f, interpolated[0], 0.001f)
        assertEquals(0f, interpolated[1], 0.001f)
        assertEquals(6f, interpolated[2], 0.001f)
        assertEquals(12f, interpolated[3], 0.001f)
        assertEquals(12f, interpolated[4], 0.001f)
    }

    @Test
    fun `legacy five band custom preference migrates to ten bands idempotently`() {
        val legacy = "6.0,3.5,0.0,-2.0,1.0"
        val migrated = migrateLegacyMusicEqualizerBands(legacy)
        assertEquals("6.0,6.0,4.5,3.5,1.5,0.0,-1.0,-2.0,0.0,1.0", migrated)
        assertEquals(10, parseMusicEqualizerBands(migrated).size)

        // 读旧写新：写回后的十段值再迁移应为 null；重复执行结果一致
        assertNull(migrateLegacyMusicEqualizerBands(migrated))
        assertNull(migrateLegacyMusicEqualizerBands(migrated))
        assertEquals(migrated, migrateLegacyMusicEqualizerBands(legacy))
    }

    @Test
    fun `migration ignores empty and non legacy shaped values`() {
        assertNull(migrateLegacyMusicEqualizerBands(null))
        assertNull(migrateLegacyMusicEqualizerBands(""))
        assertNull(migrateLegacyMusicEqualizerBands("  "))
        assertNull(migrateLegacyMusicEqualizerBands("1.0,2.0,3.0"))
        assertNull(migrateLegacyMusicEqualizerBands("1.0,2.0,3.0,4.0,5.0,6.0"))
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

    /** 10 段全开时高频段在 44.1 kHz 下也不能产生 NaN / 爆音。 */
    @Test
    fun `ten band chain stays finite at 44_1 kHz`() {
        val filters = MusicEqualizerFrequencies.map {
            BiquadFilter().apply { setCoefficients(peakingEqualizerCoefficients(44_100, it, 12f)) }
        }
        var value = 0.5f
        repeat(4_800) { index ->
            var sample = (sin(2.0 * PI * 440.0 * index / 44_100.0) * 0.5).toFloat()
            filters.forEach { filter -> sample = filter.process(sample) }
            assertTrue("index=$index sample=$sample", sample.isFinite())
            value = sample
        }
        assertTrue(value.isFinite())
    }
}
