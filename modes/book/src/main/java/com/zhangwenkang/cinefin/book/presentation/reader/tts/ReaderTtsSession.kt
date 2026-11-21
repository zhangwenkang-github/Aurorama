package com.zhangwenkang.cinefin.book.presentation.reader.tts

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 阅读页 ↔ 听书前台服务 的进程内桥（W6 T9）。
 *
 * TTS 引擎与状态机留在阅读页（避免跨进程搬运 `TextToSpeech`）；服务只做两件事：把 [state] 镜像成通知 / MediaSession 状态，以及把通知上的动作回灌给
 * [controls]。 阅读页销毁即 [clear]，服务见 `Idle` 自行退出。
 */
internal object ReaderTtsSession {

    /** 通知 / 锁屏 / 耳机的控制入口（由阅读页实现，转发给 `ReaderTtsController`）。 */
    interface Controls {
        fun play()

        fun pause()

        fun toggle()

        fun next()

        fun previous()

        fun stop()
    }

    private val _state = MutableStateFlow(ReaderTtsUiState())
    val state: StateFlow<ReaderTtsUiState> = _state.asStateFlow()

    @Volatile
    var controls: Controls? = null
        private set

    /** 通知第二行的书名。 */
    @Volatile var bookTitle: String = ""

    /** 打开 App 用的 PendingIntent 载体（阅读页把当前书的启动意图交给通知）。 */
    @Volatile var readerIntent: Intent? = null

    fun attach(controls: Controls, title: String, intent: Intent?) {
        this.controls = controls
        bookTitle = title
        readerIntent = intent
    }

    fun publish(state: ReaderTtsUiState) {
        _state.value = state
    }

    fun detach() {
        controls = null
        _state.value = ReaderTtsUiState()
    }
}

/** 听书前台服务的启停（阅读页调用；服务本身见 [ReaderTtsService]）。 */
internal object ReaderTtsNotifier {

    fun start(context: Context) {
        val intent =
            Intent(context, ReaderTtsService::class.java).setAction(ReaderTtsService.ACTION_START)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { Timber.w(it, "听书前台服务启动失败") }
    }

    fun stop(context: Context) {
        val intent =
            Intent(context, ReaderTtsService::class.java).setAction(ReaderTtsService.ACTION_STOP)
        runCatching { context.startService(intent) }.onFailure { Timber.w(it, "听书前台服务停止失败") }
    }
}
