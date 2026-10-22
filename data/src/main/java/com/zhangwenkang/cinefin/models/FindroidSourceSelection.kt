package com.zhangwenkang.cinefin.models

/** 详情与播放共用的默认媒体源规则：preferred id → 本地源 → 首个源 → null。 任何调用方都不得再各自实现 `first()` / `[0]` 回落。 */
fun selectFindroidSource(
    sources: List<FindroidSource>,
    preferredMediaSourceId: String? = null,
): FindroidSource? {
    preferredMediaSourceId?.let { preferred ->
        sources
            .firstOrNull { it.id == preferred }
            ?.let {
                return it
            }
    }
    return sources.firstOrNull { it.type == FindroidSourceType.LOCAL } ?: sources.firstOrNull()
}
