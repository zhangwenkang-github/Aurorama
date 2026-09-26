package com.zhangwenkang.cinefin.setup.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.setup.data.SetupRepositoryImpl
import com.zhangwenkang.cinefin.setup.domain.SetupRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SetupDataModule {
    @Singleton
    @Provides
    fun provideSetupRepository(
        jellyfinApi: JellyfinApi,
        serverDatabase: ServerDatabaseDao,
        appPreferences: AppPreferences,
    ): SetupRepository {
        return SetupRepositoryImpl(
            jellyfinApi = jellyfinApi,
            database = serverDatabase,
            appPreferences = appPreferences,
        )
    }
}
