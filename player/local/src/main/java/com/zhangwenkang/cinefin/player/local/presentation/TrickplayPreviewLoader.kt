package com.zhangwenkang.cinefin.player.local.presentation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.zhangwenkang.cinefin.player.core.domain.models.TrickplayInfo
import com.zhangwenkang.cinefin.player.local.domain.TrickplayRequestState
import com.zhangwenkang.cinefin.player.local.domain.TrickplaySheetCache
import com.zhangwenkang.cinefin.player.local.domain.TrickplayTiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Trickplay 预览的按需加载器（W20 · §1.11 播放增强）。
 *
 * 旧实现（`PlayerViewModel.getTrickplay`）在**换集时**把整部片的精灵图全部下载并解码成 `List<Bitmap>`
 * 常驻内存：一集通常几十到上百张图，网速慢时白拉流量，内存也随片长增长。
 *
 * 现在的策略：
 * 1. 只按需拉「当前拖动位置所在的那张精灵图」（一张图 = tileWidth × tileHeight 张缩略图）；
 * 2. 最近 2 张图做 LRU（拖动时前后各一张够用），内存与片长解耦；
 * 3. 同一张图同时只发一个请求，拉取失败的图**不重试**（拖动时每像素都会命中同一序号， 不设标记会变成重试风暴）——失败时 [frameAt] 继续返回 null，UI
 *    自然退化成"没有预览图"， 拖动本身不受影响；
 * 4. 全部 IO / 解码在后台线程，主线程只做查缓存与状态收敛，保证拖动跟手。
 *
 * 线程约定：缓存与请求状态的读写都在主线程（[frameAt] 由 UI 线程调用， 后台结果先切回主线程再落缓存）。
 */
internal class TrickplayPreviewLoader(
    private val info: TrickplayInfo,
    private val scope: CoroutineScope,
    private val fetchTileSheet: suspend (sheet: Int) -> ByteArray?,
    private val onSheetReady: () -> Unit,
) {
    private val cache = TrickplaySheetCache<Bitmap>(capacity = 2)
    private val requests = TrickplayRequestState()

    /** 服务器有没有可用的 trickplay（没有 = 预览直接降级，不发起任何请求） */
    val available: Boolean
        get() = info.interval > 0 && TrickplayTiles.tileCount(info) > 0

    val intervalMs: Int
        get() = info.interval.coerceAtLeast(1)

    /**
     * 取 [positionMs] 处的预览图。
     *
     * 未命中时当场返回 null 并触发后台拉取；拉取完成后由 [onSheetReady] 通知 uiState 刷新（`trickplayVersion` +1），UI
     * 下一次重组就能拿到图。
     */
    fun frameAt(positionMs: Long): Bitmap? {
        if (!available) return null
        val index = TrickplayTiles.tileIndexAt(positionMs, info)
        val sheet = TrickplayTiles.sheetOf(index, info)
        val tiles = cache.get(sheet)
        request(sheet)
        if (tiles == null) return null
        val offset = index - TrickplayTiles.firstTileOfSheet(sheet, info)
        return tiles.getOrNull(offset)
    }

    private fun request(sheet: Int) {
        if (!requests.canRequest(sheet)) return
        requests.markInFlight(sheet)
        scope.launch(Dispatchers.IO) {
            val tiles =
                try {
                    val bytes = fetchTileSheet(sheet)
                    if (bytes == null) emptyList() else decodeSheet(bytes, sheet)
                } catch (e: Exception) {
                    Timber.d(e, "Trickplay 精灵图 %d 拉取/解码失败", sheet)
                    emptyList()
                }
            withContext(Dispatchers.Main) {
                if (tiles.isEmpty()) {
                    requests.markFailed(sheet)
                    Timber.d("Trickplay 预览降级：精灵图 %d 不可用（本次播放不再重试）", sheet)
                } else {
                    cache.put(sheet, tiles)
                    requests.markLoaded(sheet)
                    Timber.d("Trickplay 精灵图就绪：sheet=%d tiles=%d", sheet, tiles.size)
                    onSheetReady()
                }
            }
        }
    }

    /** 把一张精灵图裁成缩略图列表；最后一张可能不满，按实际边界收敛（越界即停，不抛异常） */
    private fun decodeSheet(bytes: ByteArray, sheet: Int): List<Bitmap> {
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return emptyList()
        val tileWidth = info.width.coerceAtLeast(1)
        val tileHeight = info.height.coerceAtLeast(1)
        val columns = info.tileWidth.coerceAtLeast(1)
        val first = TrickplayTiles.firstTileOfSheet(sheet, info)
        val last = TrickplayTiles.lastTileOfSheet(sheet, info)
        val tiles = ArrayList<Bitmap>(last - first + 1)
        for (index in first..last) {
            val offset = index - first
            val x = (offset % columns) * tileWidth
            val y = (offset / columns) * tileHeight
            if (x + tileWidth > full.width || y + tileHeight > full.height) break
            tiles += Bitmap.createBitmap(full, x, y, tileWidth, tileHeight)
        }
        return tiles
    }
}
