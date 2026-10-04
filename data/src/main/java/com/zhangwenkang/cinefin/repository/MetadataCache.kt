package com.zhangwenkang.cinefin.repository

import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter

/**
 * W69：元数据缓存规则（纯函数 + 常量集中定义，可单测）。
 *
 * 口径（任务书 §三.4 stale-while-revalidate）：
 * - **TTL 内**：直接复用缓存，不发请求；
 * - **TTL 外**：页面先渲染自己保留的上一次内容，请求回来后**就地替换**（静默刷新）；
 * - **下拉刷新**：强制刷新（[JellyfinRepository.invalidateMetadataCache] 先失效缓存，再照常读取）。
 */
object MetadataCacheRules {
    /** 列表 / 详情元数据默认 TTL：10 分钟（任务书建议 5–15 分钟，取中值）。 */
    const val DEFAULT_TTL_MS: Long = 10 * 60 * 1000L

    /** 分页页片 TTL：5 分钟——分页内容最"活"，过期后应更快看到新数据。 */
    const val PAGING_TTL_MS: Long = 5 * 60 * 1000L

    /** 会话缓存条数上限（LRU 淘汰；进程内存，不做持久化）。 */
    const val DEFAULT_MAX_ENTRIES: Int = 256

    /** 缓存是否仍在 TTL 内（可直接复用、不发请求）。时钟回拨按"过期"处理（宁可多拉一次）。 */
    fun isFresh(
        fetchedAtMs: Long,
        nowMs: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): Boolean = nowMs >= fetchedAtMs && nowMs - fetchedAtMs < ttlMs

    /** 页面级决策：已经上屏过一份内容（[loadedAtMs] > 0）且已过 TTL —— 应该**保留现有画面**， 在后台静默重取（而不是清空 / 重建，导致骨架盖住海报）。 */
    fun shouldSilentlyRevalidate(
        loadedAtMs: Long,
        nowMs: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): Boolean = loadedAtMs > 0L && !isFresh(loadedAtMs, nowMs, ttlMs)
}

/** 缓存条目：值 + 抓取时间戳（epoch ms）。 */
data class MetadataCacheEntry<T>(val value: T, val fetchedAtMs: Long)

/**
 * W69：会话级元数据缓存（进程内存）。
 *
 * 目标 = 「进入页面先用本地已缓存的元数据渲染，服务器刷新静默」：仓库读方法在 TTL 内命中缓存直接返回， 不再打服务器；页面因此不再进入加载态、不会把已上屏的海报清空。缓存按
 * [namespace] 隔离 （服务器地址 + 用户），切换服务器 / 账号自然不串数据；[invalidate] / [invalidateAll] 供用户动作 （收藏 / 已看 /
 * 播放结束）与下拉刷新强制失效。
 *
 * 线程安全：读写都走单调 LRU 表（LinkedHashMap accessOrder），加锁保护。
 */
class MetadataCache(
    private val namespace: () -> String = { "" },
    private val maxEntries: Int = MetadataCacheRules.DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries =
        object : LinkedHashMap<String, MetadataCacheEntry<Any?>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, MetadataCacheEntry<Any?>>
            ): Boolean = size > maxEntries
        }

    /** 当前时间（与缓存写入同一时钟，便于单测）。 */
    fun now(): Long = clock()

    private fun scoped(key: String): String = "${namespace()}|$key"

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): MetadataCacheEntry<T>? =
        synchronized(entries) { entries[scoped(key)] as? MetadataCacheEntry<T> }

    fun <T> put(key: String, value: T) {
        synchronized(entries) { entries[scoped(key)] = MetadataCacheEntry(value, clock()) }
    }

    /** 失效某一组（键前缀）缓存，例如 `favorites:` / `resume:`。 */
    fun invalidate(prefix: String) {
        val scopedPrefix = "${namespace()}|$prefix"
        synchronized(entries) { entries.keys.removeAll { it.startsWith(scopedPrefix) } }
    }

    /** 全部失效（收藏 / 已看 / 播放结束等跨列表的用户动作）。 */
    fun invalidateAll() {
        synchronized(entries) { entries.clear() }
    }

    /** 当前条数（单测 / 诊断用）。 */
    fun size(): Int = synchronized(entries) { entries.size }
}

/**
 * W69：元数据缓存键（与 [JellyfinRepositoryImpl] 的读方法一一对应）。
 *
 * 前缀常量同时用于分组失效；参数按「类型 + 序 + 过滤 + 分页窗口」拼签名， 保证同一库 / 同一排序 / 同一页的重复读取命中同一条目。
 */
object MetadataCacheKeys {
    const val VIEWS = "views"
    const val SUGGESTIONS = "suggestions"

    const val LATEST_PREFIX = "latest:"
    const val RESUME_PREFIX = "resume:"
    const val NEXT_UP_PREFIX = "nextup:"
    const val FAVORITES_PREFIX = "favorites:"
    const val SEARCH_PREFIX = "search:"
    const val ITEM_PREFIX = "item:"
    const val SHOW_PREFIX = "show:"
    const val SEASON_PREFIX = "season:"
    const val MOVIE_PREFIX = "movie:"
    const val EPISODE_PREFIX = "episode:"
    const val SEASONS_PREFIX = "seasons:"
    const val EPISODES_PREFIX = "episodes:"
    const val ITEMS_PREFIX = "items:"
    const val COUNT_PREFIX = "count:"
    const val LIBRARY_SUGGESTIONS_PREFIX = "library-suggestions:"
    const val UPCOMING_PREFIX = "upcoming:"
    const val GENRES_PREFIX = "genres:"
    const val STUDIOS_PREFIX = "studios:"
    const val PERSON_ITEMS_PREFIX = "person-items:"
    const val LIBRARIES_PREFIX = "libraries:"

    private const val ALL = "all"

    fun latestMedia(parentId: UUID): String = "$LATEST_PREFIX$parentId"

    fun resume(includeItemTypes: List<BaseItemKind>): String =
        "$RESUME_PREFIX${signature(includeItemTypes)}"

    fun nextUp(seriesId: UUID?): String = "$NEXT_UP_PREFIX${seriesId ?: ALL}"

    fun item(itemId: UUID): String = "$ITEM_PREFIX$itemId"

    fun show(showId: UUID): String = "$SHOW_PREFIX$showId"

    fun season(seasonId: UUID): String = "$SEASON_PREFIX$seasonId"

    fun movie(movieId: UUID): String = "$MOVIE_PREFIX$movieId"

    fun episode(episodeId: UUID): String = "$EPISODE_PREFIX$episodeId"

    fun seasons(seriesId: UUID, offline: Boolean): String =
        "$SEASONS_PREFIX$seriesId|offline=$offline"

    fun episodes(
        seriesId: UUID,
        seasonId: UUID,
        fields: List<*>?,
        startItemId: UUID?,
        limit: Int?,
        offline: Boolean,
    ): String =
        "$EPISODES_PREFIX$seriesId|$seasonId|${signature(fields)}|start=$startItemId|" +
            "limit=$limit|offline=$offline"

    fun items(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: Any?,
        sortOrder: Any?,
        startIndex: Int?,
        limit: Int?,
        filters: List<ItemFilter>?,
        genres: List<String>?,
        studios: List<String>?,
    ): String =
        "$ITEMS_PREFIX${parentId ?: "-"}|${signature(includeTypes)}|rec=$recursive|" +
            "${sortBy ?: "-"}|${sortOrder ?: "-"}|[${startIndex ?: "-"},${limit ?: "-"}]|" +
            "${signature(filters)}|${signature(genres)}|${signature(studios)}"

    fun itemCount(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        filters: List<ItemFilter>?,
        genres: List<String>?,
        studios: List<String>?,
    ): String =
        "$COUNT_PREFIX${parentId ?: "-"}|${signature(includeTypes)}|rec=$recursive|" +
            "${signature(filters)}|${signature(genres)}|${signature(studios)}"

    fun librarySuggestions(
        parentId: UUID,
        includeTypes: List<BaseItemKind>?,
        limit: Int,
    ): String = "$LIBRARY_SUGGESTIONS_PREFIX$parentId|${signature(includeTypes)}|$limit"

    fun upcoming(parentId: UUID, limit: Int): String = "$UPCOMING_PREFIX$parentId|$limit"

    fun genres(parentId: UUID, includeItemTypes: List<BaseItemKind>?): String =
        "$GENRES_PREFIX$parentId|${signature(includeItemTypes)}"

    fun studios(parentId: UUID, includeItemTypes: List<BaseItemKind>?): String =
        "$STUDIOS_PREFIX$parentId|${signature(includeItemTypes)}"

    fun personItems(personIds: List<UUID>, includeTypes: List<BaseItemKind>?): String =
        "$PERSON_ITEMS_PREFIX${signature(personIds)}|${signature(includeTypes)}"

    const val LIBRARIES = "${LIBRARIES_PREFIX}all"

    fun favorites(sortBy: Any?, sortOrder: Any?): String =
        "$FAVORITES_PREFIX${sortBy ?: "-"}|${sortOrder ?: "-"}"

    fun search(query: String): String = "$SEARCH_PREFIX${query.trim().lowercase()}"

    private fun signature(parts: List<*>?): String =
        parts?.joinToString(",") { it?.toString() ?: "-" } ?: "-"
}
