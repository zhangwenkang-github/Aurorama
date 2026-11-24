package com.zhangwenkang.cinefin

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.ImageCacheSize
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlin.time.ExperimentalTime
import okio.Path.Companion.toOkioPath

@HiltAndroidApp
class BaseApplication : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var appPreferences: AppPreferences

    @OptIn(ExperimentalCoilApi::class, ExperimentalTime::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(OkHttpNetworkFetcherFactory(cacheStrategy = { CacheControlCacheStrategy() }))
                add(SvgDecoder.Factory())
            }
            .diskCachePolicy(
                if (appPreferences.getValue(appPreferences.imageCache)) CachePolicy.ENABLED
                else CachePolicy.DISABLED
            )
            .diskCache {
                val builder =
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                val maxSizeBytes =
                    ImageCacheSize.maxDiskCacheBytes(
                        appPreferences.getValue(appPreferences.imageCacheSize)
                    )
                if (maxSizeBytes != null) {
                    // 显式上限：Coil 走 maxSizeBytes 分支，不受 maximumMaxSizeBytes 钳制。
                    builder.maxSizeBytes(maxSizeBytes)
                } else {
                    // 0（默认）= 无固定上限：保留 Coil「可用空间 × 2%（10MB 保底）」，解除 250MB 默认硬顶。
                    builder.maximumMaxSizeBytes(Long.MAX_VALUE)
                }
                builder.build()
            }
            .crossfade(true)
            .build()
    }
}
