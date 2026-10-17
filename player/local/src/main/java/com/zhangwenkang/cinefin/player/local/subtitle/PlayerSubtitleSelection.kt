package com.zhangwenkang.cinefin.player.local.subtitle

import com.zhangwenkang.cinefin.language.LanguageMatcher
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerSubtitleSource
import com.zhangwenkang.cinefin.settings.domain.Constants
import timber.log.Timber

/**
 * 手动选定的字幕记忆（W74 #14②）。
 *
 * 存成 `媒体 id|主字幕序号|次字幕序号`：`-1` 表示该位置是「显式关闭 / 没有」。 切码率 / 换内核 / 解码回退会重启播放页并重建字幕源清单，这条记忆让用户的手动选择活过重启。
 */
data class SubtitleManualSelection(
    val mediaId: String,
    val primaryIndex: Int,
    val secondaryIndex: Int,
) {
    fun encode(): String = "$mediaId|$primaryIndex|$secondaryIndex"

    companion object {
        /** 没有可恢复的序号 */
        const val NO_INDEX = -1

        fun decode(raw: String?): SubtitleManualSelection? {
            val parts = raw?.split('|') ?: return null
            if (parts.size != 3) return null
            val mediaId = parts[0].takeIf { it.isNotBlank() } ?: return null
            val primary = parts[1].toIntOrNull() ?: return null
            val secondary = parts[2].toIntOrNull() ?: return null
            return SubtitleManualSelection(mediaId, primary, secondary)
        }
    }
}

/** [SubtitleManualSelection] 在当前字幕源清单上的落点 */
data class RestoredSubtitleSelection(
    val primaryIndex: Int?,
    val secondaryIndex: Int?,
    val disabledByUser: Boolean,
)

/** 主 / 次字幕在 Jellyfin 源序号域的当前分配。 */
data class SubtitleTrackPair(
    val primaryIndex: Int? = null,
    val secondaryIndex: Int? = null,
) {
    val hasDistinctTracks: Boolean
        get() = primaryIndex != null && secondaryIndex != null && primaryIndex != secondaryIndex
}

/**
 * 单列表自由分配的纯函数。
 *
 * 主 / 次共用同一组源序号；任何一端分配到另一端的同一轨时，**清空原端**而不是留下同源双份。 这里只处理序号域，图形 / 原生限制与手动记忆写入由调用方负责。
 */
object SubtitleTrackAssignment {
    fun assignPrimary(
        current: SubtitleTrackPair,
        index: Int,
    ): SubtitleTrackPair =
        current.copy(
            primaryIndex = index,
            secondaryIndex = current.secondaryIndex.takeIf { it != index },
        )

    fun assignSecondary(
        current: SubtitleTrackPair,
        index: Int,
    ): SubtitleTrackPair =
        current.copy(
            primaryIndex = current.primaryIndex.takeIf { it != index },
            secondaryIndex = index,
        )

    /** 只有两端都是不同轨时才交换；否则返回 null，调用方据此禁用入口。 */
    fun swap(current: SubtitleTrackPair): SubtitleTrackPair? {
        if (!current.hasDistinctTracks) return null
        return SubtitleTrackPair(
            primaryIndex = current.secondaryIndex,
            secondaryIndex = current.primaryIndex,
        )
    }
}

/** mpv `track-list` 里一条字幕轨的最小映射信息：`id` 是原生 sid，不是 Jellyfin 源序号。 */
data class MpvSubtitleTrackRef(
    val id: Int,
    val label: String? = null,
    val language: String? = null,
    /** mpv `Format.codecs` / `sampleMimeType`；用于图形 / 文本类型一致性校验。 */
    val codec: String? = null,
)

/**
 * mpv 原生 sid 与 Jellyfin 字幕源序号的双向映射。
 *
 * 主字幕由 mpv `sid` 选择，次字幕由 Compose 按源文件加载；两者绝不能共用一个整数域。 数值同序号只在最后兜底：mpv track id 与 Jellyfin
 * 流序号本来就是两套编号，先比标题 / 语言并校验类型。 任何一级出现多个候选都视为歧义并返回 null，让面板保留原生选择而不是猜。
 */
object MpvSubtitleTrackMapping {
    private enum class TrackType {
        Text,
        Graphic,
    }

    fun sourceIndexForTrack(
        track: MpvSubtitleTrackRef,
        sources: List<PlayerSubtitleSource>,
    ): Int? {
        val trackType = trackType(track) ?: return null
        val typed = sources.filter { sourceType(it) == trackType }
        if (typed.isEmpty()) return null

        val label = track.label?.takeIf { it.isNotBlank() }
        val language = track.language?.takeIf { it.isNotBlank() }
        if (label != null && language != null) {
            val exact = typed.filter { it.title == label && it.language.equals(language, true) }
            if (exact.isNotEmpty()) return exact.singleOrNull()?.index
        }

        if (label != null) {
            val titled = typed.filter { it.title == label }
            if (titled.isNotEmpty()) return titled.singleOrNull()?.index
        }

        if (language != null) {
            val languageMatched = typed.filter { it.language.equals(language, true) }
            if (languageMatched.isNotEmpty()) return languageMatched.singleOrNull()?.index
        }

        // 最后才允许数值同序号；走到这里仍要求 codec 已知且类型一致（上面已过滤 typed）。
        return typed.filter { it.index == track.id }.singleOrNull()?.index
    }

    /** 次字幕只接受可解析文本源；图形轨即使能按序号映射，也不能进 Compose 双语层。 */
    fun secondarySourceIndexForTrack(
        track: MpvSubtitleTrackRef,
        sources: List<PlayerSubtitleSource>,
    ): Int? {
        val index = sourceIndexForTrack(track, sources) ?: return null
        return index.takeIf { value -> sources.any { it.index == value && it.isTextBased } }
    }

    /** 把次字幕源序号反查回可选的 mpv 原生轨；映射不到时返回 null。 */
    fun trackForSource(
        sourceIndex: Int,
        tracks: List<MpvSubtitleTrackRef>,
        sources: List<PlayerSubtitleSource>,
    ): MpvSubtitleTrackRef? =
        tracks.filter { sourceIndexForTrack(it, sources) == sourceIndex }.singleOrNull()

    /**
     * mpv 单列表里需要补成“仅次字幕可用”的纯源文本轨。
     *
     * [representedSourceIndices] 必须由调用方用**同一套** native sid 映射得出（包含侧载路径、 唯一标题 / 语言与同序号兜底）；这里不再自行按标题
     * / 语言重算，避免侧载行重复出现。
     */
    fun unmappedTextSources(
        representedSourceIndices: Set<Int>,
        sources: List<PlayerSubtitleSource>,
    ): List<PlayerSubtitleSource> = sources.filter {
        it.isTextBased && it.index !in representedSourceIndices
    }

    private fun trackType(track: MpvSubtitleTrackRef): TrackType? {
        val codec = track.codec?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val normalized = codec.substringAfterLast('/')
        return if (
            PlayerSubtitleSource.isGraphicCodec(codec) ||
                PlayerSubtitleSource.isGraphicCodec(normalized)
        ) {
            TrackType.Graphic
        } else {
            TrackType.Text
        }
    }

    private fun sourceType(source: PlayerSubtitleSource): TrackType =
        when {
            source.isGraphic -> TrackType.Graphic
            PlayerSubtitleSource.isGraphicCodec(source.codec) -> TrackType.Graphic
            source.isTextBased -> TrackType.Text
            else -> TrackType.Text
        }
}

/**
 * 字幕选择逻辑（纯函数，便于单测钉死；W74 #14）。
 *
 * 主 / 次字幕都按用户在设置里给的**语言优先级**挑选；识别不出语言时的兜底策略见 [pickPrimary]。 手动选择另存
 * [SubtitleManualSelection]（同一媒体重启后恢复）与语言偏好（跨视频继承）。
 */
object PlayerSubtitleSelection {

    /** 手动选轨时把该语言提到优先级最前；纯函数供 controller 复用并单测。 */
    fun promotedLanguagePriority(
        current: String,
        language: String,
        isSecondary: Boolean,
    ): String? {
        val tag = language.takeIf { it.isNotBlank() } ?: return null
        val default = if (isSecondary) emptyList() else LanguageMatcher.DEFAULT_SUBTITLE_PRIORITY
        val priority = LanguageMatcher.parsePriority(current, default)
        val base = LanguageMatcher.baseOf(tag)
        val updated = listOf(tag) + priority.filterNot { LanguageMatcher.baseOf(it) == base }
        return LanguageMatcher.priorityToString(updated)
    }

    /**
     * 主字幕：沿用语言优先级；优先级都没命中时按「默认轨兜底」。
     *
     * 兜底是「打开新片没有字幕」的修复点：语言线索缺失时挑一条最像默认轨的字幕， 保证「打开带字幕的片子就有字幕」，同时语言优先级仍然先生效。
     */
    fun pickPrimary(
        mode: String,
        priority: List<String>,
        sources: List<PlayerSubtitleSource>,
    ): PlayerSubtitleSource? {
        if (mode == Constants.SubtitleMode.OFF) return null

        val candidates = sources.filter { it.isTextBased }
        if (candidates.isEmpty()) return null

        val best =
            candidates
                .filter { source ->
                    LanguageMatcher.priorityIndex(source.language, priority) != null
                }
                .sortedWith(
                    compareBy(
                        { LanguageMatcher.priorityIndex(it.language, priority) },
                        // 同一语言下优先完整字幕（非强制字幕）
                        { if (it.isForced) 1 else 0 },
                        { if (it.isDefault) 0 else 1 },
                    )
                )
                .firstOrNull()
        if (best != null) {
            Timber.d("自动选中主字幕（语言命中）: index=${best.index} language=${best.language}")
            return best
        }

        // 没命中偏好语言（识别不出来 / 不在列表里）：默认轨兜底，别再让画面空着
        val fallback =
            candidates
                .sortedWith(
                    compareBy(
                        { if (it.isDefault) 0 else 1 },
                        // 强制字幕（Signs & Songs）只在没有完整字幕时才用
                        { if (it.isForced) 1 else 0 },
                        { it.index },
                    )
                )
                .firstOrNull()
        fallback?.let {
            Timber.d(
                "自动选中主字幕（默认轨兜底）: index=${it.index} language=${it.language} default=${it.isDefault}"
            )
        }
        return fallback
    }

    /**
     * 次字幕（双语）：只在用户设置过次字幕语言（手动选过次字幕 → 语言偏好被记住）时自动选； 永远不与主字幕同源。这条语言偏好是**用户级**的，所以换视频 / 换集后次字幕沿用同一语言。
     */
    fun pickSecondary(
        priority: List<String>,
        sources: List<PlayerSubtitleSource>,
        excludeIndex: Int?,
    ): PlayerSubtitleSource? {
        if (priority.isEmpty()) return null
        val picked =
            sources
                .filter { it.isTextBased && it.index != excludeIndex }
                .filter { source ->
                    LanguageMatcher.priorityIndex(source.language, priority) != null
                }
                .sortedWith(
                    compareBy(
                        { LanguageMatcher.priorityIndex(it.language, priority) },
                        { if (it.isForced) 1 else 0 },
                        { if (it.isDefault) 0 else 1 },
                    )
                )
                .firstOrNull()
        picked?.let { Timber.d("自动选中次字幕（语言记忆）: index=${it.index} language=${it.language}") }
        return picked
    }

    /**
     * 把手动记忆落到当前源清单上。
     *
     * 返回 null = 没有可用的记忆（媒体不同 / 主字幕序号在当前清单里找不到），调用方回落到自动选择； 找不到的**次**字幕只降级成「关」，不影响主字幕恢复。
     */
    fun restore(
        memory: SubtitleManualSelection?,
        mediaId: String,
        sources: List<PlayerSubtitleSource>,
    ): RestoredSubtitleSelection? {
        if (memory == null || memory.mediaId != mediaId) return null
        val primary =
            when {
                memory.primaryIndex == SubtitleManualSelection.NO_INDEX -> null
                sources.any { it.index == memory.primaryIndex } -> memory.primaryIndex
                else -> return null
            }
        val secondary =
            memory.secondaryIndex
                .takeIf { it != SubtitleManualSelection.NO_INDEX && it != primary }
                ?.takeIf { index -> sources.any { it.index == index } }
        return RestoredSubtitleSelection(
            primaryIndex = primary,
            secondaryIndex = secondary,
            disabledByUser = memory.primaryIndex == SubtitleManualSelection.NO_INDEX,
        )
    }
}
