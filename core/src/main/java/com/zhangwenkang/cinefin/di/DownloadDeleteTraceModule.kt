package com.zhangwenkang.cinefin.di

import com.zhangwenkang.cinefin.utils.DownloadDeleteTraceInstaller
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadDeleteTraceModule {
    @Multibinds abstract fun installers(): Set<DownloadDeleteTraceInstaller>
}
