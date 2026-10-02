package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicItemSourceFilter

/**
 * W39 音乐库文案（纯函数，便于单测）：
 * - 计数副题按来源筛选细化（本地 / 服务器前缀，全部不加前缀）；
 * - 空态按来源分支（本地 / 服务器 / 全部 / 离线），不再出现「在服务器添加音乐」这类错源提示。
 */
private const val LOCAL_EMPTY_MESSAGE = "在『媒体库 → 本地媒体库』添加包含音乐的文件夹后回来"

/** 曲库计数副题（顶栏）：筛选「本地」→「共 N 张本地专辑」；服务器同理；全部不加前缀。 */
internal fun musicLibrarySubtitle(
    tab: MusicTab,
    filter: MusicItemSourceFilter,
    count: Int,
    offline: Boolean,
): String {
    val prefix =
        when {
            offline -> ""
            filter == MusicItemSourceFilter.LOCAL -> "本地"
            filter == MusicItemSourceFilter.SERVER -> "服务器"
            else -> ""
        }
    return when (tab) {
        MusicTab.ALBUMS -> "共 $count 张${prefix}专辑"
        MusicTab.ARTISTS -> "共 $count 位${prefix}艺术家"
        MusicTab.SONGS -> "共 $count 首${prefix}歌曲"
        MusicTab.PLAYLISTS -> "共 $count 个歌单"
    }
}

/** 空态文案（标题 + 说明）；说明为 null 时不显示第二行。 */
internal data class MusicEmptyCopy(val title: String, val message: String?)

/**
 * 曲库空态按来源分支（W39）：
 * - 本地：「本地还没有音乐」/ 去『媒体库 → 本地媒体库』添加含音乐的文件夹；
 * - 服务器：「服务器音乐库里还没有专辑」/「下拉可刷新」；
 * - 全部：「服务器和本地都还没有音乐」/ 添加后下拉刷新；
 * - 离线：不发网络请求，提示下载或下拉刷新本地索引；
 * - 歌单：只有服务器提供歌单，按服务器口径提示（下拉可刷新）。
 */
internal fun musicEmptyCopy(
    tab: MusicTab,
    filter: MusicItemSourceFilter,
    offline: Boolean,
): MusicEmptyCopy {
    if (offline) {
        return when (tab) {
            MusicTab.PLAYLISTS -> MusicEmptyCopy("离线模式没有歌单", "歌单需要联网查看；下拉可刷新本地音乐索引")
            else ->
                MusicEmptyCopy(
                    "离线模式还没有可播放的音乐",
                    "联网后在曲目菜单点「下载」，或下拉刷新本地音乐索引",
                )
        }
    }
    if (tab == MusicTab.PLAYLISTS) {
        return MusicEmptyCopy("服务器上没有歌单", "下拉可刷新")
    }
    val what =
        when (tab) {
            MusicTab.ALBUMS -> "专辑"
            MusicTab.ARTISTS -> "艺术家"
            MusicTab.SONGS -> "歌曲"
            MusicTab.PLAYLISTS -> "歌单"
        }
    return when (filter) {
        MusicItemSourceFilter.LOCAL -> MusicEmptyCopy("本地还没有音乐", LOCAL_EMPTY_MESSAGE)
        MusicItemSourceFilter.SERVER -> MusicEmptyCopy("服务器音乐库里还没有$what", "下拉可刷新")
        MusicItemSourceFilter.ALL -> MusicEmptyCopy("服务器和本地都还没有音乐", "在服务器或本地媒体库添加音乐后，下拉刷新")
    }
}
