package com.zhangwenkang.cinefin.book.presentation.reader.tts

import org.readium.r2.shared.publication.Locator

/** 语速区间（定稿六 §1：0.5–2.0×）。 */
internal val ReaderTtsRateRange = 0.5f..2.0f

/** 默认语速（1.0×）。 */
internal const val ReaderTtsDefaultRate = 1.0f

/** 朗读位置推进后连续出错的上限：超过即停止，避免在坏书上空转。 */
internal const val ReaderTtsMaxConsecutiveErrors = 3

/** 无系统 TTS 引擎时给用户看的唯一文案（K60 实测：HyperOS 无默认引擎时会走到这条）。 */
internal const val ReaderTtsNoEngineMessage = "设备无可用 TTS 引擎，请在系统设置中安装语音引擎"

/** 单个 TTS 引擎的绑定等待上限（逐个引擎回退时，避免坏引擎拖住整条链路）。 */
internal const val ReaderTtsPerEngineTimeoutMs = 3_000L

/** 一句话（句切分后的一条）：定位信息用来下发给 Decoration 高亮与章节跳转。 */
internal data class TtsSentence(
    val chapterIndex: Int,
    /** 章内序号（0 起）。 */
    val index: Int,
    val text: String,
    /** 所属资源 href（含 `#fragment` 时按资源比对）。 */
    val href: String,
    /** 整条 Readium Locator JSON；null = 句→locator 映射失败（降级：只读不高亮）。 */
    val locatorJson: String?,
    /** 全书进度（0–1）；映射失败时为 null。 */
    val progression: Double?,
) {
    /** 映射失败句：仍可朗读，但不参与高亮（观察项 R5）。 */
    val highlightable: Boolean
        get() = locatorJson != null
}

/** 一章（一个 spine 资源）的朗读文本。 */
internal data class TtsChapter(
    val index: Int,
    val title: String,
    val href: String,
    val sentences: List<TtsSentence>,
)

/** 朗读引擎的播放态。 */
internal enum class TtsPlayback {
    Idle,
    /** 正在解析章节文本（首启 / 跨章）。 */
    Preparing,
    Playing,
    Paused,
}

/** 定时关闭档位（定稿六 §4）：15 / 30 / 60 分钟 + 本章结束后停止。 */
internal enum class TtsTimerOption(val minutes: Int?, val label: String) {
    Off(null, "不定时"),
    Fifteen(15, "15 分钟"),
    Thirty(30, "30 分钟"),
    Sixty(60, "60 分钟"),
    EndOfChapter(null, "本章结束后停止");

    val isEndOfChapter: Boolean
        get() = this == EndOfChapter

    companion object {
        /** 控制条「定时」弹层的顺序（不定时在前，便于取消）。 */
        val menu: List<TtsTimerOption> = listOf(Fifteen, Thirty, Sixty, EndOfChapter, Off)
    }
}

/** 听书起点（定稿六 §2：当前位置 / 章首二选一）。 */
internal enum class ReaderTtsStartPoint(val label: String) {
    CurrentPosition("从当前位置"),
    ChapterStart("从章首"),
}

/** 系统可用音色（离线优先：中文 / 英文）。 */
internal data class TtsVoiceOption(val name: String, val label: String, val locale: String)

/**
 * 下发给 Readium 的句高亮（W6 T9 / READER-33）：定位 + 淡入起止 tint。
 *
 * W6 T9r2：200ms 淡入改由导航器侧在同一个 effect 内分档下发（[fadeFromTint] → [fadeToTint]）， 句高亮状态本身只随「句
 * locator」变化——避免 tint 每 50ms 变化把在途下发取消掉。
 */
internal data class ReaderTtsDecoration(
    val locator: Locator,
    /** 淡入起点：页面底色上的 tint（不可见）。 */
    val fadeFromTint: Int,
    /** 淡入终点：18% accent 反解后的 tint。 */
    val fadeToTint: Int,
)

/** 朗读状态快照：Compose 控制条、前台通知与单元测试共用同一份结构。 */
internal data class ReaderTtsUiState(
    val playback: TtsPlayback = TtsPlayback.Idle,
    /** 当前句；null = 未开始 / 已停止。 */
    val active: TtsSentence? = null,
    /** 引擎已回调 onStart / onRangeStart（句高亮只在真正开口后出现）。 */
    val speaking: Boolean = false,
    val rate: Float = ReaderTtsDefaultRate,
    val timer: TtsTimerOption = TtsTimerOption.Off,
    /** 定时关闭剩余毫秒；null = 未设或已到期。 */
    val timerRemainingMs: Long? = null,
    /** 已加载的句子总数（控制条用于判断是否可播放）。 */
    val sentenceCount: Int = 0,
    /** 正在解析的章节序号（Preparing 时给控制条文案用）。 */
    val loadingChapter: Int? = null,
    /** 映射失败（只读不高亮）的句子数：日志观察项 R5。 */
    val degradedSentences: Int = 0,
    /** 引擎不可用（设备没有任何可绑定的 TTS 引擎）：非阻塞诊断，用户重试成功即清空。 */
    val engineError: String? = null,
    /** 停止原因等一次性提示。 */
    val message: String? = null,
) {
    val playing: Boolean
        get() = playback == TtsPlayback.Playing

    /** 句高亮是否该显示：真正开口中，或暂停时保留位置。 */
    val highlightVisible: Boolean
        get() = active != null && (speaking || playback == TtsPlayback.Paused)
}
