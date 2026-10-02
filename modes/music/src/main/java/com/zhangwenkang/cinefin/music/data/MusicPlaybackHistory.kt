package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackController
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 播放历史（W23-MUSIC · C 组，纯逻辑，可 JVM 单测）。
 *
 * 用途只有一个：随机播放时"上一曲 = 按播放历史回到上一首**实际播放过**的歌曲"。 内核的 `seekToPrevious` 在随机模式下走的是打乱顺序里的上一项，遇到手动跳播 / 随机起点
 * 就不等于真实历史，所以这里单独记一份"播放先后"。
 *
 * 语义：
 * - [record] 每次当前曲目变化（自动衔接 / 手动切歌 / 点队列跳播）都会记录，重复曲目移到末尾；
 * - [previous] 先摘掉当前曲目，再从历史尾部往前找**仍在当前队列里**的曲目，返回它的索引； 找不到返回 null（曲目已被移出队列 / 历史为空），调用方回退到内核行为。
 */
class MusicPlaybackHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = ArrayDeque<UUID>()

    /** 只读快照（单测与日志用）。 */
    val snapshot: List<UUID>
        get() = entries.toList()

    fun record(itemId: UUID) {
        if (entries.lastOrNull() == itemId) return
        entries.remove(itemId)
        entries.addLast(itemId)
        while (entries.size > capacity) {
            entries.removeFirst()
        }
    }

    fun previous(queueItemIds: List<UUID>, currentItemId: UUID?): Int? {
        if (currentItemId != null && entries.lastOrNull() == currentItemId) {
            entries.removeLast()
        }
        while (entries.isNotEmpty()) {
            val candidate = entries.removeLast()
            val index = queueItemIds.indexOf(candidate)
            if (index >= 0) return index
        }
        return null
    }

    fun clear() = entries.clear()

    companion object {
        const val DEFAULT_CAPACITY = 128
    }
}

/**
 * 进程级播放历史跟踪（W23-MUSIC · C 组）。
 *
 * 与 [MusicQueuePersister] 同一套路：`@Singleton` + 自己的协程作用域，由音乐页首次进入时 `start()`
 * 一次；离开音乐页后后台仍在播放，历史照样记录（桌面歌词 / 通知栏切歌也会被记到）。
 */
@Singleton
class MusicPlaybackHistoryTracker
@Inject
constructor(private val playbackController: MusicPlaybackController) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val history = MusicPlaybackHistory()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            playbackController.queue
                .map { queue -> queue?.currentItem?.itemId }
                .distinctUntilChanged()
                .collect { itemId -> itemId?.let(history::record) }
        }
    }

    /** 随机模式的"上一首"：返回队列里的目标索引；null = 历史里没有可用曲目。 */
    fun previousIndex(queueItemIds: List<UUID>, currentItemId: UUID?): Int? =
        history.previous(queueItemIds, currentItemId)
}
