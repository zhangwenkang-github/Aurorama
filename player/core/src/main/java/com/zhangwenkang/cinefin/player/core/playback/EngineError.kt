package com.zhangwenkang.cinefin.player.core.playback

/**
 * 统一错误种类（W2-T4）。
 *
 * 只描述「错在哪一类」，不承载重试策略：重试预算、去重窗口与错误卡片归属仍在 `ErrorPolicy`。
 */
enum class EngineErrorKind {
    /** 起播前拿不到可播条目（源解析失败）。 */
    SOURCE_UNRESOLVED,

    /** 起播期源加载失败，还没出画面。 */
    SOURCE_START_FAILED,

    /** 网络 / IO 失败。 */
    IO_FAILED,

    /** 解码链路失败：解码器不可用、格式超出能力。 */
    DECODING_FAILED,

    /** 内核未能归类的失败（mpv 目前只上报这一类）。 */
    UNKNOWN,
}

/**
 * 统一错误模型（W2-T4）。
 *
 * 跨内核的错误载荷，取代「各内核各报一种异常」的形态。
 *
 * 本步只定义类型与映射入口（`player/local` 的 `engine.EngineErrorMapper`），不改任何现有错误流转： `PlaybackException` →
 * `ErrorPolicy` → `playerError` 的链路原样保留，接入归 T5。
 */
data class EngineError(
    /** 错误种类。 */
    val kind: EngineErrorKind,
    /** 给日志与错误卡片看的说明，可能较长。 */
    val message: String,
    /** 原始异常；没有底层异常时为 null。 */
    val cause: Throwable? = null,
)
