package com.zhangwenkang.cinefin.di

import android.content.Context
import com.zhangwenkang.cinefin.repository.BookDownloadNotificationSink
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.BookDownloadNotifications
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * W9-T7（F5）：书籍下载通知绑定。
 *
 * data 模块只声明 [BookDownloadNotificationSink]，实现在 core 复用既有下载通知；data 的 `ReaderRepositoryModule`
 * 注入该绑定交给 `BookDownloadManager`。
 */
@Module
@InstallIn(SingletonComponent::class)
object BookDownloadNotificationModule {
    @Singleton
    @Provides
    fun provideBookDownloadNotificationSink(
        @ApplicationContext context: Context,
        appPreferences: AppPreferences,
    ): BookDownloadNotificationSink = BookDownloadNotifications(context, appPreferences)
}
