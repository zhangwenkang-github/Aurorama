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
                // 偏好 0（默认）= 不设上限：不调用 maxSizeBytes，由 Coil 按可用空间自行管理。
                ImageCacheSize.maxDiskCacheBytes(
                        appPreferences.getValue(appPreferences.imageCacheSize)
                    )
                    ?.let { builder.maxSizeBytes(it) }
                builder.build()
            }
            .crossfade(true)
            .build()
    }
}
