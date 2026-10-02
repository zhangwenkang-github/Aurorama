package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.player.core.domain.models.TrickplayInfo

/**
 * Trickplay 精灵图索引换算（W20 · §1.11 播放增强）。
 *
 * Jellyfin 的 Trickplay 是「一张大图 = tileWidth × tileHeight 张缩略图」的精灵图（sheet）： 第 `index` 张缩略图落在第 `index /
 * (tileWidth * tileHeight)` 张图里。 之前客户端在换集时一次性把**整部片**的图全拉下来解码（一集几百张），
 * 拖动进度条之前就把网络与内存吃满；这里把换算抽成纯函数，播放页只按需拉「当前这张图」。
 *
 * 纯 Kotlin（不碰 Android 类型）→ 单测直接覆盖边界（末张不满、超出总张数、负数）。
 */
object TrickplayTiles {

    /** 一张精灵图能放多少张缩略图 */
    fun sheetSize(info: TrickplayInfo): Int =
        (info.tileWidth.coerceAtLeast(1) * info.tileHeight.coerceAtLeast(1)).coerceAtLeast(1)

    /** 服务器标注的总缩略图数（拿不到时按 0 = 没有 trickplay） */
    fun tileCount(info: TrickplayInfo): Int = info.thumbnailCount.coerceAtLeast(0)

    /** 精灵图总张数（最后一张可能不满） */
    fun sheetCount(info: TrickplayInfo): Int {
        val count = tileCount(info)
        if (count == 0) return 0
        return (count + sheetSize(info) - 1) / sheetSize(info)
    }

    /** 第 [index] 张缩略图所在的精灵图序号（收敛到合法区间） */
    fun sheetOf(index: Int, info: TrickplayInfo): Int {
        val last = (sheetCount(info) - 1).coerceAtLeast(0)
        return (clampIndex(index, info) / sheetSize(info)).coerceIn(0, last)
    }

    /** 第 [sheet] 张图里第一张缩略图的全局序号 */
    fun firstTileOfSheet(sheet: Int, info: TrickplayInfo): Int =
        sheet.coerceAtLeast(0) * sheetSize(info)

    /** 第 [sheet] 张图里最后一张缩略图的全局序号（含；已收敛到总张数内） */
    fun lastTileOfSheet(sheet: Int, info: TrickplayInfo): Int {
        val last = tileCount(info) - 1
        if (last < 0) return 0
        return (firstTileOfSheet(sheet, info) + sheetSize(info) - 1).coerceAtMost(last)
    }

    /** 把缩略图序号收敛到 [0, tileCount) */
    fun clampIndex(index: Int, info: TrickplayInfo): Int {
        val last = tileCount(info) - 1
        if (last < 0) return 0
        return index.coerceIn(0, last)
    }

    /** 播放位置（毫秒）对应的缩略图序号；interval 未知 / 非法时按 0 处理 */
    fun tileIndexAt(positionMs: Long, info: TrickplayInfo): Int {
        val interval = info.interval.coerceAtLeast(1)
        val raw = (positionMs.coerceAtLeast(0L) / interval).toInt()
        return clampIndex(raw, info)
    }
}

/**
 * 精灵图 LRU 缓存（W20）。
 *
 * 只保留最近用到的几张图（默认 2 张 = 拖动时前后各一张）， 内存占用与片长无关；泛型是为了单测能脱离 `Bitmap` 直接验淘汰顺序。
 */
class TrickplaySheetCache<T>(private val capacity: Int = 2) {
    private val entries =
        object : LinkedHashMap<Int, List<T>>(8, 0.75f, /* accessOrder= */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<T>>): Boolean =
                size > capacity.coerceAtLeast(1)
        }

    fun get(sheet: Int): List<T>? = entries[sheet]

    fun put(sheet: Int, tiles: List<T>) {
        entries[sheet] = tiles
    }

    fun clear() = entries.clear()

    val size: Int
        get() = entries.size

    fun contains(sheet: Int): Boolean = entries.containsKey(sheet)
}

/**
 * 精灵图请求状态机（W20，纯逻辑）。
 *
 * 保证同一张图同时只发一次请求；**失败的图不再重试**——拖动进度条时 每移动一像素都会命中同一个序号，不设失败标记会变成对服务的重试风暴。
 */
class TrickplayRequestState {
    private val inFlight = mutableSetOf<Int>()
    private val loaded = mutableSetOf<Int>()
    private val failed = mutableSetOf<Int>()

    fun canRequest(sheet: Int): Boolean = sheet !in inFlight && sheet !in loaded && sheet !in failed

    fun markInFlight(sheet: Int) {
        inFlight += sheet
    }

    fun markLoaded(sheet: Int) {
        inFlight -= sheet
        loaded += sheet
    }

    fun markFailed(sheet: Int) {
        inFlight -= sheet
        failed += sheet
    }

    fun isInFlight(sheet: Int): Boolean = sheet in inFlight

    fun isLoaded(sheet: Int): Boolean = sheet in loaded

    fun isFailed(sheet: Int): Boolean = sheet in failed
}
