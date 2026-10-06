package com.zhangwenkang.cinefin.presentation.utils

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.flow.first

/**
 * 「导航条目 → 滚动位置」的有界记忆表（W75 #5）。
 *
 * 详情 / 列表页返回时会「回到界面最上面」（#5）有两条已知路径：
 * 1. 目的地的组合被回收后重新创建时，平台侧保存的滚动值没能对上（实测：视频页聚合网格从条目详情返回后 `firstVisibleItemIndex` 回到 0），列表 / 网格就被打回顶部。
 * 2. 恢复发生在**内容就位之前**——骨架屏 / 首屏还没有条目时最大可滚动量不足，恢复出来的偏移被夹到 0， 之后条目补齐也不会回到原位（`rememberSaveable`
 *    里已经没有原值了）。
 *
 * 这里按「导航条目 id」记一份偏移：条目没变（详情页盖住列表、进季再返回）就能落回原位置； 换条目（从库里重新打开同一剧集）是新 id、不会继承旧位置，既有导航语义不变。 表按 LRU
 * 淘汰，条目销毁后残留的记录也会被后续写入顶掉，因此不会无界增长。
 */
internal class NavScrollMemory(private val maxEntries: Int = 24) {
    private val scrollOffsets = LinkedHashMap<String, Int>()
    private val lazyPositions = LinkedHashMap<String, Pair<Int, Int>>()

    fun scrollOffset(key: String): Int = scrollOffsets[key] ?: 0

    fun rememberScrollOffset(key: String, offset: Int) {
        touch(scrollOffsets, key, offset)
    }

    fun lazyPosition(key: String): Pair<Int, Int>? = lazyPositions[key]

    fun rememberLazyPosition(key: String, index: Int, offset: Int) {
        touch(lazyPositions, key, index to offset)
    }

    /** 仅供测试：清空记忆，避免用例之间互相影响。 */
    fun clear() {
        scrollOffsets.clear()
        lazyPositions.clear()
    }

    private fun <T> touch(map: LinkedHashMap<String, T>, key: String, value: T) {
        // 先删后插：把 key 挪到最近使用的一端（LRU 淘汰靠这个顺序）。
        map.remove(key)
        map[key] = value
        while (map.size > maxEntries) {
            val oldest = map.keys.firstOrNull() ?: break
            map.remove(oldest)
        }
    }
}

private val navScrollMemory = NavScrollMemory()

/** 记忆键（纯函数，单测覆盖）：优先用**导航条目 id**——它在一个目的地被盖住又回来时保持不变， 而「重新导航到同一页面」会拿到新条目，从而不会继承上一次的位置。 */
internal fun navScrollKey(owner: Any?, fallback: String): String =
    if (owner is NavBackStackEntry) "entry:${owner.id}" else fallback

/** 当前组合所属的导航条目 id（不在 NavHost 里时回落到 [fallbackKey]）。 */
@Composable
private fun rememberNavScrollKey(fallbackKey: String): String {
    val owner = LocalLifecycleOwner.current
    return remember(owner, fallbackKey) { navScrollKey(owner, fallbackKey) }
}

/**
 * 带位置记忆的 [ScrollState]（详情页 Column + verticalScroll 用）。
 *
 * 平台的 [rememberScrollState] 本身是 saveable 的；这里额外加一层「内容就位后再落位」， 覆盖「恢复时内容还没铺开、偏移被夹到 0」这条路径。
 */
@Composable
internal fun rememberScrollMemoryState(fallbackKey: String): ScrollState {
    val key = rememberNavScrollKey(fallbackKey)
    val state = rememberScrollState()

    LaunchedEffect(key) {
        val target = navScrollMemory.scrollOffset(key)
        if (target <= 0) return@LaunchedEffect
        // 等内容能滚到目标位置再落位，避免被短内容夹到 0。
        snapshotFlow { state.maxValue }.first { it >= target }
        if (state.value < target) state.scrollTo(target)
    }
    LaunchedEffect(key, state) {
        snapshotFlow { state.value to state.maxValue }
            .collect { (value, max) ->
                // 内容还没铺开（max == 0）时不写：否则会把记忆值污染成 0。
                if (max > 0) navScrollMemory.rememberScrollOffset(key, value)
            }
    }
    return state
}

/** 带位置记忆的 [LazyListState]（详情页 LazyColumn 用）。 */
@Composable
internal fun rememberLazyScrollMemoryState(fallbackKey: String): LazyListState {
    val key = rememberNavScrollKey(fallbackKey)
    val state = rememberLazyListState()

    LaunchedEffect(key) {
        val target = navScrollMemory.lazyPosition(key) ?: return@LaunchedEffect
        if (target.first == 0 && target.second == 0) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > target.first }
        if (state.firstVisibleItemIndex < target.first) {
            state.scrollToItem(target.first, target.second)
        }
    }
    LaunchedEffect(key, state) {
        snapshotFlow {
            Triple(
                state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset,
                state.layoutInfo.totalItemsCount,
            )
        }
            .collect { (index, offset, total) ->
                if (total > 0) navScrollMemory.rememberLazyPosition(key, index, offset)
            }
    }
    return state
}

/** 带位置记忆的 [LazyGridState]（库内容页网格用）。 */
@Composable
internal fun rememberLazyGridScrollMemoryState(fallbackKey: String): LazyGridState {
    val key = rememberNavScrollKey(fallbackKey)
    val state = rememberLazyGridState()

    LaunchedEffect(key) {
        val target = navScrollMemory.lazyPosition(key) ?: return@LaunchedEffect
        if (target.first == 0 && target.second == 0) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > target.first }
        if (state.firstVisibleItemIndex < target.first) {
            state.scrollToItem(target.first, target.second)
        }
    }
    LaunchedEffect(key, state) {
        snapshotFlow {
            Triple(
                state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset,
                state.layoutInfo.totalItemsCount,
            )
        }
            .collect { (index, offset, total) ->
                if (total > 0) navScrollMemory.rememberLazyPosition(key, index, offset)
            }
    }
    return state
}
