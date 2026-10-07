package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * W76-Q6：一次「解析播放信息」（`PlaybackInfo`）请求的缓存键。
 *
 * 同一条目在**不同起播位置**解析出的 [PlayerItem] 不是同一份结果：`startTimeTicks` 决定服务器转码会话从哪一刻开始
 * 生成分片（W73 #7），所以位置必须进键——只按 itemId 去重会把「从 0 起播」的请求接到「从 25 分钟起播」的转码地址上。
 */
internal data class PlaybackRequestKey(
    val itemId: UUID,
    val mediaSourceIndex: Int?,
    val startPositionTicks: Long,
)

/** 纯函数：解析播放信息的缓存键。位置按 Jellyfin ticks（100 ns）参与，负数（= 未指定 / 从 0）统一归一为 0。 */
internal fun playbackRequestKey(
    itemId: UUID,
    mediaSourceIndex: Int?,
    startPositionTicks: Long,
): PlaybackRequestKey =
    PlaybackRequestKey(
        itemId = itemId,
        mediaSourceIndex = mediaSourceIndex,
        startPositionTicks = startPositionTicks.coerceAtLeast(0L),
    )

/** W76-Q6：缓存取证用的计数（实际发出的请求数 / 复用已解析结果的次数）。 */
internal data class PlaybackRequestStats(
    val requests: Int,
    val hits: Int,
)

/**
 * W76-Q6：播放信息的「请求去重 / 结果复用」缓存。
 *
 * 背景：系列级起播后，后台补队列会为整剧每集解析一次播放信息（含转码会话创建）。旧实现只有一张「已构建条目」清单 （`PlaylistManager.playerItems`），挡不住两类重复：
 *
 * 1. **同一条目并发解析**：后台补片循环与 `onMediaItemTransition` 的「上一集 / 下一集」预取可能同时解析同一条目， 两边都看到「还没构建」→ 同一个
 *    `PlaybackInfo` 打两遍（还会往清单里塞两条）。
 * 2. **同一条目重复解析**：`getInitialItem` 每次都无条件重建起播条目——重复入队 / 起播集与后台补队列撞在一起时， 对已在时间线里的条目再打一遍
 *    `PlaybackInfo`。
 *
 * 实现：按 [PlaybackRequestKey] 做「一键一锁」的合并——同键请求串行，后到的调用者直接拿前一个的结果；不同键互不
 * 阻塞（保持旧实现「逐集串行请求」的节奏，不额外制造并发压力）。解析失败不落缓存，与旧行为一致（由调用方 `runCatching` 降级，下次仍可重试）。
 *
 * 生命周期：随 [PlaylistManager]（= 一次播放页）走。换内核 / 换码率都会 `viewModelStore.clear()` 后重建播放页、 缓存随之作废（也正因为如此，影响
 * `PlaybackInfo` 的偏好——转码档位 / 码率——不需要进键）；转码 seek 重开会话走 `forceRefresh`，不会复用旧地址。
 */
internal class PlaybackRequestCache {
    private val lock = Mutex()
    private val keyLocks = mutableMapOf<PlaybackRequestKey, Mutex>()
    private val resolved = mutableMapOf<PlaybackRequestKey, PlayerItem>()

    @Volatile private var requests: Int = 0

    @Volatile private var hits: Int = 0

    /** 取缓存；没有（或被 [forceRefresh] 作废）时才执行 [loader] 解析一次。同键并发只解析一次。 */
    suspend fun resolve(
        key: PlaybackRequestKey,
        forceRefresh: Boolean = false,
        loader: suspend () -> PlayerItem,
    ): PlayerItem {
        val keyLock = lock.withLock { keyLocks.getOrPut(key) { Mutex() } }
        return keyLock.withLock {
            if (!forceRefresh) {
                resolved[key]?.let { cached ->
                    hits++
                    Timber.d(
                        "PlaybackInfo 复用缓存：item=%s posTicks=%d",
                        key.itemId,
                        key.startPositionTicks,
                    )
                    return@withLock cached
                }
            }
            val item = loader()
            requests++
            resolved[key] = item
            Timber.d(
                "PlaybackInfo 解析请求：item=%s posTicks=%d",
                key.itemId,
                key.startPositionTicks,
            )
            item
        }
    }

    fun stats(): PlaybackRequestStats = PlaybackRequestStats(requests = requests, hits = hits)
}
