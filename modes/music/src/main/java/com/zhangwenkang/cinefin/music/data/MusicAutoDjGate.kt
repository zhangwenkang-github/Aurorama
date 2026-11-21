package com.zhangwenkang.cinefin.music.data

/** Auto DJ 闸门决策（设计定稿五 §3）：只有 [APPEND] 才允许追加续播批次。 */
internal enum class AutoDjDecision {
    APPEND,
    SKIP,
}

/** 闸门命中格：只用于日志与刹车提示分流，不改变决策语义。 */
internal enum class AutoDjGateReason {
    /** 前五格通过（此时第 6 格尚未评估）或六格全过。 */
    PROCEED,
    /** ① 偏好开关关闭。 */
    DISABLED,
    /** ② 种子曲不是服务器来源（本地 SAF 曲目 / 队列里找不到种子）。 */
    SEED_NOT_SERVER,
    /** ③ 睡眠定时「播完当前曲目停止」挂起或倒计时已到期。 */
    SLEEP_STOP_PENDING,
    /** ④ 队尾由 T8 错误跳曲导致。 */
    ENDED_BY_ERROR,
    /** ⑤ 同一播放会话内连续自动追加已满上限（刹车）。 */
    BATCH_LIMIT,
    /** ⑥ InstantMix 拉取失败或返回空。 */
    INSTANT_MIX_UNAVAILABLE,
}

internal data class AutoDjGateResult(val decision: AutoDjDecision, val reason: AutoDjGateReason)

/**
 * 闸门输入。
 *
 * [instantMixItemCount]：`null` = 这一轮尚未拉取 InstantMix（只评估前五格，通过 = 放行去拉取）； 非空 = 拉取结果条数（0 视同失败 / 空）。
 */
internal data class AutoDjGateInput(
    val enabled: Boolean,
    val seedIsServerItem: Boolean,
    val sleepStopPending: Boolean,
    val endedByError: Boolean,
    val autoAppendBatches: Int,
    val instantMixItemCount: Int? = null,
)

/** 单批续播曲目数（设计定稿五 §2：limit=10）。 */
internal const val AUTO_DJ_INSTANT_MIX_LIMIT = 10

/** 同一播放会话内连续自动追加的批数上限（设计定稿五 §3 第 5 格）。 */
internal const val AUTO_DJ_MAX_BATCHES = 3

/**
 * Auto DJ 六格闸门（纯函数，设计定稿五 §3）。
 *
 * 任一格不满足即 [AutoDjDecision.SKIP]，判定顺序固定为 ① 开关 → ② 种子来源 → ③ 睡眠挂起 → ④ 队尾成因 → ⑤ 刹车 → ⑥ InstantMix。
 * 前五格不依赖网络，控制器先用 `instantMixItemCount = null` 过一遍，避免开关关着还发请求；拿到结果后再用真实条数复评第 6 格。
 */
internal fun autoDjGate(input: AutoDjGateInput): AutoDjGateResult =
    when {
        !input.enabled -> AutoDjGateResult(AutoDjDecision.SKIP, AutoDjGateReason.DISABLED)
        !input.seedIsServerItem ->
            AutoDjGateResult(AutoDjDecision.SKIP, AutoDjGateReason.SEED_NOT_SERVER)
        input.sleepStopPending ->
            AutoDjGateResult(AutoDjDecision.SKIP, AutoDjGateReason.SLEEP_STOP_PENDING)
        input.endedByError -> AutoDjGateResult(AutoDjDecision.SKIP, AutoDjGateReason.ENDED_BY_ERROR)
        input.autoAppendBatches >= AUTO_DJ_MAX_BATCHES ->
            AutoDjGateResult(AutoDjDecision.SKIP, AutoDjGateReason.BATCH_LIMIT)
        input.instantMixItemCount == null ->
            // 前五格已通过：放行到「拉取 InstantMix」阶段
            AutoDjGateResult(AutoDjDecision.APPEND, AutoDjGateReason.PROCEED)
        input.instantMixItemCount <= 0 ->
            AutoDjGateResult(AutoDjDecision.SKIP, AutoDjGateReason.INSTANT_MIX_UNAVAILABLE)
        else -> AutoDjGateResult(AutoDjDecision.APPEND, AutoDjGateReason.PROCEED)
    }
