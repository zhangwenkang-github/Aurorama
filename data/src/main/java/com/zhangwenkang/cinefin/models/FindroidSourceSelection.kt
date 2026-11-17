package com.zhangwenkang.cinefin.models

/**
 * 详情与播放共用的默认媒体源规则：可交付的 preferred id → 可交付的本地源 → 首个可交付源 → null。 任何调用方都不得再各自实现 `first()` / `[0]` 回落。
 */
fun selectFindroidSource(
    sources: List<FindroidSource>,
    preferredMediaSourceId: String? = null,
): FindroidSource? {
    val availableSources = sources.filter { it.isAvailableForSourceSelection() }
    preferredMediaSourceId?.let { preferred ->
        availableSources
            .firstOrNull { it.id == preferred }
            ?.let {
                return it
            }
    }
    return availableSources.firstOrNull { it.type == FindroidSourceType.LOCAL }
        ?: availableSources.firstOrNull()
}
