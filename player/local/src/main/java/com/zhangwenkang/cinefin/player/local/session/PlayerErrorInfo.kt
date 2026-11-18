package com.zhangwenkang.cinefin.player.local.session

/**
 * 播放错误：给控制层的错误卡片用（阶段 1.3）。
 *
 * W2-T2 从 `PlayerViewModel` 的嵌套类型搬到 session 包，作为 [ErrorPolicy] 的唯一错误载荷。
 *
 * 保持纯 Kotlin：message / codeName / backend 三个字段，无 Android 依赖。
 */
data class PlayerErrorInfo(
    /** 后端给出的说明，可能较长，UI 侧截断显示 */
    val message: String,
    /** 错误码名，例如 ERROR_CODE_DECODING_FAILED */
    val codeName: String,
    /** 出错时使用的内核：exoplayer / mpv */
    val backend: String,
)
