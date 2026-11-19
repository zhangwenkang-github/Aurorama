package com.zhangwenkang.cinefin.player.local.mpv

import com.zhangwenkang.cinefin.player.core.playback.EngineTrack
import com.zhangwenkang.cinefin.player.core.playback.EngineTrackType
import org.json.JSONArray

/**
 * mpv `track-list` 的记录模型与到引擎轨道的映射（W4-T3a-1）。
 *
 * 与 `MPVPlayer` 既有的 media3 `Tracks` 构建刻意分开：那份 `Tracks` 是 media3 面（MediaSession / 既有 UI）的投影， 改它的
 * `selectionFlags` 会影响 `TrackSelectionEngine` 的语言选择；引擎枚举只需要读，所以单独解析同一份 JSON， 不触碰既有行为。
 */
internal data class MpvTrackEntry(
    val id: Int,
    val type: EngineTrackType,
    val language: String?,
    val title: String?,
    val codec: String?,
    val isExternal: Boolean,
    val isDefault: Boolean,
    val isForced: Boolean,
)

/** mpv 轨道类型 → 引擎轨道类型（不做默认兜底：解析阶段已按 type 字段过滤）。 */
internal fun MPVTrackType.toEngineTrackType(): EngineTrackType =
    when (this) {
        MPVTrackType.VIDEO -> EngineTrackType.VIDEO
        MPVTrackType.AUDIO -> EngineTrackType.AUDIO
        MPVTrackType.SUBTITLE -> EngineTrackType.SUBTITLE
    }

/** 引擎轨道类型 → mpv 轨道类型。 */
internal fun EngineTrackType.toMpvTrackType(): MPVTrackType =
    when (this) {
        EngineTrackType.VIDEO -> MPVTrackType.VIDEO
        EngineTrackType.AUDIO -> MPVTrackType.AUDIO
        EngineTrackType.SUBTITLE -> MPVTrackType.SUBTITLE
    }

/** 单条记录 → 引擎轨道：id 直接用 mpv 轨道 id（同一会话内稳定）。 */
internal fun MpvTrackEntry.toEngineTrack(): EngineTrack =
    EngineTrack(
        id = id.toString(),
        type = type,
        language = language?.takeIf { it.isNotBlank() },
        title = title?.takeIf { it.isNotBlank() },
        codec = codec?.lowercase()?.takeIf { it.isNotBlank() },
        isExternal = isExternal,
        isDefault = isDefault,
        isForced = isForced,
    )

/**
 * 解析 mpv `track-list` 属性。
 *
 * 缺 `type` / 非法 `id` 的记录丢弃；整份 JSON 解析失败返回空表（与接口「不支持时返回空表」一致）。
 */
internal fun parseMpvTrackList(trackListJson: String?): List<MpvTrackEntry> {
    if (trackListJson.isNullOrBlank()) return emptyList()
    return runCatching {
            val tracks = JSONArray(trackListJson)
            (0 until tracks.length()).mapNotNull { index ->
                val track = tracks.optJSONObject(index) ?: return@mapNotNull null
                val type =
                    MPVTrackType.entries.firstOrNull { it.type == track.optString("type") }
                        ?: return@mapNotNull null
                val id = track.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
                MpvTrackEntry(
                    id = id,
                    type = type.toEngineTrackType(),
                    language = track.optString("lang").takeIf { it.isNotBlank() },
                    title = track.optString("title").takeIf { it.isNotBlank() },
                    codec = track.optString("codec").takeIf { it.isNotBlank() },
                    isExternal = track.optBoolean("external"),
                    isDefault = track.optBoolean("default"),
                    isForced = track.optBoolean("forced"),
                )
            }
        }
        .getOrDefault(emptyList())
}
