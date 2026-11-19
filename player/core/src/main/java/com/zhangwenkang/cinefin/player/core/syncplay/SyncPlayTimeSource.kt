package com.zhangwenkang.cinefin.player.core.syncplay

import java.time.Instant

/**
 * `GET /GetUtcTime` 的两个服务器时间戳（W4-T8b）。
 *
 * 结构与 data 层 T8a 的 `SyncPlayServerTime` 一致；`player:core` 不依赖 `data`，装配时由 T8d 做一次字段搬运。
 */
data class SyncPlayServerTimeSample(
    /** 服务器收到请求的时刻（四时间戳里的 t2）。 */
    val requestReceptionTime: Instant,
    /** 服务器发出响应的时刻（四时间戳里的 t3）。 */
    val responseTransmissionTime: Instant,
)

/**
 * 服务器时间源端口（W4-T8b / VIDEO-38）。
 *
 * 生产实现在 T8d 包一层 `SyncPlayGateway.getServerTime()`；本模块只依赖这个接口，单测注入 fake，不触网。
 *
 * 失败（网络 / 401 / 端点缺失）直接抛异常：单次采样失败不会污染已收敛的偏移，[SyncPlayClock.run] 会继续轮询。
 */
interface SyncPlayTimeSource {
    suspend fun fetchServerTime(): SyncPlayServerTimeSample
}
