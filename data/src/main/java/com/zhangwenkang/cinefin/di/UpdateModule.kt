package com.zhangwenkang.cinefin.di

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.update.GitHubReleaseClient
import com.zhangwenkang.cinefin.update.OkHttpGitHubReleaseFetcher
import com.zhangwenkang.cinefin.update.OkHttpUpdateTransport
import com.zhangwenkang.cinefin.update.UpdateCheckCoordinator
import com.zhangwenkang.cinefin.update.UpdateCheckGateStore
import com.zhangwenkang.cinefin.update.UpdateDownloadExecutor
import com.zhangwenkang.cinefin.update.UpdateHttpDownloader
import com.zhangwenkang.cinefin.update.UpdatePlatformInfo
import com.zhangwenkang.cinefin.update.UpdateReleaseSource
import com.zhangwenkang.cinefin.update.UpdateRepository
import com.zhangwenkang.cinefin.update.UpdateRepositoryImpl
import com.zhangwenkang.cinefin.update.UpdateStateStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class UpdateHttpClient

@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {
    @Singleton
    @Provides
    @UpdateHttpClient
    fun provideUpdateHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

    @Singleton
    @Provides
    fun provideUpdateReleaseSource(@UpdateHttpClient client: OkHttpClient): UpdateReleaseSource {
        val releaseClient = GitHubReleaseClient(OkHttpGitHubReleaseFetcher(client))
        return UpdateReleaseSource { releaseClient.fetchLatestRelease() }
    }

    @Singleton
    @Provides
    fun provideUpdateStateStore(@ApplicationContext context: Context): UpdateStateStore =
        UpdateStateStore(File(context.filesDir, "updates"))

    @Singleton
    @Provides
    fun provideUpdateCheckGate(appPreferences: AppPreferences): UpdateCheckGateStore =
        object : UpdateCheckGateStore {
            override fun lastAutomaticAttemptAtEpochMs(): Long =
                appPreferences.lastAutomaticUpdateCheckAttemptAt()

            override fun lastSuccessfulCheckAtEpochMs(): Long =
                appPreferences.lastSuccessfulUpdateCheckAt()

            override fun recordAutomaticAttempt(epochMs: Long): Boolean =
                appPreferences.recordAutomaticUpdateCheckAttempt(epochMs)

            override fun recordSuccessfulCheck(epochMs: Long): Boolean =
                appPreferences.recordSuccessfulUpdateCheck(epochMs)
        }

    @Singleton
    @Provides
    fun provideUpdatePlatformInfo(@ApplicationContext context: Context): UpdatePlatformInfo =
        object : UpdatePlatformInfo {
            override fun installedVersionName(): String {
                val packageInfo =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.packageManager.getPackageInfo(
                            context.packageName,
                            PackageManager.PackageInfoFlags.of(0L),
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0)
                    }
                return packageInfo.versionName.orEmpty()
            }

            override fun supportedAbis(): List<String> = Build.SUPPORTED_ABIS.toList()
        }

    @Singleton
    @Provides
    fun provideCheckCoordinator(
        source: UpdateReleaseSource,
        store: UpdateStateStore,
        gate: UpdateCheckGateStore,
        platform: UpdatePlatformInfo,
    ): UpdateCheckCoordinator =
        UpdateCheckCoordinator(
            source = source,
            store = store,
            gate = gate,
            platform = platform,
        )

    @Singleton
    @Provides
    fun provideUpdateDownloadExecutor(
        store: UpdateStateStore,
        @UpdateHttpClient client: OkHttpClient,
    ): UpdateDownloadExecutor {
        val downloader = UpdateHttpDownloader(store, OkHttpUpdateTransport(client))
        return UpdateDownloadExecutor { release, onProgress ->
            downloader.download(release, onProgress)
        }
    }

    @Singleton
    @Provides
    fun provideUpdateRepository(
        coordinator: UpdateCheckCoordinator,
        store: UpdateStateStore,
        downloader: UpdateDownloadExecutor,
    ): UpdateRepository =
        UpdateRepositoryImpl(
            coordinator = coordinator,
            store = store,
            downloader = downloader,
        )
}
