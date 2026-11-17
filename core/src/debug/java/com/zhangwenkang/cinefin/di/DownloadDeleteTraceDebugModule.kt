package com.zhangwenkang.cinefin.di

import com.zhangwenkang.cinefin.utils.DownloadDeleteTraceInstaller
import com.zhangwenkang.cinefin.utils.DownloadDeleteTraceOwner
import com.zhangwenkang.cinefin.utils.DownloadDeleteTraceRuntime
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
object DownloadDeleteTraceDebugModule {
    @Provides
    @IntoSet
    fun provideInstaller(owner: DownloadDeleteTraceOwner): DownloadDeleteTraceInstaller =
        DownloadDeleteTraceInstaller {
            DownloadDeleteTraceRuntime.install(owner)
        }
}
