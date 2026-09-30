package com.zhangwenkang.cinefin.book.presentation.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 阅读模式 DI 骨架（W0，2026-09-30 由项目负责人预建）。
 *
 * W1（R1-SKELETON）在此模块内实现书架 / 阅读页；本文件是 DI 扩展点， 后续由 R1 追加 Repository 与阅读引擎绑定，不新增独立 DI 文件。
 */
@Module @InstallIn(SingletonComponent::class) object BookModule
