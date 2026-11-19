package com.zhangwenkang.cinefin.player.local.syncplay

import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder
import com.zhangwenkang.cinefin.syncplay.SyncPlayGateway
import com.zhangwenkang.cinefin.syncplay.SyncPlayGatewayImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * SyncPlay 装配绑定（W4-T8d）。
 *
 * T8a 只交付了 `SyncPlayGatewayImpl` 的实现（构造函数吃 `JellyfinApi`，**没有** `@Inject`），Hilt 绑定按交接留到本卡：这里用
 * `@Provides` 组装网关与播放口宿主，data 模块零改动。协调器（[SyncPlaySessionCoordinator]）随构造图注入。
 */
@Module
@InstallIn(SingletonComponent::class)
internal object SyncPlayModule {
    @Singleton
    @Provides
    fun provideSyncPlayGateway(api: JellyfinApi): SyncPlayGateway = SyncPlayGatewayImpl(api)

    @Singleton
    @Provides
    fun provideSyncPlayPlayerHost(playerHolder: PlayerHolder): SyncPlayPlayerHost =
        PlayerHolderSyncPlayHost(playerHolder)
}
