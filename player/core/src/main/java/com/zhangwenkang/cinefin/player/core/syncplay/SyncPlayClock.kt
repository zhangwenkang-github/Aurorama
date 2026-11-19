package com.zhangwenkang.cinefin.player.core.syncplay

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay as coroutineDelay
import timber.log.Timber

/** 本地时钟端口：四时间戳里 t1（发报）/ t4（收报）的唯一来源。 */
fun interface SyncPlayLocalClock {
    fun now(): Instant

    companion object {
        /** 生产用系统时钟。 */
        val System: SyncPlayLocalClock = SyncPlayLocalClock { Instant.now() }
    }
}

/** 采样间隔等待端口：单测注入虚拟 ticker，不睡真实时间。 */
fun interface SyncPlayDelay {
    suspend fun await(duration: Duration)

    companion object {
        /** 生产默认：`kotlinx.coroutines.delay`。 */
        val Default: SyncPlayDelay = SyncPlayDelay { coroutineDelay(it.toMillis()) }
    }
}

/**
 * 一次四时间戳采样（对应 jellyfin-web `TimeSync.js` 的 `Measurement`）。
 *
 * t1 = [requestSentTime]（本地发报），t2 = [serverRequestReceptionTime]，t3 =
 * [serverResponseTransmissionTime]， t4 = [requestReceivedTime]（本地收报）。公式与上游逐字一致，不做任何取整或“优化”。
 */
class SyncPlayClockSample(
    val requestSentTime: Instant,
    val serverRequestReceptionTime: Instant,
    val serverResponseTransmissionTime: Instant,
    val requestReceivedTime: Instant,
) {
    /** `((t2 − t1) + (t3 − t4)) / 2`：服务器时刻 − 本地时刻。 */
    val offset: Duration =
        Duration.between(requestSentTime, serverRequestReceptionTime)
            .plus(Duration.between(requestReceivedTime, serverResponseTransmissionTime))
            .dividedBy(2)

    /** `(t4 − t1) − (t3 − t2)`：扣掉服务器处理耗时后的往返时延。 */
    val roundTripDelay: Duration =
        Duration.between(requestSentTime, requestReceivedTime)
            .minus(Duration.between(serverRequestReceptionTime, serverResponseTransmissionTime))

    /** 上游 `getPing()`：`delay / 2`（上游对毫秒四舍五入，这里保留纳秒精度，差异 < 1ms）。 */
    val ping: Duration
        get() = roundTripDelay.dividedBy(2)
}

/**
 * 时钟的只读观测面（T8c 状态机与 T8d 诊断消费）。
 *
 * [isSynchronized] 表示至少有一个样本；未收敛时 [offset] 恒为 0，[SyncPlayClock.nowServer] 退化为本地时刻。
 */
data class SyncPlayClockState(
    val offset: Duration = Duration.ZERO,
    val roundTripDelay: Duration = Duration.ZERO,
    val sampleCount: Int = 0,
) {
    val isSynchronized: Boolean
        get() = sampleCount > 0
}

/**
 * SyncPlay 时钟同步（W4-T8b / VIDEO-38），与 jellyfin-web `src/plugins/syncPlay/core/timeSync/TimeSync.js`
 * 的算法逐字对应：
 *
 * - 窗口保留最近 [SAMPLE_WINDOW_SIZE] 个样本，取 [SyncPlayClockSample.roundTripDelay] 最小者的 offset 作为当前偏移；
 * - 前 [GREEDY_SAMPLE_COUNT] 个样本按 [GREEDY_INTERVAL] 贪心快采，之后切 [STEADY_INTERVAL] 低功耗间隔；
 * - 未收敛（无样本）时 offset 为 0，[nowServer] 等于本地时刻。
 *
 * 三个外部依赖（[SyncPlayTimeSource] / [SyncPlayLocalClock] / [SyncPlayDelay]）都是端口，单测用虚拟时钟 + 虚拟 ticker
 * 驱动，不睡真实时间、不触网。观察面 [state] / [nowServer] 可在任意线程读；采样循环由调用方 scope 承载（T8c）。
 */
class SyncPlayClock(
    private val timeSource: SyncPlayTimeSource,
    private val localClock: SyncPlayLocalClock = SyncPlayLocalClock.System,
    private val pause: SyncPlayDelay = SyncPlayDelay.Default,
) {
    private val lock = Any()
    private val samples = ArrayDeque<SyncPlayClockSample>(SAMPLE_WINDOW_SIZE + 1)

    @Volatile private var snapshot = SyncPlayClockState()

    /** 当前偏移 / 时延 / 窗口样本数的只读快照。 */
    val state: SyncPlayClockState
        get() = snapshot

    /** 服务器“现在”：本地时刻 + 当前偏移（上游 `localDateToRemote`）。 */
    fun nowServer(): Instant = localClock.now().plus(snapshot.offset)

    /**
     * 采一次样并入窗，返回本次样本。
     *
     * 采样失败（[SyncPlayTimeSource] 抛异常）原样抛出，且不改变已收敛的偏移与窗口；轮询是否继续由调用方决定。
     */
    suspend fun sampleOnce(): SyncPlayClockSample {
        val requestSentTime = localClock.now()
        val serverTime = timeSource.fetchServerTime()
        val requestReceivedTime = localClock.now()
        val sample =
            SyncPlayClockSample(
                requestSentTime = requestSentTime,
                serverRequestReceptionTime = serverTime.requestReceptionTime,
                serverResponseTransmissionTime = serverTime.responseTransmissionTime,
                requestReceivedTime = requestReceivedTime,
            )
        synchronized(lock) {
            samples.addLast(sample)
            while (samples.size > SAMPLE_WINDOW_SIZE) {
                samples.removeFirst()
            }
            // minBy 取第一个最小值：等时延并列时保留窗口内更早的样本，与上游稳定排序一致。
            val best = samples.minBy { it.roundTripDelay }
            snapshot =
                SyncPlayClockState(
                    offset = best.offset,
                    roundTripDelay = best.roundTripDelay,
                    sampleCount = samples.size,
                )
        }
        return sample
    }

    /**
     * 采样轮询主循环：先按 [nextInterval] 等待，再采样，直到协程被取消。
     *
     * 单次采样失败只记日志、不终止轮询；取消异常原样抛出。
     */
    suspend fun run(): Nothing {
        while (true) {
            pause.await(nextInterval())
            try {
                sampleOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "SyncPlay 时钟同步采样失败，等待下个周期")
            }
        }
    }

    /** 清空窗口并回到未收敛状态（退组 / 换服务器时调用，对应上游 `resetMeasurements`）。 */
    fun reset() {
        synchronized(lock) {
            samples.clear()
            snapshot = SyncPlayClockState()
        }
    }

    private fun nextInterval(): Duration =
        if (snapshot.sampleCount < GREEDY_SAMPLE_COUNT) GREEDY_INTERVAL else STEADY_INTERVAL

    companion object {
        /** 上游 `NumberOfTrackedMeasurements`。 */
        const val SAMPLE_WINDOW_SIZE = 8

        /** 上游 `GreedyPingCount`：窗口少于该数时保持贪婪间隔。 */
        const val GREEDY_SAMPLE_COUNT = 3

        /** 上游 `PollingIntervalGreedy`。 */
        val GREEDY_INTERVAL: Duration = Duration.ofSeconds(1)

        /** 上游 `PollingIntervalLowProfile`。 */
        val STEADY_INTERVAL: Duration = Duration.ofSeconds(60)
    }
}
