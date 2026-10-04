package com.zhangwenkang.cinefin.di

import android.app.Application
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.JellyfinRepositoryImpl
import com.zhangwenkang.cinefin.repository.JellyfinRepositoryOfflineImpl
import com.zhangwenkang.cinefin.repository.MetadataPreloader
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Singleton
    @Provides
    fun provideJellyfinRepositoryImpl(
        application: Application,
        jellyfinApi: JellyfinApi,
        serverDatabase: ServerDatabaseDao,
        appPreferences: AppPreferences,
        localLibrary: LocalLibraryRepository,
    ): JellyfinRepositoryImpl {
        println("Creating new jellyfinRepositoryImpl")
        return JellyfinRepositoryImpl(
            application,
            jellyfinApi,
            serverDatabase,
            appPreferences,
            localLibrary,
        )
    }

    @Singleton
    @Provides
    fun provideJellyfinRepositoryOfflineImpl(
        application: Application,
        jellyfinApi: JellyfinApi,
        serverDatabase: ServerDatabaseDao,
        appPreferences: AppPreferences,
        localLibrary: LocalLibraryRepository,
    ): JellyfinRepositoryOfflineImpl {
        println("Creating new jellyfinRepositoryOfflineImpl")
        return JellyfinRepositoryOfflineImpl(
            application,
            jellyfinApi,
            serverDatabase,
            appPreferences,
            localLibrary,
        )
    }

    @Provides
    fun provideJellyfinRepository(
        jellyfinRepositoryImpl: JellyfinRepositoryImpl,
        jellyfinRepositoryOfflineImpl: JellyfinRepositoryOfflineImpl,
        appPreferences: AppPreferences,
    ): JellyfinRepository {
        println("Creating new JellyfinRepository")
        return when (appPreferences.getValue(appPreferences.offlineMode)) {
            true -> jellyfinRepositoryOfflineImpl
            false -> jellyfinRepositoryImpl
        }
    }

    /** W69b：元数据预加载器（每次取仓库都按当前在线 / 离线偏好解析，与页面一致）。 */
    @Singleton
    @Provides
    fun provideMetadataPreloader(
        repository: javax.inject.Provider<JellyfinRepository>
    ): MetadataPreloader = MetadataPreloader(repository)
}
