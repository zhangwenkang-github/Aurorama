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

    /** W88-C：搜索结果首屏的软刷新阈值；离线快照保留上限由 snapshot policy 管理。 */
    const val SEARCH_TTL_MS: Long = 10 * 60 * 1000L

    /** 分页页片 TTL：5 分钟——分页内容最"活"，过期后应更快看到新数据。 */
    const val PAGING_TTL_MS: Long = 5 * 60 * 1000L

    /** 会话缓存条数上限（LRU 淘汰；进程内存，不做持久化）。 */
    const val DEFAULT_MAX_ENTRIES: Int = 256

    /**
     * W69b：同一 key 的**最小刷新冷却**（30 s，任务书建议 30–60 s）。
     *
     * 冷却期内即使缓存已过期，也先把旧值发回调用方、不重复打服务器——覆盖「返回页面 / 重进页面反复触发刷新」 与失败重试风暴；下拉刷新走
     * [JellyfinRepository.invalidateMetadataCache] 清缓存，不受冷却限制。
     */
    const val MIN_REFRESH_COOLDOWN_MS: Long = 30 * 1000L

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

    /** W69b：距上次刷新是否已超过冷却窗口（可再次发起请求）。从未刷新过 = true。 */
    fun canRefresh(
        lastRefreshAtMs: Long,
        nowMs: Long,
        cooldownMs: Long = MIN_REFRESH_COOLDOWN_MS,
    ): Boolean =
        lastRefreshAtMs <= 0L || nowMs < lastRefreshAtMs || nowMs - lastRefreshAtMs >= cooldownMs
}

/** 缓存条目：值 + 抓取时间戳（epoch ms）。 */
data class MetadataCacheEntry<T>(val value: T, val fetchedAtMs: Long)

/**
 * W69：会话级元数据缓存（进程内存）。
 *
 * 目标 = 「进入页面先用本地已缓存的元数据渲染，服务器刷新静默」：仓库读方法在 TTL 内命中缓存直接返回， 不再打服务器；页面因此不再进入加载态、不会把已上屏的海报清空。缓存按
 * [namespace] 隔离（serverId + userId），generation 隔离同一身份下的内容代际；[invalidate] / [invalidateAll] 只作用于当前
 * namespace。
 *
 * 线程安全：读写都走单调 LRU 表（LinkedHashMap accessOrder），加锁保护。
 */
class MetadataCache(
    private val namespace: () -> MetadataNamespace?,
    private val maxEntries: Int = MetadataCacheRules.DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class ScopedKey(val namespace: MetadataNamespace, val key: String)

    private val lock = Any()

    private val entries =
        object : LinkedHashMap<ScopedKey, MetadataCacheEntry<Any?>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<ScopedKey, MetadataCacheEntry<Any?>>
            ): Boolean = size > maxEntries
        }

    /** W69b：每个 key 最近一次「发起请求」的时间（发起即记，含失败）——冷却期内不再重复请求。 */
    private val lastFetchAttemptAt = HashMap<ScopedKey, Long>()

    /** 每个 namespace 的内容代际；用户动作只递增当前 namespace。 */
    private val generations = HashMap<MetadataNamespace, Long>()

    /** 当前时间（与缓存写入同一时钟，便于单测）。 */
    fun now(): Long = clock()

    /** 当前身份栅栏；serverId / userId 任一缺失时 fail-closed，不读写缓存。 */
    fun currentFence(): MetadataFence? =
        synchronized(lock) {
            val activeNamespace =
                namespace()?.takeIf { it.serverId.isNotBlank() && it.userId.isNotBlank() }
                    ?: return@synchronized null
            MetadataFence(
                namespace = activeNamespace,
                contentGeneration = generations.getOrPut(activeNamespace) { 0L },
            )
        }

    fun isCurrent(fence: MetadataFence): Boolean =
        synchronized(lock) { currentFenceLocked() == fence }

    private fun currentFenceLocked(): MetadataFence? {
        val activeNamespace =
            namespace()?.takeIf { it.serverId.isNotBlank() && it.userId.isNotBlank() }
                ?: return null
        return MetadataFence(
            namespace = activeNamespace,
            contentGeneration = generations.getOrPut(activeNamespace) { 0L },
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): MetadataCacheEntry<T>? =
        currentFence()?.let { fence -> get(fence, key) }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(fence: MetadataFence, key: String): MetadataCacheEntry<T>? =
        synchronized(lock) {
            if (currentFenceLocked() != fence) {
                null
            } else {
                entries[ScopedKey(fence.namespace, key)] as? MetadataCacheEntry<T>
            }
        }

    fun <T> put(key: String, value: T): Boolean =
        currentFence()?.let { fence -> putIfCurrent(fence, key, value) } ?: false

    /** 提交前校验 fence，并在同一把锁内写入；旧代结果不得覆盖当前内容。 */
    fun <T> putIfCurrent(fence: MetadataFence, key: String, value: T): Boolean =
        putIfCurrent(fence, key, value, clock())

    /** 磁盘命中回填时保留 envelope 的 savedAt，避免把旧快照误判成刚抓取。 */
    fun <T> putIfCurrent(
        fence: MetadataFence,
        key: String,
        value: T,
        fetchedAtMs: Long,
    ): Boolean =
        synchronized(lock) {
            if (currentFenceLocked() != fence) {
                false
            } else {
                entries[ScopedKey(fence.namespace, key)] = MetadataCacheEntry(value, fetchedAtMs)
                true
            }
        }

    /** 只清理当前 namespace 中当前代际的指定键前缀。 */
    fun invalidate(prefix: String) {
        val fence = currentFence() ?: return
        synchronized(lock) {
            if (currentFenceLocked() != fence) return@synchronized
            val scopedPrefix = ScopedKey(fence.namespace, prefix)
            entries.keys.removeAll {
                it.namespace == fence.namespace && it.key.startsWith(scopedPrefix.key)
            }
            lastFetchAttemptAt.keys.removeAll {
                it.namespace == fence.namespace && it.key.startsWith(scopedPrefix.key)
            }
        }
    }

    /** 失效当前 namespace 的全部内容并递增 generation；其他 namespace 不受影响。 */
    fun invalidateAll() {
        val activeNamespace = currentFence()?.namespace ?: return
        synchronized(lock) {
            if (
                namespace()?.takeIf { it.serverId.isNotBlank() && it.userId.isNotBlank() } !=
                    activeNamespace
            ) {
                return@synchronized
            }
            generations[activeNamespace] = (generations[activeNamespace] ?: 0L) + 1L
            entries.keys.removeAll { it.namespace == activeNamespace }
            lastFetchAttemptAt.keys.removeAll { it.namespace == activeNamespace }
        }
    }

    /** W69b：冷却窗口内是否仍可发起请求（无记录 = 可以）。 */
    fun canStartFetch(
        key: String,
        nowMs: Long,
        cooldownMs: Long = MetadataCacheRules.MIN_REFRESH_COOLDOWN_MS,
    ): Boolean {
        val fence = currentFence() ?: return false
        return synchronized(lock) {
            if (currentFenceLocked() != fence) return@synchronized false
            MetadataCacheRules.canRefresh(
                lastFetchAttemptAt[ScopedKey(fence.namespace, key)] ?: 0L,
                nowMs,
                cooldownMs,
            )
        }
    }

    /** W69b：记录一次真实的请求发起（成功 / 失败都算，防止失败风暴）。 */
    fun markFetchStarted(key: String, nowMs: Long) {
        val fence = currentFence() ?: return
        synchronized(lock) {
            if (currentFenceLocked() == fence) {
                lastFetchAttemptAt[ScopedKey(fence.namespace, key)] = nowMs
            }
        }
    }

    /** 当前条数（单测 / 诊断用）。 */
    fun size(): Int = synchronized(lock) { entries.size }
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
    const val LIBRARY_ITEM_COUNT_PREFIX = "library-count:"
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

    fun libraryItemCount(libraryId: UUID): String = "$LIBRARY_ITEM_COUNT_PREFIX$libraryId"

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

    fun search(query: String, scope: SearchScope = SearchScope.ALL): String =
        "$SEARCH_PREFIX${scope.name}:${query.trim().lowercase()}"

    private fun signature(parts: List<*>?): String =
        parts?.joinToString(",") { it?.toString() ?: "-" } ?: "-"
}
