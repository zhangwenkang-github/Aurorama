package com.zhangwenkang.cinefin.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
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
 * 与 `DatabaseModule` 同款构建方式（AndroidSQLiteDriver + 破坏式迁移兜底）， 但单独成文件，避免动 W20 也会碰的
 * `DatabaseModule.kt`。
 */
@Module
@InstallIn(SingletonComponent::class)
object MusicDatabaseModule {
    @Singleton
    @Provides
    fun provideMusicDatabase(@ApplicationContext app: Context): MusicDatabase =
        Room.databaseBuilder(app.applicationContext, MusicDatabase::class.java, "music")
            .setDriver(AndroidSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideMusicQueueDao(database: MusicDatabase): MusicQueueDao = database.musicQueueDao()

    @Provides
    fun provideMusicRecentDao(database: MusicDatabase): MusicRecentDao = database.musicRecentDao()

    @Singleton
    @Provides
    fun provideMusicStorage(
        musicQueueDao: MusicQueueDao,
        musicRecentDao: MusicRecentDao,
    ): MusicStorage = MusicStorageImpl(musicQueueDao, musicRecentDao)
}
