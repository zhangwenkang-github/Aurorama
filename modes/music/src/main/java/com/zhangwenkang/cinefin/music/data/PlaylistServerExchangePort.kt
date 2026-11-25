package com.zhangwenkang.cinefin.music.data

/**
 * 服务器歌单「整份导入」的能力边界（W3-T19 后的现状说明）。
 *
 * 服务器歌单的日常写操作已经支持并开放入口：新建歌单、加入曲目、移出曲目、重命名、删除、歌单内换位 （见 `MusicRepository.createServerPlaylist /
 * addToPlaylist / removeFromPlaylist / renameServerPlaylist / deleteServerPlaylist /
 * moveServerPlaylistItem`）。本接口只描述**整份文件导入服务器**这一尚未开放的能力： 目前 [writeEnabled] 恒为 false，[document]
 * 中的文件内容仍不能绕过本地导入直接写服务器。
 */
interface PlaylistServerExchangePort {
    val writeEnabled: Boolean
        get() = false

    suspend fun importPlaylist(document: PlaylistExchangeDocument): Boolean
}
