package com.zhangwenkang.cinefin.di

import com.zhangwenkang.cinefin.music.data.MusicLyricsOverlayHost
import com.zhangwenkang.cinefin.playback.CinefinPlaybackServiceStarter
import com.zhangwenkang.cinefin.playback.MusicLyricsOverlayHostImpl
import com.zhangwenkang.cinefin.player.local.domain.PlaybackServiceStarter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 播放会话相关绑定（W1 R2）。
 *
 * `player:local` 定义 [PlaybackServiceStarter] 接口，由宿主 App 提供实现（服务类在 app:phone， 播放模块不能反向依赖）。
 */
@Module
@InstallIn(SingletonComponent::class)
interface PlaybackModule {
    @Binds
    fun bindPlaybackServiceStarter(impl: CinefinPlaybackServiceStarter): PlaybackServiceStarter

    @Binds fun bindMusicLyricsOverlayHost(impl: MusicLyricsOverlayHostImpl): MusicLyricsOverlayHost
}
