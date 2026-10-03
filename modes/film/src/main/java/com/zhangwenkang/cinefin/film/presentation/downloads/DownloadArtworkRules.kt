package com.zhangwenkang.cinefin.film.presentation.downloads

/**
 * W57 下载页缩略图回退优先级（纯函数，单测覆盖）。
 *
 * 用户口径：下载条目**本地优先**（下载时由 `ImagesDownloaderWorker` 落盘到
 * `filesDir/images/<itemId>/primary`），本地图缺失时才用服务器 URL 兜底； 两者都无 → 视图层用类型图标占位（任何网络下都不依赖服务器才能出图）。
 *
 * W59 追加：视频层级图**严格同级**（用户 2026-10-04 拍板）——节目 / 季只用自身海报，剧集只用自身缩略图 （帧图）；某级缺图直接类型占位，**不跨级回退**（避免串图）。
 */
object DownloadArtworkRules {

    /** W59 视频层级（电影 = 单条目自身主图）。 */
    enum class Level {
        SHOW,
        SEASON,
        EPISODE,
        MOVIE,
    }

    /** 本地优先：本地路径非空用本地，否则用远程 URL（可为 null）。 */
    fun resolve(localPath: String?, remoteUrl: String?): String? =
        localPath?.takeIf { it.isNotBlank() } ?: remoteUrl?.takeIf { it.isNotBlank() }

    /**
     * W59 视频层级取图（纯函数，单测覆盖）——每级**只用自己的图**。
     *
     * [ownLocal] / [ownRemote] = 本级自己的图（本地优先、服务器兜底）：节目 / 季 = 自己的海报，剧集 = 自己的缩略图（帧图）， 电影 =
     * 自己的主图。[seasonImage] / [showImage] 只作为「不可跨级回退」的显性输入：本级缺图时本函数**直接返回 null 交给类型占位**， 绝不采纳上一级的图。
     */
    fun videoArtwork(
        level: Level,
        ownLocal: String?,
        ownRemote: String?,
        seasonImage: String? = null,
        showImage: String? = null,
    ): String? =
        when (level) {
            // 电影 / 剧集 / 季 / 节目：只认本级自己的图；上一级图（seasonImage / showImage）永不参与。
            Level.MOVIE,
            Level.EPISODE,
            Level.SEASON,
            Level.SHOW -> resolve(ownLocal, ownRemote)
        }
}
