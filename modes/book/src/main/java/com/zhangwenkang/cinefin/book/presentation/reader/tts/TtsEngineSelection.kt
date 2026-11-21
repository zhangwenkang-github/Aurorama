package com.zhangwenkang.cinefin.book.presentation.reader.tts

/**
 * TTS 引擎绑定顺序（W6 T9r，纯函数）。
 *
 * K60 实测（HyperOS）：设备没有任何默认 TTS 引擎（`tts_default_synth = null`），`TextToSpeech(context, listener)`
 * 直接初始化失败；但系统里装着可用引擎（如 `com.xiaomi.mibrain.speech`）。所以绑定分两步：
 * 先试系统默认构造（有默认引擎时零开销），失败再按本函数给出的包名顺序逐个显式绑定。
 *
 * 排序口径（确定性、可测）：默认引擎置顶（若它确实在已装列表里），其余按包名升序去重，保证同一台设备 每次重试顺序一致、便于日志比对。
 */
internal fun ttsEngineBindOrder(installed: List<String>, defaultEngine: String?): List<String> {
    val normalized = installed.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    val default = defaultEngine?.trim()?.takeIf { it.isNotEmpty() && it in normalized }
    return if (default == null) normalized
    else listOf(default) + normalized.filterNot { it == default }
}
