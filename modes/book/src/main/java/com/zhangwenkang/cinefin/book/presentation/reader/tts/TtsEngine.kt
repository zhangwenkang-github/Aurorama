package com.zhangwenkang.cinefin.book.presentation.reader.tts

/**
 * 朗读引擎抽象（W6 T9）：控制器只依赖这三个动作，系统 `TextToSpeech` 与单测 fake 都可实现。
 *
 * 引擎回调（开口 / 句末 / 出错）由实现方转给 [ReaderTtsController.onUtteranceStarted] 等方法。
 */
internal interface TtsEngine {
    /** 设置语速（倍速）；实现方自行 clamp 到平台可接受区间。 */
    fun setRate(rate: Float)

    /** 朗读一句；返回 false = 引擎不可用（未就绪 / 无可用音色）。 */
    fun speak(text: String, utteranceId: String): Boolean

    /** 立即停止当前朗读（暂停 / 跳句 / 退出）。 */
    fun stop()
}
