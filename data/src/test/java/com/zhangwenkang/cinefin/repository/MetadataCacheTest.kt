package com.zhangwenkang.cinefin.repository

import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W69：元数据缓存（TTL / LRU / 失效 / 命名空间）+ 缓存键单测。 */
class MetadataCacheTest {

    @Test
    fun `TTL 内命中、到期即过期`() {
        var now = 1_000L
        val cache = MetadataCache(clock = { now })
        cache.put("views", listOf("a", "b"))

        now = 1_000L + MetadataCacheRules.DEFAULT_TTL_MS - 1
        assertTrue(
            MetadataCacheRules.isFresh(
                fetchedAtMs = 1_000L,
                nowMs = now,
                ttlMs = MetadataCacheRules.DEFAULT_TTL_MS,
            )
        )
        assertEquals(listOf("a", "b"), cache.get<List<String>>("views")?.value)

        now = 1_000L + MetadataCacheRules.DEFAULT_TTL_MS
        assertFalse(
            MetadataCacheRules.isFresh(
                fetchedAtMs = 1_000L,
                nowMs = now,
                ttlMs = MetadataCacheRules.DEFAULT_TTL_MS,
            )
        )
    }

    @Test
    fun `时钟回拨按过期处理`() {
        assertFalse(MetadataCacheRules.isFresh(fetchedAtMs = 5_000L, nowMs = 4_000L))
    }

    @Test
    fun `已上屏内容过期才静默重取`() {
        // 从未上屏（0）= 走骨架 / 完整加载，不叫"静默重取"。
        assertFalse(MetadataCacheRules.shouldSilentlyRevalidate(loadedAtMs = 0L, nowMs = 1_000L))
        // TTL 内：直接复用，什么都不做。
        assertFalse(
            MetadataCacheRules.shouldSilentlyRevalidate(
                loadedAtMs = 1_000L,
                nowMs = 1_000L + MetadataCacheRules.DEFAULT_TTL_MS - 1,
            )
        )
        // TTL 外：保留画面 + 后台静默重取。
        assertTrue(
            MetadataCacheRules.shouldSilentlyRevalidate(
                loadedAtMs = 1_000L,
                nowMs = 1_000L + MetadataCacheRules.DEFAULT_TTL_MS,
            )
        )
    }

    @Test
    fun `W69b 最小刷新冷却按窗口判定`() {
        // 从未刷新过 → 允许。
        assertTrue(MetadataCacheRules.canRefresh(lastRefreshAtMs = 0L, nowMs = 1_000L))
        // 冷却窗口内 → 不允许（返回旧值，不打服务器）。
        assertFalse(
            MetadataCacheRules.canRefresh(
                lastRefreshAtMs = 1_000L,
                nowMs = 1_000L + MetadataCacheRules.MIN_REFRESH_COOLDOWN_MS - 1,
            )
        )
        // 到达 / 超过冷却 → 允许。
        assertTrue(
            MetadataCacheRules.canRefresh(
                lastRefreshAtMs = 1_000L,
                nowMs = 1_000L + MetadataCacheRules.MIN_REFRESH_COOLDOWN_MS,
            )
        )
        // 冷却常量落在任务书建议的 30–60 s 区间。
        assertTrue(MetadataCacheRules.MIN_REFRESH_COOLDOWN_MS >= 30_000L)
        assertTrue(MetadataCacheRules.MIN_REFRESH_COOLDOWN_MS <= 60_000L)
    }

    @Test
    fun `W69b 缓存按 key 记录请求发起时间`() {
        val cache = MetadataCache(clock = { 1_000L })
        assertTrue(cache.canStartFetch("resume:Movie,Episode", nowMs = 1_000L))
        cache.markFetchStarted("resume:Movie,Episode", nowMs = 1_000L)
        assertFalse(cache.canStartFetch("resume:Movie,Episode", nowMs = 1_000L + 5_000L))
        assertTrue(
            cache.canStartFetch(
                "resume:Movie,Episode",
                nowMs = 1_000L + MetadataCacheRules.MIN_REFRESH_COOLDOWN_MS,
            )
        )
        // 其他 key 不受影响。
        assertTrue(cache.canStartFetch("views", nowMs = 2_000L))
        // 全量失效同时清掉冷却记录（下拉刷新后可以立刻重取）。
        cache.invalidateAll()
        assertTrue(cache.canStartFetch("resume:Movie,Episode", nowMs = 2_000L))
    }

    @Test
    fun `W69b 同 key 并发请求只保留一个 owner`() {
        val inFlight = InFlightRequests()
        val (first, firstOwner) = inFlight.join("show:1")
        val (second, secondOwner) = inFlight.join("show:1")
        val (other, otherOwner) = inFlight.join("show:2")

        assertTrue(firstOwner)
        assertFalse(secondOwner)
        assertTrue(second === first)
        assertTrue(otherOwner)
        assertFalse(other === first)
        assertEquals(2, inFlight.size())

        first.complete("done")
        inFlight.finish("show:1", first)
        assertEquals(1, inFlight.size())
        // owner 收尾后同 key 可以重新发起。
        val (again, againOwner) = inFlight.join("show:1")
        assertTrue(againOwner)
        assertFalse(again === first)
    }

    @Test
    fun `默认 TTL 落在任务要求的 5 到 15 分钟区间`() {
        assertTrue(MetadataCacheRules.DEFAULT_TTL_MS >= 5 * 60 * 1000L)
        assertTrue(MetadataCacheRules.DEFAULT_TTL_MS <= 15 * 60 * 1000L)
        assertTrue(MetadataCacheRules.PAGING_TTL_MS <= MetadataCacheRules.DEFAULT_TTL_MS)
    }

    @Test
    fun `超过上限按最久未使用淘汰`() {
        var now = 1_000L
        val cache = MetadataCache(maxEntries = 3, clock = { now })
        cache.put("a", 1)
        now += 1
        cache.put("b", 2)
        now += 1
        cache.put("c", 3)
        now += 1

        // 访问 a，使 b 成为最久未使用。
        assertNotNull(cache.get<Int>("a"))
        cache.put("d", 4)

        assertNull(cache.get<Int>("b"))
        assertNotNull(cache.get<Int>("a"))
        assertNotNull(cache.get<Int>("c"))
        assertNotNull(cache.get<Int>("d"))
        assertEquals(3, cache.size())
    }

    @Test
    fun `invalidate 只清指定前缀`() {
        val cache = MetadataCache(clock = { 1_000L })
        val libraryId = UUID.randomUUID()
        cache.put(MetadataCacheKeys.latestMedia(libraryId), "latest")
        cache.put(MetadataCacheKeys.resume(listOf(BaseItemKind.MOVIE)), "resume")
        cache.put(MetadataCacheKeys.SUGGESTIONS, "suggestions")

        cache.invalidate(MetadataCacheKeys.RESUME_PREFIX)

        assertEquals("latest", cache.get<String>(MetadataCacheKeys.latestMedia(libraryId))?.value)
        assertEquals("suggestions", cache.get<String>(MetadataCacheKeys.SUGGESTIONS)?.value)
        assertEquals(2, cache.size())
    }

    @Test
    fun `invalidateAll 清空`() {
        val cache = MetadataCache(clock = { 1_000L })
        cache.put(MetadataCacheKeys.VIEWS, "views")
        cache.put(MetadataCacheKeys.SUGGESTIONS, "suggestions")

        cache.invalidateAll()

        assertNull(cache.get<String>(MetadataCacheKeys.VIEWS))
        assertEquals(0, cache.size())
    }

    @Test
    fun `服务器与用户命名空间互不可见`() {
        var namespace = "srv-a|user-1|"
        val cache = MetadataCache(namespace = { namespace }, clock = { 1_000L })
        cache.put(MetadataCacheKeys.VIEWS, "views-a")

        namespace = "srv-b|user-2|"
        assertNull(cache.get<String>(MetadataCacheKeys.VIEWS))
        cache.put(MetadataCacheKeys.VIEWS, "views-b")

        // 切回旧账号仍能命中旧账号的缓存（没有被误删）。
        namespace = "srv-a|user-1|"
        assertEquals("views-a", cache.get<String>(MetadataCacheKeys.VIEWS)?.value)
        // invalidateAll = 用户动作（收藏 / 已看 / 播放结束）的全量失效，跨命名空间一起清。
        cache.invalidateAll()
        namespace = "srv-b|user-2|"
        assertNull(cache.get<String>(MetadataCacheKeys.VIEWS))
        namespace = "srv-a|user-1|"
        assertNull(cache.get<String>(MetadataCacheKeys.VIEWS))
    }

    @Test
    fun `分页键区分页码窗口与过滤条件`() {
        val parent = UUID.randomUUID()
        val firstPage =
            MetadataCacheKeys.items(
                parentId = parent,
                includeTypes = listOf(BaseItemKind.MOVIE),
                recursive = true,
                sortBy = "DateCreated",
                sortOrder = "Descending",
                startIndex = 0,
                limit = 10,
                filters = null,
                genres = null,
                studios = null,
            )
        val secondPage =
            MetadataCacheKeys.items(
                parentId = parent,
                includeTypes = listOf(BaseItemKind.MOVIE),
                recursive = true,
                sortBy = "DateCreated",
                sortOrder = "Descending",
                startIndex = 10,
                limit = 10,
                filters = null,
                genres = null,
                studios = null,
            )
        val unfiltered =
            MetadataCacheKeys.items(
                parentId = parent,
                includeTypes = listOf(BaseItemKind.MOVIE),
                recursive = true,
                sortBy = "DateCreated",
                sortOrder = "Descending",
                startIndex = 0,
                limit = 10,
                filters = listOf(ItemFilter.IS_UNPLAYED),
                genres = null,
                studios = null,
            )
        val sameAsFirst =
            MetadataCacheKeys.items(
                parentId = parent,
                includeTypes = listOf(BaseItemKind.MOVIE),
                recursive = true,
                sortBy = "DateCreated",
                sortOrder = "Descending",
                startIndex = 0,
                limit = 10,
                filters = null,
                genres = null,
                studios = null,
            )

        assertFalse(firstPage == secondPage)
        assertFalse(firstPage == unfiltered)
        assertEquals(firstPage, sameAsFirst)
    }

    @Test
    fun `搜索键忽略大小写与首尾空白`() {
        assertEquals(MetadataCacheKeys.search("  Dune "), MetadataCacheKeys.search("dune"))
    }

    @Test
    fun `续播键按类型区分`() {
        assertFalse(
            MetadataCacheKeys.resume(listOf(BaseItemKind.BOOK)) ==
                MetadataCacheKeys.resume(listOf(BaseItemKind.AUDIO))
        )
    }

    @Test
    fun `剧集键区分季与取数窗口`() {
        val series = UUID.randomUUID()
        val season = UUID.randomUUID()
        val base =
            MetadataCacheKeys.episodes(
                seriesId = series,
                seasonId = season,
                fields = listOf("Overview"),
                startItemId = null,
                limit = null,
                offline = false,
            )
        val limited =
            MetadataCacheKeys.episodes(
                seriesId = series,
                seasonId = season,
                fields = listOf("Overview"),
                startItemId = null,
                limit = 100,
                offline = false,
            )
        val otherSeason =
            MetadataCacheKeys.episodes(
                seriesId = series,
                seasonId = UUID.randomUUID(),
                fields = listOf("Overview"),
                startItemId = null,
                limit = null,
                offline = false,
            )

        assertFalse(base == limited)
        assertFalse(base == otherSeason)
    }
}
