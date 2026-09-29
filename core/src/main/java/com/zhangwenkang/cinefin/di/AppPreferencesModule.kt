package com.zhangwenkang.cinefin.di

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppPreferencesModule {
    @Singleton
    @Provides
    fun provideAppPreferences(sp: SharedPreferences): AppPreferences {
        return AppPreferences(sp)
    }
}
