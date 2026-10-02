package com.zhangwenkang.cinefin.di

import android.content.Context
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.DownloadMediaSidecar
import com.zhangwenkang.cinefin.utils.OfflineMediaRepository
import com.zhangwenkang.cinefin.utils.OfflineMediaRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** W36：离线媒体目录（视频 / 音乐 / 书籍）的依赖注入。 */
@Module
@InstallIn(SingletonComponent::class)
object OfflineMediaModule {

    @Singleton
    @Provides
    fun provideOfflineMediaRepository(
        @ApplicationContext context: Context,
        serverDatabase: ServerDatabaseDao,
        readerRepository: ReaderRepository,
        appPreferences: AppPreferences,
    ): OfflineMediaRepository =
        OfflineMediaRepositoryImpl(
            context = context,
            database = serverDatabase,
            sidecar = DownloadMediaSidecar(context),
            readerRepository = readerRepository,
            appPreferences = appPreferences,
        )
}
