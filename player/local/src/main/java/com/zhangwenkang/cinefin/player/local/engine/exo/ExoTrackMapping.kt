package com.zhangwenkang.cinefin.player.local.engine.exo

import androidx.media3.common.C
import com.zhangwenkang.cinefin.player.core.playback.EngineTrackType

/**
 * Exo 轨道 id 的编解码与字段归一（W4-T3a-1，纯 Kotlin / 纯 JVM 可测）。
 *
 * id 形如 `"groupIndex:trackIndex"`：编码的是 `Tracks.groups` 与组内轨道的下标， 同一播放会话内稳定——原地切换 / 重选轨靠它定位，不跨会话持久化。
 */
internal fun exoTrackId(
    groupIndex: Int,
    trackIndex: Int,
): String = "$groupIndex:$trackIndex"

/** 解析 [exoTrackId] 的产物；格式不合法（调用方传了别的内核的 id 等）时返回 null。 */
internal fun decodeExoTrackId(id: String): Pair<Int, Int>? {
    val parts = id.split(':')
    if (parts.size != 2) return null
    val groupIndex = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val trackIndex = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    return groupIndex to trackIndex
}

/** 引擎轨道类型 → media3 `C.TRACK_TYPE_*`。 */
internal fun EngineTrackType.toMedia3TrackType(): Int =
    when (this) {
        EngineTrackType.VIDEO -> C.TRACK_TYPE_VIDEO
        EngineTrackType.AUDIO -> C.TRACK_TYPE_AUDIO
        EngineTrackType.SUBTITLE -> C.TRACK_TYPE_TEXT
    }

/**
 * 编码名归一：小写、取 `/` 之后的短名、去掉 `.` 后缀（`S_TEXT/ASS` → `ass`，`mp4a.40.2` → `mp4a`）。
 *
 * 编码名缺失时退回 mime 的子类型；两者都没有返回 null。
 */
internal fun exoCodecName(
    codecs: String?,
    mimeType: String?,
): String? {
    val raw = codecs?.takeIf { it.isNotBlank() } ?: mimeType?.takeIf { it.isNotBlank() }
    return raw?.lowercase()?.substringAfterLast('/')?.substringBefore('.')?.takeIf {
        it.isNotBlank()
    }
}
