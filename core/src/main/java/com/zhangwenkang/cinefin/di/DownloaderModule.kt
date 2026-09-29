package com.zhangwenkang.cinefin.di

import android.app.Application
import androidx.work.WorkManager
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.Downloader
import com.zhangwenkang.cinefin.utils.DownloaderImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloaderModule {
    @Singleton
    @Provides
    fun provideDownloader(
        application: Application,
        serverDatabase: ServerDatabaseDao,
        jellyfinRepository: JellyfinRepository,
        appPreferences: AppPreferences,
        workManager: WorkManager,
    ): Downloader {
        return DownloaderImpl(
            application,
            serverDatabase,
            jellyfinRepository,
            appPreferences,
            workManager,
        )
    }
}
