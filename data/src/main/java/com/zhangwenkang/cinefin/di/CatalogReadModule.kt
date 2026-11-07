package com.zhangwenkang.cinefin.di

import com.zhangwenkang.cinefin.repository.JellyfinRepositoryImpl
import com.zhangwenkang.cinefin.repository.JellyfinRepositoryOfflineImpl
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadPort
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object CatalogReadModule {
    @Provides
    fun provideCatalogReadPort(
        online: JellyfinRepositoryImpl,
        offline: JellyfinRepositoryOfflineImpl,
        appPreferences: AppPreferences,
    ): CatalogReadPort =
        selectCatalogReadPort(
            offlineMode = appPreferences.getValue(appPreferences.offlineMode),
            online = online,
            offline = offline,
        )

    internal fun selectCatalogReadPort(
        offlineMode: Boolean,
        online: CatalogReadPort,
        offline: CatalogReadPort,
    ): CatalogReadPort = if (offlineMode) offline else online
}
