package com.zhangwenkang.cinefin.film.presentation.search

import com.zhangwenkang.cinefin.local.LocalMediaKind
import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.models.FindroidFolder
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie

/** W82 #25：搜索结果的三类展示分区。 */
enum class SearchCategory {
    VIDEO,
    MUSIC,
    BOOK,
}

/**
 * 搜索结果分类 / 去重纯函数。
 *
 * 服务器 `AUDIO` 与 `VIDEO` 都映射成 `FindroidMovie`，所以分类必须看原始 `kind`； `FindroidFolder` 的 `kind` 用于识别 Book
 * / 音乐容器。所有来源最后都收敛到 [SearchCategory]，UI 只按分类渲染，不再散落类型判断。
 */
object SearchResultRules {

    fun categoryOf(item: FindroidItem): SearchCategory =
        when (item) {
            is FindroidMovie ->
                if (item.kind.equals("AUDIO", ignoreCase = true)) SearchCategory.MUSIC
                else SearchCategory.VIDEO
            is FindroidFolder -> categoryOfServerKind(item.kind)
            else -> SearchCategory.VIDEO
        }

    fun categoryOf(hit: LocalSearchHit): SearchCategory =
        when (hit.entry.kind) {
            LocalMediaKind.VIDEO -> SearchCategory.VIDEO
            LocalMediaKind.MUSIC -> SearchCategory.MUSIC
            LocalMediaKind.BOOK -> SearchCategory.BOOK
        }

    private fun categoryOfServerKind(kind: String?): SearchCategory =
        when (kind?.uppercase()) {
            "BOOK" -> SearchCategory.BOOK
            "MUSIC_ALBUM",
            "MUSIC_ARTIST",
            "PLAYLIST" -> SearchCategory.MUSIC
            else -> SearchCategory.VIDEO
        }

    /** 同一服务器条目可能被多个库命中；按 id 去重，避免跨类型重复卡片。 */
    fun dedupeServer(items: List<FindroidItem>): List<FindroidItem> = items.distinctBy { it.id }

    /** 本地索引按 itemId 去重；同名不同路径的文件仍保留。 */
    fun dedupeLocal(hits: List<LocalSearchHit>): List<LocalSearchHit> = hits.distinctBy {
        it.entry.itemId
    }
}
