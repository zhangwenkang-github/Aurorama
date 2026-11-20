package com.zhangwenkang.cinefin.player.local.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import com.zhangwenkang.cinefin.player.local.domain.isPlayingMusicItem
import com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音乐输出侧实际参数（W5-T6 · MUSIC-34）：ExoPlayer 当前选中音频轨的 [Format] 运行时读数。
 *
 * 取不到一律留 null，由 UI 降级显示「—」；不做猜测（例如没有选中轨时不要拿首轨冒充）。
 */
data class MusicAudioOutputInfo(
    val codec: String? = null,
    val container: String? = null,
    val sampleRate: Int? = null,
    val channelCount: Int? = null,
    val bitrate: Int? = null,
)

/** [Format] → 输出快照（纯转换，便于单测）。 */
fun audioOutputInfo(format: Format?): MusicAudioOutputInfo? {
    if (format == null) return null
    return MusicAudioOutputInfo(
        codec = format.codecs ?: format.sampleMimeType,
        container = format.containerMimeType,
        sampleRate = format.sampleRate.takeIf { it > 0 },
        channelCount = format.channelCount.takeIf { it > 0 },
        bitrate = format.bitrate.takeIf { it > 0 },
    )
}

/** 当前选中音频轨的 Format；没有选中轨时为 null。 */
fun Tracks.selectedAudioFormat(): Format? =
    groups
        .firstOrNull { group -> group.type == C.TRACK_TYPE_AUDIO && group.isSelected }
        ?.let { group ->
            (0 until group.length)
                .firstOrNull(group::isTrackSelected)
                ?.let(group.mediaTrackGroup::getFormat)
        }

/**
 * 输出侧一致性守卫（W5-fixpack）：只有 Exo 当前 `mediaItem` 与面板展示的 [itemId] 同一代时才返回读数。
 *
 * AUTO 切歌瞬间 `queue.currentItem` 还停在旧曲目、Exo 的 `currentTracks` 已是新曲目；不加这道校验会把两首歌的参数混排进同一张面板。 对不上就返回
 * null，由 UI 走既有「—（未播放）」降级，不猜也不冒充。
 */
internal fun audioOutputInfoForItem(
    itemId: UUID,
    currentMediaId: String?,
    tracks: Tracks,
): MusicAudioOutputInfo? =
    if (currentMediaId != itemId.toString()) null else audioOutputInfo(tracks.selectedAudioFormat())

/**
 * 音乐输出信息读取入口（W5-T6）：只在音乐会话且当前条目是音乐曲目时读数，避免把视频会话的音频参数当成音乐输出。
 *
 * UI 在打开信息面板时同步调用一次 [snapshot]（主线程，`currentTracks` 读取开销可忽略）；[itemId] 必须是面板正在展示的曲目， 播放器当前条目与之不一致时返回
 * null（切歌瞬间的代际校验）。
 */
@Singleton
class MusicAudioOutputInfoSource @Inject constructor(private val playerHolder: PlayerHolder) {

    fun snapshot(itemId: UUID): MusicAudioOutputInfo? {
        val player = playerHolder.existingPlayer ?: return null
        if (!playerHolder.musicSessionActive || !player.isPlayingMusicItem()) return null
        return audioOutputInfoForItem(
            itemId = itemId,
            currentMediaId = player.currentMediaItem?.mediaId,
            tracks = player.currentTracks,
        )
    }
}
