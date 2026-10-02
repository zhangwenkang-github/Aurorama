package com.zhangwenkang.cinefin.player.local.audio

import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 音乐均衡器（W30-MUSIC-FX）：5 段 peaking EQ 的纯数学层。
 *
 * 频段固定为 [MusicEqualizerFrequencies]（对齐 Android 平台 Equalizer 的五段划分）， 增益范围
 * ±[MUSIC_EQUALIZER_MAX_GAIN_DB] dB。 处理器（[MusicAudioEffectsProcessor]）与 UI 的预设 /
 * 自定义档位共用这里的定义与编解码函数。
 */
val MusicEqualizerFrequencies: List<Float> = listOf(60f, 230f, 910f, 3600f, 14000f)

const val MUSIC_EQUALIZER_BAND_COUNT = 5
const val MUSIC_EQUALIZER_MAX_GAIN_DB = 12f
const val MUSIC_EQUALIZER_MIN_GAIN_DB = -12f

/** 均衡器预设：`gainsDb` 依次对应 [MusicEqualizerFrequencies] 的五段。 */
enum class MusicEqualizerPreset(
    val key: String,
    val label: String,
    val gainsDb: List<Float>,
) {
    FLAT("flat", "原声", listOf(0f, 0f, 0f, 0f, 0f)),
    BASS("bass", "低音增强", listOf(7f, 5f, 2f, 0f, 0f)),
    VOCAL("vocal", "人声", listOf(-3f, 0f, 3f, 4f, 1f)),
    ROCK("rock", "摇滚", listOf(5f, 3f, -1f, 3f, 5f)),
    POP("pop", "流行", listOf(-1f, 2f, 4f, 2f, -1f)),
    CLASSICAL("classical", "古典", listOf(4f, 3f, 0f, 3f, 4f)),
    CUSTOM("custom", "自定义", listOf(0f, 0f, 0f, 0f, 0f));

    companion object {
        fun fromKey(key: String?): MusicEqualizerPreset =
            entries.firstOrNull { it.key == key } ?: FLAT
    }
}

/** 把任意输入清洗成合法的五段增益列表。 */
fun sanitizeMusicEqualizerBands(bands: List<Float>): List<Float> =
    List(MUSIC_EQUALIZER_BAND_COUNT) { index ->
        (bands.getOrNull(index) ?: 0f)
            .takeIf { it.isFinite() }
            ?.coerceIn(MUSIC_EQUALIZER_MIN_GAIN_DB, MUSIC_EQUALIZER_MAX_GAIN_DB) ?: 0f
    }

/** 自定义频段按 [MusicEqualizerPreset] 取值；自定义预设使用 [customBands]。 */
fun musicEqualizerBands(
    preset: MusicEqualizerPreset,
    customBands: List<Float>,
): List<Float> =
    if (preset == MusicEqualizerPreset.CUSTOM) sanitizeMusicEqualizerBands(customBands)
    else sanitizeMusicEqualizerBands(preset.gainsDb)

/** 编解码：偏好里存 `"6.0,3.5,0.0,0.0,-2.0"` 形式的逗号分隔 dB。 */
fun parseMusicEqualizerBands(text: String?): List<Float> =
    sanitizeMusicEqualizerBands(
        text?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.isNotEmpty() }
            ?: emptyList()
    )

fun encodeMusicEqualizerBands(bands: List<Float>): String =
    sanitizeMusicEqualizerBands(bands).joinToString(",") { String.format(Locale.US, "%.1f", it) }

/**
 * RBJ Audio EQ Cookbook 的 peaking EQ 系数（归一化到 a0 = 1）。
 *
 * 频响在 [frequencyHz] 处恰好抬升 / 压低 [gainDb] dB（Q 控制带宽，默认 1.0）。
 */
internal data class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
)

internal fun peakingEqualizerCoefficients(
    sampleRate: Int,
    frequencyHz: Float,
    gainDb: Float,
    q: Float = 1.0f,
): BiquadCoefficients {
    val safeSampleRate = sampleRate.coerceAtLeast(8_000).toDouble()
    val nyquist = safeSampleRate / 2.0
    // 超过 0.45×采样率的频段（例如 14 kHz @ 22.05 kHz）收敛到安全上限，避免 tan 溢出。
    val freq = frequencyHz.toDouble().coerceIn(10.0, nyquist * 0.9)
    val a = 10.0.pow(gainDb.toDouble() / 40.0)
    val w0 = 2.0 * PI * freq / safeSampleRate
    val alpha = sin(w0) / (2.0 * q.toDouble().coerceAtLeast(0.05))
    val cosW0 = cos(w0)

    val b0 = 1.0 + (alpha * a)
    val b1 = -2.0 * cosW0
    val b2 = 1.0 - (alpha * a)
    val a0 = 1.0 + (alpha / a)
    val a1 = -2.0 * cosW0
    val a2 = 1.0 - (alpha / a)
    return BiquadCoefficients(
        b0 = b0 / a0,
        b1 = b1 / a0,
        b2 = b2 / a0,
        a1 = a1 / a0,
        a2 = a2 / a0,
    )
}

/** 单个双二阶（Direct Form II Transposed）滤波器，双精度状态保证长播不漂移。 */
internal class BiquadFilter {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    fun setCoefficients(coefficients: BiquadCoefficients) {
        b0 = coefficients.b0
        b1 = coefficients.b1
        b2 = coefficients.b2
        a1 = coefficients.a1
        a2 = coefficients.a2
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    fun process(input: Float): Float {
        val x = input.toDouble()
        val y = (b0 * x) + z1
        z1 = (b1 * x) - (a1 * y) + z2
        z2 = (b2 * x) - (a2 * y)
        return y.toFloat()
    }
}
