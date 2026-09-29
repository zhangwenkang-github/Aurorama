package com.zhangwenkang.cinefin.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.zhangwenkang.cinefin.database.MIGRATION_6_7
import com.zhangwenkang.cinefin.database.ServerDatabase
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Singleton
    @Provides
    fun provideServerDatabaseDao(@ApplicationContext app: Context): ServerDatabaseDao {
        return Room.databaseBuilder(app.applicationContext, ServerDatabase::class.java, "servers")
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(MIGRATION_6_7)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
            .getServerDatabaseDao()
    }
}
