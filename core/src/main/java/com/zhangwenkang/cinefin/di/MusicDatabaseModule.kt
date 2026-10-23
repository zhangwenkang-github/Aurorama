package com.zhangwenkang.cinefin.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.zhangwenkang.cinefin.database.music.LocalPlaylistDao
import com.zhangwenkang.cinefin.database.music.MusicDatabase
import com.zhangwenkang.cinefin.database.music.MusicQueueDao
import com.zhangwenkang.cinefin.database.music.MusicRecentDao
import com.zhangwenkang.cinefin.database.music.MusicStorage
import com.zhangwenkang.cinefin.database.music.MusicStorageImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 音乐线本地库（W21-R2）：独立于 `servers` 库的 `music` 库。
 *
 * 与 `DatabaseModule` 同款驱动构建，但迁移显式声明；队列、最近播放与 W87 本地歌单均不得因 schema 变化被自动清空。
 */
@Module
@InstallIn(SingletonComponent::class)
object MusicDatabaseModule {
    @Singleton
    @Provides
    fun provideMusicDatabase(@ApplicationContext app: Context): MusicDatabase =
        Room.databaseBuilder(app.applicationContext, MusicDatabase::class.java, "music")
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(MusicDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideMusicQueueDao(database: MusicDatabase): MusicQueueDao = database.musicQueueDao()

    @Provides
    fun provideMusicRecentDao(database: MusicDatabase): MusicRecentDao = database.musicRecentDao()

    @Provides
    fun provideLocalPlaylistDao(database: MusicDatabase): LocalPlaylistDao =
        database.localPlaylistDao()

    @Singleton
    @Provides
    fun provideMusicStorage(
        musicQueueDao: MusicQueueDao,
        musicRecentDao: MusicRecentDao,
    ): MusicStorage = MusicStorageImpl(musicQueueDao, musicRecentDao)
}
