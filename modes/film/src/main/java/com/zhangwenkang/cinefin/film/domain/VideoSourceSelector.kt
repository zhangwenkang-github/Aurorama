package com.zhangwenkang.cinefin.film.domain

import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.selectFindroidSource

/**
 * 详情与播放共用的默认媒体源规则：
 * 1. 明确 preferred mediaSourceId 且存在时优先；
 * 2. 否则优先本地下载源；
 * 3. 没有本地源时取首个远端源；
 * 4. 空列表返回 null。
 */
fun selectSourceForMetadata(
    sources: List<FindroidSource>,
    preferredMediaSourceId: String? = null,
): FindroidSource? {
    return selectFindroidSource(sources, preferredMediaSourceId)
}
