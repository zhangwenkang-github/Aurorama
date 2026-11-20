package com.zhangwenkang.cinefin.music.presentation

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.bitmapConfig
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.MediaColors
import com.zhangwenkang.cinefin.core.presentation.theme.mediaColorsFromCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MUSIC-17 封面调色板（W5-T2 定稿一 §3）。
 *
 * 提取链：Coil 按 itemId 所在曲目的封面 URI 取 **≤96px** 边长的 ARGB_8888 位图（`Palette` 只能读软件位图）→
 * `Palette.from().generate()` → 三个取样色（vibrant / muted / dominant）交给 core 的 [mediaColorsFromCover]
 * 做优先级、纯色回落与 3:1 护栏。
 *
 * 缓存：按 itemId 进程内 [LruCache]（32 条，不落盘；提取失败也缓存「空取样」，避免对坏封面反复解码）。换曲时缓存命中则同步出色， 未命中先回落域色、提取完成后自动换色。
 */
internal object MusicCoverPalette {
    /** 提取边长：调色板采样足够，且远小于封面原图。 */
    const val SampleSize = 96

    /** 进程内缓存条数（定稿 §3）。 */
    const val CacheSize = 32

    private val swatchCache = LruCache<String, CoverSwatches>(CacheSize)

    /** 当前曲目的封面派生媒体色；提取中 / 无封面 / 纯色封面 / 提取失败时返回 null，调用方回落 [LocalMediaColors] 域色。 */
    @Composable
    fun mediaColors(itemId: String?, imageUri: String?): MediaColors? {
        val colors = LocalCinefinColors.current
        val context = LocalPlatformContext.current
        var swatches by remember(itemId) { mutableStateOf(itemId?.let(swatchCache::get)) }
        LaunchedEffect(itemId, imageUri) {
            val id = itemId ?: return@LaunchedEffect
            val cached = swatchCache.get(id)
            if (cached != null) {
                swatches = cached
                return@LaunchedEffect
            }
            val extracted = withContext(Dispatchers.IO) { extract(context, imageUri) }
            swatchCache.put(id, extracted)
            swatches = extracted
        }
        return remember(swatches, colors) {
            swatches?.let { entry ->
                mediaColorsFromCover(
                    vibrant = entry.vibrant?.let(::Color),
                    muted = entry.muted?.let(::Color),
                    dominant = entry.dominant?.let(::Color),
                    colors = colors,
                )
            }
        }
    }

    private suspend fun extract(context: Context, imageUri: String?): CoverSwatches {
        if (imageUri.isNullOrBlank()) return CoverSwatches()
        val request =
            ImageRequest.Builder(context)
                .data(imageUri)
                .size(SampleSize)
                // Palette 需要可读像素：硬件位图读不了，强制软件位图。
                .bitmapConfig(Bitmap.Config.ARGB_8888)
                .build()
        val bitmap =
            runCatching { SingletonImageLoader.get(context).execute(request) }
                .getOrNull()
                ?.image
                ?.let { image -> (image as? BitmapImage)?.bitmap } ?: return CoverSwatches()
        val palette =
            runCatching { Palette.from(bitmap).generate() }.getOrNull() ?: return CoverSwatches()
        return CoverSwatches(
            vibrant = palette.vibrantSwatch?.rgb,
            muted = palette.mutedSwatch?.rgb,
            dominant = palette.dominantSwatch?.rgb,
        )
    }
}

/** `Palette` 三个取样色（ARGB）；全空 = 无封面 / 提取失败（同样进缓存，避免反复解码）。 */
internal data class CoverSwatches(
    val vibrant: Int? = null,
    val muted: Int? = null,
    val dominant: Int? = null,
)
