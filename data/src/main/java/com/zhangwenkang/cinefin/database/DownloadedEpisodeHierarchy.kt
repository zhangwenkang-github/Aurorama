package com.zhangwenkang.cinefin.database

import java.util.UUID

/** W34：已下载剧集的归属信息（节目 / 季），供下载列表层级化使用。 */
data class DownloadedEpisodeHierarchy(
    val episodeId: UUID,
    val seasonId: UUID,
    val seriesId: UUID,
    val episodeName: String,
    val episodeIndex: Int,
    val runtimeTicks: Long,
    val seriesName: String,
    val seasonName: String,
    val seasonIndex: Int,
)
