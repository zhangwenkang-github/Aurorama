package com.zhangwenkang.cinefin.music.presentation.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 音乐模式 DI 骨架（W0，2026-09-30 由项目负责人预建）。
 *
 * W1（R2-SKELETON）在此模块内实现曲库浏览 / 正在播放 / 队列 / 歌词； 本文件是 DI 扩展点，后续由 R2 追加绑定，不新增独立 DI 文件。
 */
@Module @InstallIn(SingletonComponent::class) object MusicModule
