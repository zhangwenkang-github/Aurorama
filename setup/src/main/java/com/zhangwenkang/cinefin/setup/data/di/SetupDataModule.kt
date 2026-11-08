package com.zhangwenkang.cinefin.setup.data.di

import android.content.Context
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.api.JellyfinApiFactory
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.identity.IdentityWriteCoordinator
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.setup.data.SetupRepositoryImpl
import com.zhangwenkang.cinefin.setup.domain.SetupRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SetupDataModule {
    @Singleton
    @Provides
    fun provideJellyfinApiFactory(
        @ApplicationContext context: Context,
        jellyfinApi: JellyfinApi,
        appPreferences: AppPreferences,
    ): JellyfinApiFactory =
        JellyfinApiFactory(jellyfinApi) {
            JellyfinApi(
                androidContext = context,
                requestTimeout = appPreferences.getValue(appPreferences.requestTimeout),
                connectTimeout = appPreferences.getValue(appPreferences.connectTimeout),
                socketTimeout = appPreferences.getValue(appPreferences.socketTimeout),
            )
        }

    @Singleton
    @Provides
    fun provideSetupRepository(
        jellyfinApi: JellyfinApi,
        apiFactory: JellyfinApiFactory,
        serverDatabase: ServerDatabaseDao,
        appPreferences: AppPreferences,
        identityWriteCoordinator: IdentityWriteCoordinator,
    ): SetupRepository {
        return SetupRepositoryImpl(
            jellyfinApi = jellyfinApi,
            database = serverDatabase,
            appPreferences = appPreferences,
            apiFactory = apiFactory,
            identityConditionalWritePort = identityWriteCoordinator,
        )
    }
}
