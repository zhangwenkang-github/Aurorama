package com.zhangwenkang.cinefin.di

import android.app.Application
import androidx.work.WorkManager
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.network.SharedPreferencesCertificateTrustStore
import com.zhangwenkang.cinefin.network.buildCertificateAwareOkHttpClient
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.DownloadRedirectTokenInterceptor
import com.zhangwenkang.cinefin.utils.Downloader
import com.zhangwenkang.cinefin.utils.DownloaderImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object DownloaderModule {
    /**
     * W50 自研下载引擎的 OkHttp 客户端。
     *
     * 与 API 客户端共用「默认校验 + 用户确认指纹」的证书策略（自签证书服务器可下载）；读取超时按「两次读之间」 计算，长时间无数据视为网络故障，交由引擎从残片继续。
     */
    @Singleton
    @Provides
    fun provideDownloadHttpClient(application: Application): OkHttpClient =
        buildCertificateAwareOkHttpClient(
            OkHttpClient.Builder()
                .addInterceptor(DownloadRedirectTokenInterceptor())
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build(),
            SharedPreferencesCertificateTrustStore(application),
        )

    @Singleton
    @Provides
    fun provideDownloader(
        application: Application,
        serverDatabase: ServerDatabaseDao,
        jellyfinRepository: JellyfinRepository,
        appPreferences: AppPreferences,
        workManager: WorkManager,
        downloadHttpClient: OkHttpClient,
    ): Downloader {
        return DownloaderImpl(
            application,
            serverDatabase,
            jellyfinRepository,
            appPreferences,
            workManager,
            downloadHttpClient,
        )
    }
}
