package com.zhangwenkang.cinefin.repository

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * W62：媒体库「共 N 个项目」的数字口径（纯函数 + 单测）。
 *
 * 服务器 10.11.8 对 UserView（库视图）的 `ChildCount` 是**随机值**：W61 全量回归与本波只读探针都在同一请求上 采样到不同数字（电影 8/4/6、动漫
 * 4/5/2、书籍 9/5/3…），而按 `parentId` 直接子项查询的 `TotalRecordCount` 两次采样完全一致（电影 17 / 动漫 94 / 书籍 8 / 音乐
 * 124），且与进入库后内容页看到的口径一致。
 *
 * 因此上屏数字 = 稳定计数；只有稳定计数拿不到（查询失败 / 离线）时才回退 `ChildCount`（老口径）， 两者都没有则返回 null（UI 按既有规则只显示库名，不占位）。
 */
fun stableLibraryItemCount(stableCount: Int?, childCount: Int?): Int? =
    stableCount?.takeIf { it >= 0 } ?: childCount?.takeIf { it >= 0 }

/**
 * W62：库直接子项稳定计数的进程内短 TTL 缓存（纯逻辑，注入时钟便于单测）。
 *
 * 库列表在侧栏（抽屉）、视频页、媒体库页、书架四处都会各读一次；稳定计数要为每个库多发一发轻量查询 （`parentId` + `recursive=false` +
 * `limit=1`），缓存避免同一会话里反复打服务器。
 */
internal class LibraryItemCountCache(
    private val ttlMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val count: Int, val at: Long)

    private val entries = ConcurrentHashMap<UUID, Entry>()

    /** 命中且未过期返回计数；未命中 / 已过期返回 null（过期条目顺手清掉）。 */
    fun get(libraryId: UUID): Int? {
        val entry = entries[libraryId] ?: return null
        if (now() - entry.at > ttlMillis) {
            entries.remove(libraryId, entry)
            return null
        }
        return entry.count
    }

    fun put(libraryId: UUID, count: Int) {
        entries[libraryId] = Entry(count, now())
    }

    fun clear() {
        entries.clear()
    }
}
