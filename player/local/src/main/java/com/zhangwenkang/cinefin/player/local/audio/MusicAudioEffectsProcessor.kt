package com.zhangwenkang.cinefin.player.local.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.math.sqrt
import timber.log.Timber

/**
 * 音乐音效处理器（W30-MUSIC-FX）：EQ（5 段 peaking）+ ReplayGain 增益，挂在 ExoPlayer 的音频处理链上
 * （[CinefinRenderersFactory] 与 [AudioDelayProcessor] 同链）。
 *
 * 线程模型：音频线程调用 [queueInput]；参数由主线程写入 `@Volatile` 字段，可在播放中即时生效。
 *
 * 设计要点：
 * - [isActive] **配置完成后恒为 true**（同 [AudioDelayProcessor] 的注释）：音频链的 active 集合在 configure 时确定，
 *   若按"当前是否音乐会话"返回 false，视频与音乐格式相同时音乐侧会拿不到处理器；改由 [queueInput] 里按 [sessionActive] 透传，视频会话不受影响。
 * - 音乐会话开启 EQ / ReplayGain 时逐样本处理；关闭时透传（一次内存拷贝成本）。
 * - 每约 5 秒输出一条输入 / 输出 RMS 与当前效果参数日志，作为真机"对音频输出可测差异"的证据。
 */
class MusicAudioEffectsProcessor : BaseAudioProcessor() {

    /** 是否处于音乐会话；视频会话（false）时透传。 */
    @Volatile var sessionActive: Boolean = false

    /** EQ 开关（关闭时跳过 5 段滤波）。 */
    @Volatile var equalizerEnabled: Boolean = false

    /** 五段增益（dB），顺序同 [MusicEqualizerFrequencies]。 */
    @Volatile var equalizerGainsDb: List<Float> = MusicEqualizerPreset.FLAT.gainsDb

    /** ReplayGain 线性增益（1 = 不应用）。 */
    @Volatile var replayGainFactor: Float = 1f

    private var channelCount = 0
    private var sampleRate = 0
    private var encoding = C.ENCODING_INVALID
    private var filters: Array<Array<BiquadFilter>> = emptyArray()
    private var appliedGains: List<Float>? = null
    private var channelPhase = 0

    private var statsFrames = 0L
    private var statsInputSquares = 0.0
    private var statsOutputSquares = 0.0

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
                inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        encoding = inputAudioFormat.encoding
        filters = Array(channelCount) { Array(MUSIC_EQUALIZER_BAND_COUNT) { BiquadFilter() } }
        appliedGains = null
        channelPhase = 0
        resetStats()
        return inputAudioFormat
    }

    /** 配置完成后恒活跃（细节见类注释）：视频会话的 PCM 也会流经本处理器，但 [sessionActive] 为 false 时直接透传。 */
    override fun isActive(): Boolean =
        inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET &&
            outputAudioFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (hasPendingOutput()) return
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        val shouldProcess =
            sessionActive && channelCount > 0 && (equalizerEnabled || replayGainFactor != 1f)
        if (!shouldProcess) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        applyParametersIfChanged()
        when (encoding) {
            C.ENCODING_PCM_16BIT -> processPcm16(inputBuffer, output)
            C.ENCODING_PCM_FLOAT -> processPcmFloat(inputBuffer, output)
            else -> output.put(inputBuffer)
        }
        output.flip()
        logStatsIfDue()
    }

    override fun onFlush() {
        resetProcessingState()
    }

    override fun onReset() {
        resetProcessingState()
        appliedGains = null
    }

    private fun resetProcessingState() {
        filters.forEach { channelFilters -> channelFilters.forEach(BiquadFilter::reset) }
        channelPhase = 0
        resetStats()
    }

    private fun resetStats() {
        statsFrames = 0L
        statsInputSquares = 0.0
        statsOutputSquares = 0.0
    }

    /** 每处理一个 buffer 检查一次参数变化：变化时重算五段系数（不重置滤波器状态，避免瞬断）。 */
    private fun applyParametersIfChanged() {
        val gains = equalizerGainsDb
        if (gains == appliedGains) return
        appliedGains = gains
        val coefficients = MusicEqualizerFrequencies.mapIndexed { index, frequency ->
            peakingEqualizerCoefficients(
                sampleRate = sampleRate,
                frequencyHz = frequency,
                gainDb = gains.getOrElse(index) { 0f },
            )
        }
        for (channel in 0 until channelCount) {
            val channelFilters = filters[channel]
            for (band in 0 until MUSIC_EQUALIZER_BAND_COUNT) {
                channelFilters[band].setCoefficients(coefficients[band])
            }
        }
    }

    private fun processPcm16(input: ByteBuffer, output: ByteBuffer) {
        val limit = input.limit()
        while (input.position() < limit) {
            val raw = input.short.toInt()
            val value = raw / 32768f
            val processed = applyEffects(value)
            val clipped = processed.coerceIn(-1f, 1f)
            output.putShort((clipped * 32767f).roundToInt().toShort())
        }
        input.position(limit)
    }

    private fun processPcmFloat(input: ByteBuffer, output: ByteBuffer) {
        val limit = input.limit()
        while (input.position() < limit) {
            val value = input.float
            val processed = applyEffects(value)
            output.putFloat(processed.coerceIn(-1f, 1f))
        }
        input.position(limit)
    }

    private fun applyEffects(input: Float): Float {
        var value = input
        if (equalizerEnabled && channelCount > 0) {
            val channel = channelPhase
            val channelFilters = filters[channel]
            for (band in 0 until MUSIC_EQUALIZER_BAND_COUNT) {
                value = channelFilters[band].process(value)
            }
        }
        // 声道相位与样本流对齐：无论 EQ 是否开启都要推进（否则重启 EQ 时 L/R 状态会错位）。
        if (channelCount > 0) {
            channelPhase += 1
            if (channelPhase >= channelCount) channelPhase = 0
        }
        val factor = replayGainFactor
        if (factor != 1f) value *= factor
        statsFrames += 1
        statsInputSquares += input.toDouble() * input
        statsOutputSquares += value.toDouble() * value
        return value
    }

    private fun logStatsIfDue() {
        val framesPerInterval = sampleRate.toLong() * STATS_INTERVAL_SECONDS
        if (framesPerInterval <= 0 || statsFrames < framesPerInterval) return
        val inputRms = sqrt(statsInputSquares / statsFrames)
        val outputRms = sqrt(statsOutputSquares / statsFrames)
        val ratio = if (inputRms > 0.0) outputRms / inputRms else 0.0
        Timber.i(
            "音乐音效统计：eq=%s gains=%s rg=%.2fdB 输入RMS=%.4f 输出RMS=%.4f 实际增益=%.2fx",
            if (equalizerEnabled) "开" else "关",
            equalizerGainsDb.joinToString(prefix = "[", postfix = "]") {
                String.format(java.util.Locale.US, "%.1f", it)
            },
            replayGainDbLabel(),
            inputRms,
            outputRms,
            ratio,
        )
        resetStats()
    }

    private fun replayGainDbLabel(): Double {
        val factor = replayGainFactor.toDouble()
        return if (factor <= 0.0) -99.0 else 20.0 * kotlin.math.log10(factor)
    }

    private companion object {
        const val STATS_INTERVAL_SECONDS = 5L
    }
}
