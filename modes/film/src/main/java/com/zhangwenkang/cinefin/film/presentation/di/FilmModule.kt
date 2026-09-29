package com.zhangwenkang.cinefin.film.presentation.di

import com.zhangwenkang.cinefin.film.domain.VideoMetadataParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FilmModule {
    @Singleton
    @Provides
    fun provideVideoMetadataParser(): VideoMetadataParser {
        return VideoMetadataParser
    }
}
