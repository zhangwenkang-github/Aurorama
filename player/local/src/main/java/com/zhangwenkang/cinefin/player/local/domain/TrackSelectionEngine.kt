package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import com.zhangwenkang.cinefin.language.LanguageMatcher
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.Constants
import timber.log.Timber

/**
 * 字幕与音轨的智能选择引擎。
 *
 * 关键点在于“语言线索”的来源往往很脏：Jellyfin 元数据可能只有 `chi`， 轨道标题可能是“简体中文”或 `Chinese (Simplified)`，外挂字幕则可能写进文件名。
 * 这里统一交给 [LanguageMatcher] 归一化后，再按用户设定的优先级挑选， 从而做到“打开任何一部片子都自动选到想看的字幕，并且换视频后设置依然生效”。
 */
class TrackSelectionEngine(private val appPreferences: AppPreferences) {
    /** 字幕模式：auto / always / off */
    val subtitleMode: String
        get() = appPreferences.getValue(appPreferences.subtitleMode)

    val subtitlePriority: List<String>
        get() =
            LanguageMatcher.parsePriority(
                appPreferences.getValue(appPreferences.preferredSubtitleLanguages),
                LanguageMatcher.DEFAULT_SUBTITLE_PRIORITY,
            )

    val audioPriority: List<String>
        get() =
            LanguageMatcher.parsePriority(
                appPreferences.getValue(appPreferences.preferredAudioLanguages),
                LanguageMatcher.DEFAULT_AUDIO_PRIORITY,
            )

    /**
     * 依据当前媒体实际包含的轨道，计算应该使用的轨道选择参数。
     *
     * @param subtitlesManaged 字幕已由自研字幕管线 / 图形字幕路由接管：此时这里完全不碰
     *   文字轨（启用 / 禁用与 override 都不动）。否则「引擎选文字轨 → 路由再禁掉」会让
     *   参数来回变化，触发 onTracksChanged 死循环。
     */
    fun parameters(
        current: TrackSelectionParameters,
        tracks: Tracks,
        subtitlesManaged: Boolean = false,
    ): TrackSelectionParameters {
        var builder = current.buildUpon()

        if (!subtitlesManaged) {
            // 字幕开关
            builder =
                when (subtitleMode) {
                    Constants.SubtitleMode.OFF ->
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    else -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                }

            val text = pickTextTrack(tracks)
            if (text != null) {
                builder =
                    builder.setOverrideForType(
                        TrackSelectionOverride(text.group.mediaTrackGroup, text.index)
                    )
                Timber.d("自动选择字幕轨道: ${text.tag} (${text.format.label})")
            }
        }

        val audio = pickAudioTrack(tracks)
        if (audio != null) {
            builder =
                builder.setOverrideForType(
                    TrackSelectionOverride(audio.group.mediaTrackGroup, audio.index)
                )
            Timber.d("自动选择音轨: ${audio.tag} (${audio.format.label})")
        }

        return builder.build()
    }

    /** 记住用户手动选择的语言：把它提到优先级列表最前面， 这样下一个视频（以及下一集）会自动沿用同一语言。 */
    fun rememberSelectedLanguage(
        trackType: @C.TrackType Int,
        format: Format?,
    ) {
        if (!appPreferences.getValue(appPreferences.rememberTrackSelection)) return
        val tag = format?.let { detectLanguage(it) } ?: return

        val isText = trackType == C.TRACK_TYPE_TEXT
        val preference =
            if (isText) {
                appPreferences.preferredSubtitleLanguages
            } else {
                appPreferences.preferredAudioLanguages
            }
        val default =
            if (isText) {
                LanguageMatcher.DEFAULT_SUBTITLE_PRIORITY
            } else {
                LanguageMatcher.DEFAULT_AUDIO_PRIORITY
            }

        val current = LanguageMatcher.parsePriority(appPreferences.getValue(preference), default)
        // 同语言族的旧条目先移除，避免出现 zh-Hans 与 zh 同时存在造成的歧义
        val base = LanguageMatcher.baseOf(tag)
        val updated = listOf(tag) + current.filterNot { LanguageMatcher.baseOf(it) == base }
        appPreferences.setValue(preference, LanguageMatcher.priorityToString(updated))
        Timber.i("已记住${if (isText) "字幕" else "音轨"}语言偏好: $tag")
    }

    private data class Candidate(
        val group: Tracks.Group,
        val index: Int,
        val format: Format,
        val tag: String?,
        val priority: Int,
        val forced: Boolean,
        val isDefault: Boolean,
    )

    private fun pickTextTrack(tracks: Tracks): Candidate? {
        val mode = subtitleMode
        if (mode == Constants.SubtitleMode.OFF) return null

        val candidates = collect(tracks, C.TRACK_TYPE_TEXT)
        if (candidates.isEmpty()) return null

        candidates
            .filter { it.priority >= 0 }
            .sortedWith(
                compareBy(
                    { it.priority },
                    // 同一语言下优先完整字幕（非强制字幕）
                    { if (it.forced) 1 else 0 },
                    { if (it.isDefault) 0 else 1 },
                )
            )
            .firstOrNull()
            ?.let {
                return it
            }

        // 没有命中偏好语言：自动模式不显示字幕，始终显示模式挑一条默认轨道
        return when (mode) {
            Constants.SubtitleMode.ALWAYS ->
                candidates.sortedBy { if (it.isDefault) 0 else 1 }.firstOrNull()
            else -> null
        }
    }

    private fun pickAudioTrack(tracks: Tracks): Candidate? =
        collect(tracks, C.TRACK_TYPE_AUDIO)
            .filter { it.priority >= 0 }
            .sortedWith(compareBy({ it.priority }, { if (it.isDefault) 0 else 1 }))
            .firstOrNull()

    private fun collect(
        tracks: Tracks,
        type: @C.TrackType Int,
    ): List<Candidate> {
        val priority = if (type == C.TRACK_TYPE_TEXT) subtitlePriority else audioPriority
        return tracks.groups
            .filter { it.type == type }
            .flatMap { group ->
                (0 until group.length).mapNotNull { index ->
                    if (!group.isTrackSupported(index)) return@mapNotNull null
                    val format = group.getTrackFormat(index)
                    val tag = detectLanguage(format)
                    Candidate(
                        group = group,
                        index = index,
                        format = format,
                        tag = tag,
                        priority = LanguageMatcher.priorityIndex(tag, priority) ?: -1,
                        forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                        isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                    )
                }
            }
    }

    /** 依次使用轨道语言标签、标题、轨道 id（外挂字幕常把文件名放在 id 里）识别语言 */
    private fun detectLanguage(format: Format): String? =
        LanguageMatcher.detect(
            format.language,
            format.label,
            format.id?.let { LanguageMatcher.fromFileName(it) ?: it },
        )
}
