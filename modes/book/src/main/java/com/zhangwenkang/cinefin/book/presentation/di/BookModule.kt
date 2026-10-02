package com.zhangwenkang.cinefin.book.presentation.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 阅读模式 DI 骨架（W0，2026-09-30 由项目负责人预建）。
 *
 * W1（R1-SKELETON）在此模块内实现书架 / 阅读页；阅读仓储绑定在 W34 上移到
 * `data/repository/ReaderRepositoryModule.kt`（下载页宿主不一定依赖本模块）。
 */
@Module @InstallIn(SingletonComponent::class) object BookModule
