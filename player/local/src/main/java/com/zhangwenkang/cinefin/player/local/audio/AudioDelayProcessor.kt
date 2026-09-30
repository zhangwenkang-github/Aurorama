package com.zhangwenkang.cinefin.player.local.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import timber.log.Timber

/**
 * 音轨延迟处理器（§1.2）。
 *
 * Media3 没有音频时间偏移 API，要调「声音比画面早/晚」只能自己动 PCM 数据：
 * - 正延迟：在音频流里插入若干帧静音 → 声音整体后移（听起来比画面晚）；
 * - 负延迟：丢掉音频流开头若干帧 → 声音整体前移。
 *
 * 与字幕延迟不同，延迟值可以**播放中即时改**：每次 [queueInput] 都会把 「目标延迟」与「已应用的延迟」对齐，缺多少补多少，不需要 flush / 重开播放。
 *
 * 实现继承 Media3 的 [BaseAudioProcessor]（它负责 buffer 复用与状态机）， 注意必须覆写 [isActive]：基类只在「有待消费输出」时算活跃，靠它会让处理器
 * 被管线旁路，延迟只在第一个 buffer 生效。
 */
class AudioDelayProcessor : BaseAudioProcessor() {

    companion object {
        /** 可调范围 ±5s：再大的偏移基本是片源本身有问题，不是同步微调 */
        const val MAX_DELAY_MS = 5_000L

        /** 面板步长 0.05s：音画同步比字幕更吃精度 */
        const val STEP_MS = 50L

        /** 单次最多插入的静音字节数：超过就分多个 buffer 继续补（避免一次分配过大） */
        private const val MAX_SILENCE_BYTES = 1 shl 20
    }

    /**
     * 目标延迟（毫秒），正 = 声音延后。
     *
     * 播放线程写入、音频线程读取，用 volatile 保证可见性；改这个值不需要 flush， 下一次 [queueInput] 就会把差值补上（或跳掉）。
     */
    @Volatile var delayMs: Long = 0L

    /**
     * 已经体现在输出流里的延迟（以帧计）：正 = 已插入静音，负 = 已跳过数据。
     *
     * 每次 queueInput 用 `target - applied` 求差值，跨 buffer 的跳过/插入会自动续上。
     */
    private var appliedFrames: Long = 0L

    /** 上一次打过日志的目标延迟：只在延迟值变化后打一条，避免每个 buffer 刷屏 */
    private var lastLoggedDelayMs: Long = Long.MIN_VALUE

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        // 只处理 PCM：16bit 是 Media3 音频链的默认输出，float 是开启 float 输出后的格式
        if (
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
                inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    /** 配置完成后始终活跃：否则管线会在没有待输出时旁路本处理器，延迟无法持续生效 */
    override fun isActive(): Boolean =
        inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET &&
            outputAudioFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (hasPendingOutput()) return

        val format = inputAudioFormat
        val bytesPerFrame = format.bytesPerFrame
        if (bytesPerFrame <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val targetFrames = delayMs * format.sampleRate / 1000L

        // 1) 目标是「提前」：先吃掉输入里的数据，吃不完的下个 buffer 继续
        val neededSkip = targetFrames - appliedFrames
        if (neededSkip < 0) {
            val availableFrames = (inputBuffer.remaining() / bytesPerFrame).toLong()
            val skipFrames = minOf(-neededSkip, availableFrames)
            if (skipFrames > 0) {
                inputBuffer.position(inputBuffer.position() + (skipFrames * bytesPerFrame).toInt())
                appliedFrames -= skipFrames
                logApplied("跳过", skipFrames)
            }
        }

        val dataBytes = inputBuffer.remaining()
        val inputEnd = inputBuffer.limit()

        // 2) 目标是「延后」：输出 = 静音 + 原始数据
        val pendingSilenceFrames = (targetFrames - appliedFrames).coerceAtLeast(0L)
        val silenceBytes =
            (pendingSilenceFrames * bytesPerFrame).coerceAtMost(MAX_SILENCE_BYTES.toLong()).toInt()

        if (silenceBytes <= 0) {
            if (dataBytes <= 0) {
                inputBuffer.position(inputEnd)
                return
            }
            val output = replaceOutputBuffer(dataBytes)
            output.put(inputBuffer)
            output.flip()
            inputBuffer.position(inputEnd)
            return
        }

        val output = replaceOutputBuffer(silenceBytes + dataBytes)
        // PCM 0 即静音：16bit 与 float 的全零字节都表示 0
        output.put(ByteArray(silenceBytes))
        if (dataBytes > 0) {
            output.put(inputBuffer)
        }
        output.flip()
        inputBuffer.position(inputEnd)
        appliedFrames += silenceBytes / bytesPerFrame
        logApplied("插入静音", (silenceBytes / bytesPerFrame).toLong())
    }

    override fun onFlush() {
        // seek / 重配后重新从零开始应用延迟（延迟值本身保留）
        appliedFrames = 0L
        // 允许重新打一条日志：seek 后能确认延迟又被重新应用了一遍
        lastLoggedDelayMs = Long.MIN_VALUE
    }

    /** 延迟值变化后打一条实际生效日志（帧数按当前采样率换算），便于真机排障 */
    private fun logApplied(
        action: String,
        frames: Long,
    ) {
        if (delayMs == lastLoggedDelayMs || frames <= 0L) return
        lastLoggedDelayMs = delayMs
        Timber.d(
            "音频延迟生效：目标 %d ms → %s %d 帧（%d Hz）",
            delayMs,
            action,
            frames,
            inputAudioFormat.sampleRate,
        )
    }
}
