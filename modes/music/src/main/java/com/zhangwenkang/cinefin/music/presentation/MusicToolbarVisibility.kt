package com.zhangwenkang.cinefin.music.presentation

/**
 * 音乐页头部的可见性规则（2.0.2 D2 重排后拆分）。
 *
 * - [showsSource]：标签行「⋮」菜单是否给出「来源筛选 / 来源徽标」（离线只有单一本地来源，不显示）；
 * - [showsPlayAll]：「播放全部 + 随机」紧凑工具行是否显示——只对 [MusicTab.SONGS] / [MusicTab.ALBUMS] 有意义，
 *   且列表非空、非详情、非批量多选态。
 */
internal data class MusicPrimaryToolbarVisibility(
    val showsSource: Boolean,
    val showsPlayAll: Boolean,
) {
    /** 内容区上方的紧凑工具行是否显示（等价于「播放全部」可见）。 */
    val hasToolbar: Boolean
        get() = showsPlayAll

    companion object {
        /** 「播放全部」有意义的 tab：歌曲 / 专辑。 */
        private val PlayAllTabs = setOf(MusicTab.SONGS, MusicTab.ALBUMS)

        fun forState(
            tab: MusicTab,
            offline: Boolean,
            songCount: Int,
            detailPresent: Boolean,
            batchMode: Boolean,
        ): MusicPrimaryToolbarVisibility {
            val toolbarRequested = !detailPresent && !batchMode
            return MusicPrimaryToolbarVisibility(
                showsSource = toolbarRequested && !offline,
                showsPlayAll = toolbarRequested && songCount > 0 && tab in PlayAllTabs,
            )
        }
    }
}

internal data class MusicBatchActionAccessibility(
    val contentDescription: String,
    val stateDescription: String?,
    val enabled: Boolean,
)

internal fun musicBatchActionAccessibility(
    label: String,
    enabled: Boolean,
    loading: Boolean,
): MusicBatchActionAccessibility =
    MusicBatchActionAccessibility(
        contentDescription = label,
        stateDescription =
            when {
                loading -> "正在处理"
                !enabled -> "不可用"
                else -> null
            },
        enabled = enabled && !loading,
    )
