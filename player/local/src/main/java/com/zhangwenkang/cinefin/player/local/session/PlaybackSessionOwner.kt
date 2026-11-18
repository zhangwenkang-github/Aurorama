package com.zhangwenkang.cinefin.player.local.session

import com.zhangwenkang.cinefin.player.core.domain.models.PlaybackMediaIdentity
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerMediaInfo
import com.zhangwenkang.cinefin.player.local.domain.PlaybackQueueEntry
import com.zhangwenkang.cinefin.player.local.domain.PlaybackRequestToken
import com.zhangwenkang.cinefin.player.local.domain.PlaybackSnapshotState
import com.zhangwenkang.cinefin.player.local.domain.acceptCurrentStreamSnapshot
import com.zhangwenkang.cinefin.player.local.domain.advanceCurrentStreamRequest
import com.zhangwenkang.cinefin.player.local.domain.beginPlaybackRequest
import com.zhangwenkang.cinefin.player.local.domain.bindSourceSnapshot
import com.zhangwenkang.cinefin.player.local.presentation.PlaybackSessionState
import java.util.UUID

/**
 * 播放会话事实的唯一所有者（W2-T1：strangler 第一步）。
 *
 * 只保存事实，不做投影或时序决策：快照代次、请求令牌、会话状态、起播源意图。
 *
 * 快照写入后通过构造注入的 [onSnapshotsChanged] 通知调用方。
 *
 * ViewModel 注入 `::publishPlaybackSnapshots`，通知次数与旧实现的发布点一一对应。
 *
 * UI 投影、播放器读取与协程仍留在 ViewModel。
 *
 * 本类保持纯 Kotlin，不依赖 android.* / androidx.*，便于 T2 / T3 继续下沉。
 */
internal class PlaybackSessionOwner(private val onSnapshotsChanged: () -> Unit) {
    /** 源媒体与当前流的快照；只有本类可写。 */
    var playbackSnapshots: PlaybackSnapshotState = PlaybackSnapshotState()
        private set

    /** 当前有效的异步结果令牌；reset 后为 null。 */
    var activePlaybackToken: PlaybackRequestToken? = null
        private set

    /** 播放页会话状态；写入走 [updatePlaybackSessionState]，计算规则暂留在 ViewModel。 */
    var playbackSessionState: PlaybackSessionState = PlaybackSessionState()
        private set

    /** 最近一次 `initializePlayer` 的源意图：起播期源错误卡片的「重试」要靠它重新解析源。 */
    var lastSourceIntent: SourceResolutionIntent? = null
        private set

    /** 最近一次可重试的源意图；起播成功或新起播时清空。 */
    var failedSourceIntent: SourceResolutionIntent? = null
        private set

    /** 新请求开始：清空 source / current，并返回单调递增的代次。 */
    fun resetPlaybackSnapshots(): Long {
        val generation = playbackSnapshots.generation + 1L
        playbackSnapshots = PlaybackSnapshotState(generation = generation)
        activePlaybackToken = null
        onSnapshotsChanged()
        return generation
    }

    fun beginMediaSnapshot(identity: PlaybackMediaIdentity): PlaybackRequestToken {
        val (snapshots, token) = beginPlaybackRequest(playbackSnapshots, identity)
        playbackSnapshots = snapshots
        activePlaybackToken = token
        onSnapshotsChanged()
        return token
    }

    /**
     * 已有同 identity 且未失效的令牌时复用，否则重新开始一次请求。
     *
     * 复用条件必须是令牌、identity 与代次三者同时一致；否则晚到的旧结果会被误判为当前结果。
     */
    fun ensureMediaSnapshot(identity: PlaybackMediaIdentity): PlaybackRequestToken {
        val active = activePlaybackToken
        if (
            active != null &&
                active.identity == identity &&
                playbackSnapshots.identity == identity &&
                playbackSnapshots.generation == active.generation
        ) {
            return active
        }
        return beginMediaSnapshot(identity)
    }

    /** 切轨 / 内核轨道集变化：保留 source，只清旧 current 并让旧异步结果失效。 */
    fun advanceCurrentStreamGeneration(): PlaybackRequestToken? {
        val (snapshots, token) = advanceCurrentStreamRequest(playbackSnapshots)
        playbackSnapshots = snapshots
        if (token == null) return null
        activePlaybackToken = token
        onSnapshotsChanged()
        return token
    }

    /** 提交源快照：令牌已过期时快照保持不变，但仍照旧通知一次（与旧实现一致）。 */
    fun applySourceSnapshot(token: PlaybackRequestToken, source: PlayerMediaInfo?) {
        playbackSnapshots = bindSourceSnapshot(playbackSnapshots, token, source)
        onSnapshotsChanged()
    }

    /** 提交内核流快照：令牌已过期时快照保持不变，但仍照旧通知一次（与旧实现一致）。 */
    fun applyCurrentStreamSnapshot(token: PlaybackRequestToken, current: PlayerMediaInfo?) {
        playbackSnapshots = acceptCurrentStreamSnapshot(playbackSnapshots, token, current)
        onSnapshotsChanged()
    }

    fun updatePlaybackSessionState(state: PlaybackSessionState) {
        playbackSessionState = state
    }

    /** 新起播：忘记旧的失败意图并记录本次源意图。 */
    fun startSourceAttempt(intent: SourceResolutionIntent) {
        failedSourceIntent = null
        lastSourceIntent = intent
    }

    fun failSourceIntent(intent: SourceResolutionIntent) {
        failedSourceIntent = intent
    }

    /** 起播 / 播放错误：把最近一次源意图标记为可重试。 */
    fun failLastSourceIntent() {
        failedSourceIntent = lastSourceIntent
    }

    fun clearFailedSourceIntent() {
        failedSourceIntent = null
    }
}

/** 最近一次起播的源解析意图；起播期源错误的重试按它重新解析源、换新的转码会话。 */
internal data class SourceResolutionIntent(
    val itemId: UUID,
    val itemKind: String,
    val startFromBeginning: Boolean,
    val startPositionMs: Long,
    val playbackSessionId: String,
    val queueEntries: List<PlaybackQueueEntry>,
    val preferredMediaSourceId: String?,
    val sourceIntentId: String,
)
