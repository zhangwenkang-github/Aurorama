package com.zhangwenkang.cinefin.book.presentation.reader.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * 系统 `TextToSpeech` 的薄封装（W6 T9 / READER-32/36）。
 *
 * 一句一个 utterance（`QUEUE_FLUSH`），句内区间不细分：高亮的边界由 `onStart` / `onRangeStart` 回调确认——真正开口后才把句高亮推给
 * UI（`ReaderTtsController.onUtteranceStarted`）。 语音数据全部来自系统（离线音色优先，符合 READER-45），不做在线合成。
 */
internal class AndroidTtsEngine(private val context: Context) : TtsEngine {

    /** 引擎回调：由使用方转给 `ReaderTtsController` 的同名方法。 */
    interface Callbacks {
        fun onStarted(utteranceId: String)

        fun onRangeStart(utteranceId: String, start: Int, end: Int)

        fun onFinished(utteranceId: String)

        fun onFailed(utteranceId: String)
    }

    var callbacks: Callbacks? = null

    var ready: Boolean = false
        private set

    var initError: String? = null
        private set

    private var tts: TextToSpeech? = null
    private var preferredVoiceName: String? = null
    private val readySignal = CompletableDeferred<Boolean>()

    /** 初始化（幂等）；结果经 [ready] / [initError] 反映，等待就绪用 [awaitReady]。 */
    fun initialize(preferredVoice: String? = null) {
        preferredVoiceName = preferredVoice
        if (tts != null) return
        tts =
            TextToSpeech(context.applicationContext) { status ->
                val engine = tts
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    initError = "系统 TTS 初始化失败"
                    readySignal.complete(false)
                    Timber.w("TTS 初始化失败：status=$status")
                    return@TextToSpeech
                }
                ready = true
                initError = null
                applyPreferredVoice(engine)
                engine.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            utteranceId?.let { callbacks?.onStarted(it) }
                        }

                        override fun onRangeStart(
                            utteranceId: String?,
                            start: Int,
                            end: Int,
                            frame: Int,
                        ) {
                            utteranceId?.let { callbacks?.onRangeStart(it, start, end) }
                        }

                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onDone(utteranceId: String?) {
                            utteranceId?.let { callbacks?.onFinished(it) }
                        }

                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onError(utteranceId: String?) {
                            utteranceId?.let { callbacks?.onFailed(it) }
                            Timber.w("TTS 朗读失败：utterance=$utteranceId")
                        }
                    }
                )
                readySignal.complete(true)
            }
    }

    /** 等待引擎就绪（首启时 `TextToSpeech` 初始化是异步的）；超时 / 失败返回 false。 */
    suspend fun awaitReady(timeoutMs: Long = 8_000L): Boolean {
        if (ready) return true
        if (tts == null) return false
        return withTimeoutOrNull(timeoutMs) { readySignal.await() } ?: false
    }

    override fun setRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(ReaderTtsRateRange))
    }

    override fun speak(text: String, utteranceId: String): Boolean {
        val engine = tts ?: return false
        if (!ready || text.isBlank()) return false
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        return result == TextToSpeech.SUCCESS
    }

    override fun stop() {
        runCatching { tts?.stop() }
    }

    /** 系统可用音色（中文 / 英文优先，离线音色排前面）。 */
    fun voices(): List<TtsVoiceOption> {
        val engine = tts ?: return emptyList()
        val available = runCatching {
            engine.voices.orEmpty()
        }
            .onFailure { Timber.w(it, "读取系统音色失败") }
            .getOrDefault(emptySet())
        return available
            .filter { it.locale.language == "zh" || it.locale.language == "en" }
            .sortedWith(
                compareBy<Voice>(
                    { it.isNetworkConnectionRequired },
                    { it.locale.language != "zh" },
                    { it.name },
                )
            )
            .map { voice ->
                TtsVoiceOption(
                    name = voice.name,
                    label = voiceLabel(voice),
                    locale = voice.locale.toLanguageTag(),
                )
            }
    }

    /** 选择音色（名字来自 [voices]）；找不到就沿用当前。 */
    fun setVoice(name: String?) {
        preferredVoiceName = name
        tts?.let(::applyPreferredVoice)
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
    }

    private fun applyPreferredVoice(engine: TextToSpeech) {
        val target = preferredVoiceName?.let { name ->
            runCatching { engine.voices?.firstOrNull { it.name == name } }
                .onFailure { Timber.w(it, "系统音色列表不可用") }
                .getOrNull()
        }
        if (target != null) {
            val result = runCatching { engine.voice = target }.isSuccess
            if (result) return
        }
        // 没有匹配音色：优先简体中文，退回英文，最后交给系统默认。
        val language = runCatching {
            engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
        }
            .getOrDefault(-1)
        if (language < 0) {
            runCatching { engine.setLanguage(Locale.US) }
        }
    }

    private fun voiceLabel(voice: Voice): String {
        val locale = voice.locale
        val base =
            when (locale.language) {
                "zh" -> if (locale.country == "CN") "中文（简体）" else "中文（${locale.country}）"
                "en" ->
                    if (locale.country == "US") "English (US)" else "English (${locale.country})"
                else -> locale.displayName
            }
        val quality =
            when (voice.quality) {
                Voice.QUALITY_VERY_HIGH,
                Voice.QUALITY_HIGH -> "高品质"
                Voice.QUALITY_NORMAL -> "标准"
                else -> ""
            }
        val network = if (voice.isNetworkConnectionRequired) "在线" else "离线"
        return listOf(base, quality, network).filter { it.isNotBlank() }.joinToString(" · ")
    }
}
