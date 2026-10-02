package com.zhangwenkang.cinefin.repository

import android.content.Context
import com.zhangwenkang.cinefin.api.JellyfinApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * W34：`ReaderRepository` 绑定从 `modes:book` 上移到 data 层。
 *
 * 下载页（`modes:film`）要读阅读器的离线书籍列表；绑定留在 book 模块会让只依赖 `modes:film` 的宿主（如 TV 壳）缺绑定，因此把「数据仓储」的绑定放到它自己的模块。
 */
@Module
@InstallIn(SingletonComponent::class)
object ReaderRepositoryModule {
    @Provides
    @Singleton
    fun provideReaderRepository(
        @ApplicationContext context: Context,
        jellyfinApi: JellyfinApi,
    ): ReaderRepository = ReaderRepositoryImpl(context, jellyfinApi)
}
