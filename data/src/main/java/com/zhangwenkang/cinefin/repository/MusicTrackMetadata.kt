package com.zhangwenkang.cinefin.repository

/** W34：主库音频的专辑 / 艺人元数据（下载列表层级化用）。 */
data class MusicTrackMetadata(
    val itemId: java.util.UUID,
    val name: String,
    val albumName: String?,
    val artist: String?,
    val indexNumber: Int?,
    val imageUri: String?,
)
