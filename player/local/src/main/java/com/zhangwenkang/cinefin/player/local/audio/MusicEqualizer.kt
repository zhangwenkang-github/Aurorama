package com.zhangwenkang.cinefin.player.local.audio

import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 音乐均衡器（W30-MUSIC-FX；W5-T6 升 10 段）：10 段 peaking EQ 的纯数学层。
 *
 * 频段固定为 [MusicEqualizerFrequencies]（31.25 Hz–16 kHz 一倍频程划分），增益范围 ±[MUSIC_EQUALIZER_MAX_GAIN_DB] dB、
 * [MUSIC_EQUALIZER_GAIN_STEP_DB] dB 步进。处理器（[MusicAudioEffectsProcessor]）与 UI 的预设 /
 * 自定义档位共用这里的定义与编解码函数。
 */
val MusicEqualizerFrequencies: List<Float> =
    listOf(31.25f, 62.5f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)

const val MUSIC_EQUALIZER_BAND_COUNT = 10
const val MUSIC_EQUALIZER_MAX_GAIN_DB = 12f
const val MUSIC_EQUALIZER_MIN_GAIN_DB = -12f

/** 增益步进（dB）：滑杆与预设数值都落在该网格上。 */
const val MUSIC_EQUALIZER_GAIN_STEP_DB = 0.5f

/** W5-T6 迁移前的 5 段中心频率（W30-MUSIC-FX 旧口径），只用于既有预设插值与旧自定义偏好迁移。 */
val LegacyMusicEqualizerFrequencies: List<Float> = listOf(60f, 230f, 910f, 3_600f, 14_000f)

/**
 * 均衡器预设：`gainsDb` 依次对应 [MusicEqualizerFrequencies] 的十段。
 *
 * 前六个预设是 W30 五段口径的插值结果（按 log2 频率线性插值后取 0.5 dB 网格，数值写死）； JAZZ / TREBLE_BOOST 为 W5-T6 新增；CUSTOM
 * 使用用户自定义频段。
 */
enum class MusicEqualizerPreset(
    val key: String,
    val label: String,
    val gainsDb: List<Float>,
) {
    FLAT("flat", "原声", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    BASS("bass", "低音增强", listOf(7f, 7f, 6f, 5f, 3.5f, 2f, 1f, 0f, 0f, 0f)),
    VOCAL("vocal", "人声", listOf(-3f, -3f, -1.5f, 0f, 1.5f, 3f, 3.5f, 4f, 2f, 1f)),
    ROCK("rock", "摇滚", listOf(5f, 5f, 4f, 3f, 0.5f, -0.5f, 1.5f, 3f, 4f, 5f)),
    POP("pop", "流行", listOf(-1f, -1f, 0.5f, 2f, 3f, 4f, 3f, 2f, 0f, -1f)),
    CLASSICAL("classical", "古典", listOf(4f, 4f, 3.5f, 3f, 1.5f, 0f, 1.5f, 3f, 3.5f, 4f)),
    JAZZ("jazz", "爵士", listOf(4f, 3f, 1f, 2f, -2f, -2f, 0f, 1f, 3f, 4f)),
    TREBLE_BOOST("treble_boost", "高音增强", listOf(0f, 0f, 0f, 0f, -1f, -1f, 0f, 2f, 5f, 7f)),
    CUSTOM("custom", "自定义", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f));

    companion object {
        fun fromKey(key: String?): MusicEqualizerPreset =
            entries.firstOrNull { it.key == key } ?: FLAT
    }
}

/** 把任意输入清洗成合法的十段增益列表。 */
fun sanitizeMusicEqualizerBands(bands: List<Float>): List<Float> =
    List(MUSIC_EQUALIZER_BAND_COUNT) { index ->
        (bands.getOrNull(index) ?: 0f)
            .takeIf { it.isFinite() }
            ?.coerceIn(MUSIC_EQUALIZER_MIN_GAIN_DB, MUSIC_EQUALIZER_MAX_GAIN_DB) ?: 0f
    }

/** 增益对齐到 [MUSIC_EQUALIZER_GAIN_STEP_DB] 网格（滑杆与迁移结果共用）。 */
fun quantizeMusicEqualizerGain(gainDb: Float): Float {
    if (!gainDb.isFinite()) return 0f
    val quantized =
        (gainDb / MUSIC_EQUALIZER_GAIN_STEP_DB).roundToInt() * MUSIC_EQUALIZER_GAIN_STEP_DB
    return quantized.coerceIn(MUSIC_EQUALIZER_MIN_GAIN_DB, MUSIC_EQUALIZER_MAX_GAIN_DB)
}

/** 自定义频段按 [MusicEqualizerPreset] 取值；自定义预设使用 [customBands]。 */
fun musicEqualizerBands(
    preset: MusicEqualizerPreset,
    customBands: List<Float>,
): List<Float> =
    if (preset == MusicEqualizerPreset.CUSTOM) sanitizeMusicEqualizerBands(customBands)
    else sanitizeMusicEqualizerBands(preset.gainsDb)

/** 编解码：偏好里存 `"6.0,3.5,0.0,..."` 形式的逗号分隔 dB（十段）。 */
fun parseMusicEqualizerBands(text: String?): List<Float> =
    sanitizeMusicEqualizerBands(
        text?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.isNotEmpty() }
            ?: emptyList()
    )

fun encodeMusicEqualizerBands(bands: List<Float>): String =
    sanitizeMusicEqualizerBands(bands).joinToString(",") { String.format(Locale.US, "%.1f", it) }

/**
 * 把 [sourceGainsDb]（对应 [sourceFrequencies]）插值到 [targetFrequencies]。
 *
 * 插值轴是 log2 频率（倍频程轴）：均衡器频段按倍频程等距划分，这样插值结果与听感一致； 目标频点超出源范围时取最近端点值。源与目标都按升序处理，源为空时全部返回 0 dB。
 */
fun interpolateMusicEqualizerBands(
    sourceFrequencies: List<Float>,
    sourceGainsDb: List<Float>,
    targetFrequencies: List<Float> = MusicEqualizerFrequencies,
): List<Float> {
    val anchors =
        sourceFrequencies.indices
            .mapNotNull { index ->
                val frequency = sourceFrequencies[index]
                val gain = sourceGainsDb.getOrNull(index)
                if (frequency.isFinite() && frequency > 0f && gain != null && gain.isFinite()) {
                    frequency.toDouble() to gain
                } else {
                    null
                }
            }
            .sortedBy { it.first }
    if (anchors.isEmpty()) return List(targetFrequencies.size) { 0f }
    return targetFrequencies.map { target ->
        val position = ln(target.coerceAtLeast(1f).toDouble())
        when {
            position <= ln(anchors.first().first) -> anchors.first().second
            position >= ln(anchors.last().first) -> anchors.last().second
            else -> {
                val upperIndex = anchors.indexOfFirst { ln(it.first) >= position }.coerceAtLeast(1)
                val lower = anchors[upperIndex - 1]
                val upper = anchors[upperIndex]
                val lowerPosition = ln(lower.first)
                val ratio = (position - lowerPosition) / (ln(upper.first) - lowerPosition)
                (lower.second + (upper.second - lower.second) * ratio).toFloat()
            }
        }
    }
}

/**
 * 旧 5 段自定义偏好 → 10 段的一次性迁移（W5-T6）。
 *
 * 返回需要写回的新字符串；无需迁移（空值 / 已是十段 / 非五段脏数据）时返回 null。 幂等：写回后再调用会因已是十段而返回 null；重复执行结果一致。
 */
fun migrateLegacyMusicEqualizerBands(stored: String?): String? {
    val values =
        stored?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.isNotEmpty() }
            ?: return null
    if (values.size == MUSIC_EQUALIZER_BAND_COUNT) return null
    if (values.size != LegacyMusicEqualizerFrequencies.size) return null
    val migrated =
        interpolateMusicEqualizerBands(
                sourceFrequencies = LegacyMusicEqualizerFrequencies,
                sourceGainsDb = values,
                targetFrequencies = MusicEqualizerFrequencies,
            )
            .map(::quantizeMusicEqualizerGain)
    return encodeMusicEqualizerBands(migrated)
}

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
    // 超过 0.9×奈奎斯特的频段（例如 16 kHz @ 22.05 kHz）收敛到安全上限，避免 tan 溢出。
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
