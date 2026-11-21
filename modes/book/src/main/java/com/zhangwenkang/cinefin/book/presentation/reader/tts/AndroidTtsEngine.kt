package com.zhangwenkang.cinefin.book.presentation.reader.tts

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * 系统 `TextToSpeech` 的薄封装（W6 T9 / READER-32/36）。
 *
 * 一句一个 utterance（`QUEUE_FLUSH`），句内区间不细分：高亮的边界由 `onStart` / `onRangeStart` 回调确认——真正开口后才把句高亮推给
 * UI（`ReaderTtsController.onUtteranceStarted`）。 语音数据全部来自系统（离线音色优先，符合 READER-45），不做在线合成。
 *
 * W6 T9r：**显式引擎回退**。部分设备（K60 / HyperOS 实测）没有默认 TTS 引擎，`TextToSpeech(context, listener)` 直接
 * status=-1 失败，但系统里装着可用引擎（`TTS_SERVICE`）。绑定顺序 = 默认构造 → [ttsEngineBindOrder]
 * 给出的已装引擎包名逐个显式绑定；全部失败才进入不可用态（文案见 [ReaderTtsNoEngineMessage]）。
 * 绑定只在用户主动点「听书」时发生（[ensureReady]），失败后不自动轮询重试，避免重试风暴。
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

    /** 失败原因（无引擎时为 [ReaderTtsNoEngineMessage]）。 */
    var initError: String? = null
        private set

    /** 当前绑定的引擎包名；null = 走系统默认构造。 */
    var boundEnginePackage: String? = null
        private set

    private var tts: TextToSpeech? = null
    private var preferredVoiceName: String? = null
    private val bindMutex = Mutex()

    /**
     * 绑定并等待引擎就绪（幂等；并发调用串行化）。
     *
     * 必须在带 Looper 的线程（Compose 主线程）调用：`TextToSpeech` 构造会绑当前线程的 Looper。 返回 false = 设备没有任何可绑定的引擎；此时
     * [initError] 已是用户可读文案。
     */
    suspend fun ensureReady(preferredVoice: String? = null, budgetMs: Long = 8_000L): Boolean {
        preferredVoice?.let { preferredVoiceName = it }
        if (ready) return true
        return bindMutex.withLock { if (ready) true else bindChain(budgetMs) }
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
        boundEnginePackage = null
    }

    // ---- 绑定链路 ----

    /** 默认构造优先，失败后按已装引擎包名逐个显式绑定；总预算 [budgetMs]，单引擎上限 [ReaderTtsPerEngineTimeoutMs]。 */
    private suspend fun bindChain(budgetMs: Long): Boolean {
        val installed = installedEnginePackages()
        val defaultEngine = defaultEnginePackage()
        val order = ttsEngineBindOrder(installed, defaultEngine)
        Timber.i("TTS 已装引擎=$installed，系统默认=$defaultEngine，显式回退顺序=$order")
        val candidates =
            buildList<String?> {
                add(null) // 系统默认构造：有默认引擎时一次成功，也覆盖「默认引擎不存在」的直接失败
                addAll(order)
            }
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        for (candidate in candidates) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            if (bindOnce(candidate, minOf(remaining, ReaderTtsPerEngineTimeoutMs))) return true
        }
        tts = null
        ready = false
        boundEnginePackage = null
        initError = ReaderTtsNoEngineMessage
        Timber.w("TTS 无可用引擎（已尝试默认构造 + ${order.size} 个已装引擎），听书不可用")
        return false
    }

    /** 绑定单个引擎（null = 系统默认构造）；成功则完成语言选择与回调注册。 */
    private suspend fun bindOnce(packageName: String?, timeoutMs: Long): Boolean {
        val signal = CompletableDeferred<Int>()
        val listener = TextToSpeech.OnInitListener { status ->
            if (!signal.isCompleted) signal.complete(status)
        }
        val instance = runCatching {
            if (packageName == null) TextToSpeech(context.applicationContext, listener)
            else TextToSpeech(context.applicationContext, listener, packageName)
        }
            .onFailure { Timber.w(it, "TTS 引擎绑定异常：${packageName ?: "系统默认"}") }
            .getOrNull()
        if (instance == null) return false
        val status = withTimeoutOrNull(timeoutMs) { signal.await() }
        if (status != TextToSpeech.SUCCESS) {
            Timber.w("TTS 引擎不可用：${packageName ?: "系统默认"}（status=$status）")
            runCatching {
                instance.stop()
                instance.shutdown()
            }
            return false
        }
        tts = instance
        boundEnginePackage = packageName
        ready = true
        initError = null
        applyPreferredVoice(instance)
        instance.setOnUtteranceProgressListener(
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
        Timber.i("TTS 引擎就绪：${packageName ?: "系统默认"}（语言=${runCatching { instance.voice?.locale }}）")
        return true
    }

    /**
     * 已装引擎包名：`PackageManager` 查 `TTS_SERVICE`（compileSdk 37 里 `TextToSpeech.getEngines()`
     * 已变成实例方法，取不到「未绑定前」的列表，且系统默认引擎缺失时正是我们要枚举的场景）。
     *
     * Android 11+ 的包可见性由 `AndroidManifest.xml` 的 `<queries><intent action=TTS_SERVICE>` 开放。
     */
    @Suppress("DEPRECATION")
    private fun installedEnginePackages(): List<String> {
        val fromPackages = runCatching {
            context.packageManager
                .queryIntentServices(
                    Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
                    0,
                )
                .mapNotNull { it.serviceInfo?.packageName?.trim() }
        }
            .onFailure { Timber.w(it, "查询 TTS_SERVICE 失败") }
            .getOrDefault(emptyList())
        val default = defaultEnginePackage()
        return (fromPackages + listOfNotNull(default)).filter { it.isNotEmpty() }.distinct()
    }

    /**
     * 系统默认 TTS 引擎包名（`tts_default_synth`）。
     *
     * 只是排序提示：没有默认引擎时返回 null，绑定链仍会逐个尝试已装引擎。
     */
    private fun defaultEnginePackage(): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, "tts_default_synth")
    }
        .onFailure { Timber.w(it, "读取默认 TTS 引擎失败") }
        .getOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /** 音色 / 语言：指定音色优先，其次简体中文、再英文（`isLanguageAvailable` 判定，缺数据也算不可用）。 */
    private fun applyPreferredVoice(engine: TextToSpeech) {
        val target = preferredVoiceName?.let { name ->
            runCatching { engine.voices?.firstOrNull { it.name == name } }
                .onFailure { Timber.w(it, "系统音色列表不可用") }
                .getOrNull()
        }
        if (target != null && runCatching { engine.voice = target }.isSuccess) return
        if (isLanguageUsable(engine, Locale.SIMPLIFIED_CHINESE)) {
            runCatching { engine.language = Locale.SIMPLIFIED_CHINESE }
            return
        }
        if (isLanguageUsable(engine, Locale.US)) {
            runCatching { engine.language = Locale.US }
        }
    }

    private fun isLanguageUsable(engine: TextToSpeech, locale: Locale): Boolean =
        runCatching { engine.isLanguageAvailable(locale) }.getOrDefault(-2) >=
            TextToSpeech.LANG_AVAILABLE

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
