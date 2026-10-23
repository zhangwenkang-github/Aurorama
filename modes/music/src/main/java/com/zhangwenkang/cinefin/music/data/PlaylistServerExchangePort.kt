package com.zhangwenkang.cinefin.music.data

/**
 * 服务器歌单互导的未来能力边界。
 *
 * 1.4.0 不提供实现、不调用 Jellyfin 写 API、不在 UI 暴露入口。[document] 中的文件内容不能绕过本地导入直接写服务器。
 */
interface PlaylistServerExchangePort {
    val writeEnabled: Boolean
        get() = false

    suspend fun importPlaylist(document: PlaylistExchangeDocument): Boolean
}
