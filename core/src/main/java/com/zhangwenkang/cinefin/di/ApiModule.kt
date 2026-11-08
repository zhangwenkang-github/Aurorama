package com.zhangwenkang.cinefin.di

import android.content.Context
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.identity.IdentityReadPort
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {
    @Singleton
    @Provides
    fun provideJellyfinApi(
        @ApplicationContext application: Context,
        appPreferences: AppPreferences,
    ): JellyfinApi =
        JellyfinApi.getInstance(
            context = application,
            requestTimeout = appPreferences.getValue(appPreferences.requestTimeout),
            connectTimeout = appPreferences.getValue(appPreferences.connectTimeout),
            socketTimeout = appPreferences.getValue(appPreferences.socketTimeout),
        )
}

/** The process-global API never receives identity from legacy preference or Room inference. */
@Singleton
class IdentityHydrationGate @Inject constructor(private val identityReadPort: IdentityReadPort) {
    suspend fun hydrate() = identityReadPort.hydrate().let(::canonicalOnly)

    suspend fun current() = identityReadPort.current().let(::canonicalOnly)

    private fun canonicalOnly(snapshot: com.zhangwenkang.cinefin.identity.IdentitySnapshot) =
        if (
            snapshot.status.name == "READY" &&
                (snapshot.selection.serverId == null ||
                    (snapshot.selection.userId != null && snapshot.selection.addressId != null))
        ) {
            snapshot
        } else {
            snapshot.copy(
                selection =
                    snapshot.selection.copy(
                        serverId = null,
                        userId = null,
                        addressId = null,
                        offlineMode = false,
                    ),
                status = com.zhangwenkang.cinefin.identity.IdentityStateStatus.RECOVERING,
            )
        }
}
