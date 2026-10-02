package com.zhangwenkang.cinefin.music.presentation.di

import android.content.Context
import com.zhangwenkang.cinefin.music.data.MusicRepository
import com.zhangwenkang.cinefin.music.data.MusicRepositoryImpl
import com.zhangwenkang.cinefin.music.data.lyrics.JellyfinLyricsRemoteSource
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsCache
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsOverrideStore
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsRemoteSource
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsRepository
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsRepositoryImpl
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackController
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackControllerImpl
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackStateSource
import com.zhangwenkang.cinefin.player.local.domain.MusicQueueEditor
import com.zhangwenkang.cinefin.player.local.domain.PlaybackCoordinator
import com.zhangwenkang.cinefin.player.local.domain.PlaybackCoordinatorImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

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

    @Binds fun bindMusicQueueEditor(impl: MusicPlaybackControllerImpl): MusicQueueEditor

    @Binds fun bindPlaybackCoordinator(impl: PlaybackCoordinatorImpl): PlaybackCoordinator

    @Binds fun bindLyricsRepository(impl: LyricsRepositoryImpl): LyricsRepository

    @Binds fun bindLyricsRemoteSource(impl: JellyfinLyricsRemoteSource): LyricsRemoteSource

    companion object {
        /**
         * 歌词文件缓存（`<filesDir>/lyrics`）。
         *
         * 用文件而不是 Room：W3 波次里 R1-OFFLINE 在改 Room schema / 版本号，歌词缓存没必要挤进同一个数据库（见 `MUSIC_PLAN` 决策）。
         */
        @Provides
        @Singleton
        fun provideLyricsCache(@ApplicationContext context: Context): LyricsCache =
            LyricsCache(File(context.filesDir, "lyrics"))

        /**
         * 本机歌词覆盖存储（`<filesDir>/lyrics/override`，W25-MUSIC）。
         *
         * 与缓存同目录树、但独立子目录：覆盖是"用户显式编辑"的结果，生命周期独立于自动缓存（缓存清理不应误删覆盖）。
         */
        @Provides
        @Singleton
        fun provideLyricsOverrideStore(@ApplicationContext context: Context): LyricsOverrideStore =
            LyricsOverrideStore(
                File(File(context.filesDir, "lyrics"), LyricsOverrideStore.DIRECTORY_NAME)
            )
    }
}
