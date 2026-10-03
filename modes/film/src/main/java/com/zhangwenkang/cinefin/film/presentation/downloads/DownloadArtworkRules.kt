package com.zhangwenkang.cinefin.film.presentation.downloads

/**
 * W57 下载页缩略图回退优先级（纯函数，单测覆盖）。
 *
 * 用户口径：下载条目**本地优先**（下载时由 `ImagesDownloaderWorker` 落盘到
 * `filesDir/images/<itemId>/primary`），本地图缺失时才用服务器 URL 兜底； 两者都无 → 视图层用类型图标占位（任何网络下都不依赖服务器才能出图）。
 */
object DownloadArtworkRules {

    /** 本地优先：本地路径非空用本地，否则用远程 URL（可为 null）。 */
    fun resolve(localPath: String?, remoteUrl: String?): String? =
        localPath?.takeIf { it.isNotBlank() } ?: remoteUrl?.takeIf { it.isNotBlank() }

    /** 剧集回退链：条目本地图 → 季本地海报 → 节目本地海报 → 远程兜底。 */
    fun videoFallback(
        localItem: String?,
        localSeason: String?,
        localSeries: String?,
        remoteFallback: String?,
    ): String? =
        listOf(localItem, localSeason, localSeries, remoteFallback).firstOrNull {
            !it.isNullOrBlank()
        }
}
