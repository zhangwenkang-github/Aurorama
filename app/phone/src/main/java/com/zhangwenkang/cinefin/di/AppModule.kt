package com.zhangwenkang.cinefin.di

import android.content.Context
import android.content.Intent
import com.zhangwenkang.cinefin.BaseApplication
import com.zhangwenkang.cinefin.presentation.update.AndroidUpdateInstallerGateway
import com.zhangwenkang.cinefin.update.AndroidUpdatePackageInspector
import com.zhangwenkang.cinefin.update.UpdateInstallCoordinator
import com.zhangwenkang.cinefin.update.UpdateStateStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Singleton
    @Provides
    fun provideApplication(@ApplicationContext app: Context): BaseApplication {
        return app as BaseApplication
    }

    @Singleton
    @Provides
    fun provideAndroidUpdatePackageInspector(
        @ApplicationContext context: Context
    ): AndroidUpdatePackageInspector = AndroidUpdatePackageInspector(context)

    @Singleton
    @Provides
    fun provideAndroidUpdateInstallerGateway(
        @ApplicationContext context: Context
    ): AndroidUpdateInstallerGateway = AndroidUpdateInstallerGateway(context)

    @Singleton
    @Provides
    fun provideUpdateInstallCoordinator(
        store: UpdateStateStore,
        inspector: AndroidUpdatePackageInspector,
        gateway: AndroidUpdateInstallerGateway,
    ): UpdateInstallCoordinator<Intent> =
        UpdateInstallCoordinator(
            store = store,
            installedReader = inspector,
            metadataReader = inspector,
            gateway = gateway,
            deviceSdk = { android.os.Build.VERSION.SDK_INT },
        )
}
