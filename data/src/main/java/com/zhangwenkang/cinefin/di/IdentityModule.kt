package com.zhangwenkang.cinefin.di

import com.zhangwenkang.cinefin.identity.IdentityReadPort
import com.zhangwenkang.cinefin.identity.IdentityWriteCoordinator
import com.zhangwenkang.cinefin.identity.IdentityWritePort
import com.zhangwenkang.cinefin.settings.domain.OfflineModeWritePort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class IdentityModule {
    @Binds
    @Singleton
    abstract fun bindIdentityReadPort(impl: IdentityWriteCoordinator): IdentityReadPort

    @Binds
    @Singleton
    abstract fun bindIdentityWritePort(impl: IdentityWriteCoordinator): IdentityWritePort

    @Binds
    @Singleton
    abstract fun bindOfflineModeWritePort(impl: IdentityWriteCoordinator): OfflineModeWritePort
}
