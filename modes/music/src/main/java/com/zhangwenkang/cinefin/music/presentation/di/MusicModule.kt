package com.zhangwenkang.cinefin.music.presentation.di

import com.zhangwenkang.cinefin.music.data.MusicRepository
import com.zhangwenkang.cinefin.music.data.MusicRepositoryImpl
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackController
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackControllerImpl
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackStateSource
import com.zhangwenkang.cinefin.player.local.domain.PlaybackCoordinator
import com.zhangwenkang.cinefin.player.local.domain.PlaybackCoordinatorImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 音乐模式 DI（W0 预建，W1 R2 追加绑定）。
 *
 * 本文件是音乐线的 DI 扩展点：曲库仓库、音乐播放控制器与音视频互斥仲裁都在此绑定； 不新增独立 DI 文件，后续 W2/W3 的音乐相关绑定也追加在这里。
 */
@Module
@InstallIn(SingletonComponent::class)
interface MusicModule {
    @Binds fun bindMusicRepository(impl: MusicRepositoryImpl): MusicRepository

    @Binds
    fun bindMusicPlaybackController(impl: MusicPlaybackControllerImpl): MusicPlaybackController

    @Binds
    fun bindMusicPlaybackStateSource(impl: MusicPlaybackControllerImpl): MusicPlaybackStateSource

    @Binds fun bindPlaybackCoordinator(impl: PlaybackCoordinatorImpl): PlaybackCoordinator
}
