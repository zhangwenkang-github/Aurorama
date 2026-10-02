package com.zhangwenkang.cinefin.local

import android.content.Context
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** W37：本地媒体库仓库绑定（与 `ReaderRepository` 同口径放在 data 层）。 */
@Module
@InstallIn(SingletonComponent::class)
object LocalLibraryModule {
    @Provides
    @Singleton
    fun provideLocalLibraryRepository(
        @ApplicationContext context: Context,
        serverDatabase: ServerDatabaseDao,
    ): LocalLibraryRepository = LocalLibraryRepositoryImpl(context, serverDatabase)
}
