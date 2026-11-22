package com.zhangwenkang.cinefin.di

import android.app.Application
import androidx.work.WorkManager
import com.zhangwenkang.cinefin.repository.UserDataSyncScheduler
import com.zhangwenkang.cinefin.work.WorkManagerUserDataSyncScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WorkManagerModule {
    @Singleton
    @Provides
    fun provideWorkManager(application: Application): WorkManager {
        return WorkManager.getInstance(application)
    }

    /** W8-T1r：排队写补同步（data 层的调度抽象，WorkManager 实现留在 core）。 */
    @Singleton
    @Provides
    fun provideUserDataSyncScheduler(workManager: WorkManager): UserDataSyncScheduler =
        WorkManagerUserDataSyncScheduler(workManager)
}
