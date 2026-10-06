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

/**
 * 字幕选择逻辑（纯函数，便于单测钉死；W74 #14）。
 *
 * 主 / 次字幕都按用户在设置里给的**语言优先级**挑选；识别不出语言时的兜底策略见 [pickPrimary]。 手动选择另存
 * [SubtitleManualSelection]（同一媒体重启后恢复）与语言偏好（跨视频继承）。
 */
object PlayerSubtitleSelection {

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
