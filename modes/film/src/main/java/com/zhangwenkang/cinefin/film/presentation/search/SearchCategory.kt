package com.zhangwenkang.cinefin.film.presentation.search

import com.zhangwenkang.cinefin.local.LocalMediaKind
import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.models.FindroidFolder
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadMediaRecord
import java.util.UUID

/** W82 #25：搜索结果的三类展示分区。 */
enum class SearchCategory {
    VIDEO,
    MUSIC,
    BOOK,
}

/** W88-B：结果卡片的可证明来源，只能由 canonical identity 折叠产生。 */
enum class SearchSource {
    SERVER,
    LOCAL_LIBRARY,
    DOWNLOADED,
}

/** 搜索结果唯一身份；三段齐全才允许参与服务器侧去重 / 折叠。 */
data class ServerItemIdentity(
    val serverId: String,
    val userId: UUID,
    val itemId: UUID,
)

data class ServerSearchCandidate(
    val identity: ServerItemIdentity,
    val item: FindroidItem,
)

/** 本地库唯一身份；不允许与服务器 UUID 裸认。 */
data class LocalLibraryItemIdentity(
    val libraryId: Long,
    val itemId: UUID,
)

sealed interface SearchResult {
    val stableKey: String
    val category: SearchCategory
    val sources: Set<SearchSource>
    val displayName: String

    data class Server(
        val identity: ServerItemIdentity,
        val item: FindroidItem,
        override val sources: Set<SearchSource> = setOf(SearchSource.SERVER),
    ) : SearchResult {
        override val stableKey: String =
            "server\u0000${identity.serverId}\u0000${identity.userId}\u0000${identity.itemId}"
        override val category: SearchCategory = SearchResultRules.categoryOf(item)
        override val displayName: String = item.name
    }

    /** 离线模式没有可证明 userId 时的服务器缓存结果。它保留展示 / 打开能力，但绝不参与下载折叠或跨来源去重。 */
    data class UnidentifiedServer(val item: FindroidItem) : SearchResult {
        override val stableKey: String = "unidentified-server\u0000${item.id}"
        override val category: SearchCategory = SearchResultRules.categoryOf(item)
        override val sources: Set<SearchSource> = setOf(SearchSource.SERVER)
        override val displayName: String = item.name
    }

    data class LocalLibrary(
        val identity: LocalLibraryItemIdentity,
        val hit: LocalSearchHit,
        override val sources: Set<SearchSource> = setOf(SearchSource.LOCAL_LIBRARY),
    ) : SearchResult {
        override val stableKey: String = "local\u0000${identity.libraryId}\u0000${identity.itemId}"
        override val category: SearchCategory = SearchResultRules.categoryOf(hit)
        override val displayName: String = hit.entry.displayName
    }

    data class Download(
        val record: DownloadMediaRecord,
        override val sources: Set<SearchSource> = setOf(SearchSource.DOWNLOADED),
    ) : SearchResult {
        override val stableKey: String =
            listOf(
                    "download",
                    record.kind.name,
                    record.itemId,
                    record.serverId.orEmpty(),
                    record.userId.orEmpty(),
                    record.sourceId.orEmpty(),
                )
                .joinToString("\u0000")
        override val category: SearchCategory = SearchResultRules.categoryOf(record)
        override val displayName: String = record.displayName ?: record.itemId.take(8)
    }
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

    fun categoryOf(record: DownloadMediaRecord): SearchCategory =
        when (record.kind) {
            DownloadMediaKind.VIDEO -> SearchCategory.VIDEO
            DownloadMediaKind.MUSIC -> SearchCategory.MUSIC
            DownloadMediaKind.BOOK -> SearchCategory.BOOK
        }

    private fun categoryOfServerKind(kind: String?): SearchCategory =
        when (kind?.uppercase()) {
            "BOOK" -> SearchCategory.BOOK
            "MUSIC_ALBUM",
            "MUSIC_ARTIST",
            "PLAYLIST" -> SearchCategory.MUSIC
            else -> SearchCategory.VIDEO
        }

    /** 同一服务器条目可能被多个库命中；必须按 `serverId + userId + itemId` 去重。 此裸 UUID 版本只为旧测试保留，生产搜索不得调用。 */
    @Deprecated("W88-B：生产搜索使用 fold(serverItems)，禁止跨 server/user 按 UUID 互认")
    fun dedupeServer(items: List<FindroidItem>): List<FindroidItem> = items.distinctBy { it.id }

    /** 本地库身份 = `libraryId + itemId`；同 UUID 跨库仍并列。 */
    fun dedupeLocal(hits: List<LocalSearchHit>): List<LocalSearchHit> = hits.distinctBy {
        LocalLibraryItemIdentity(it.libraryId, it.entry.itemId)
    }

    /**
     * 以证据折叠三类来源，并保持确定性顺序：
     * 1. server 输入首见顺序（同 canonical identity 去重）；
     * 2. local library 输入首见顺序（同 libraryId + itemId 去重）；
     * 3. 未折叠下载输入首见顺序。
     *
     * 本地库没有服务器 provenance，因此永不与 server 折叠。下载必须三段 provenance 完整，视频 / 音乐还必须 命中服务器条目的同一 remote media
     * source；缺段、坏行或 source 不同均保持独立。
     */
    fun fold(
        serverItems: List<ServerSearchCandidate>,
        localHits: List<LocalSearchHit>,
        downloads: List<DownloadMediaRecord>,
    ): List<SearchResult> {
        val uniqueServers = serverItems.distinctBy { it.identity }
        val uniqueDownloads = downloads.distinctBy { it.downloadIdentityKey() }
        val matchedDownloadKeys = mutableSetOf<String>()
        val serverResults = uniqueServers.map { candidate ->
            val matchingDownloads = uniqueDownloads.filter { record ->
                record.downloadIdentityKey() !in matchedDownloadKeys &&
                    record.provenance()?.matches(candidate) == true
            }
            matchedDownloadKeys += matchingDownloads.map { it.downloadIdentityKey() }
            SearchResult.Server(
                identity = candidate.identity,
                item = candidate.item,
                sources =
                    if (matchingDownloads.isEmpty()) {
                        setOf(SearchSource.SERVER)
                    } else {
                        setOf(SearchSource.SERVER, SearchSource.DOWNLOADED)
                    },
            )
        }
        val localResults =
            dedupeLocal(localHits).map { hit ->
                SearchResult.LocalLibrary(
                    identity = LocalLibraryItemIdentity(hit.libraryId, hit.entry.itemId),
                    hit = hit,
                )
            }
        val downloadResults =
            uniqueDownloads
                .filterNot { it.downloadIdentityKey() in matchedDownloadKeys }
                .map { SearchResult.Download(it) }
        return serverResults + localResults + downloadResults
    }

    private fun com.zhangwenkang.cinefin.utils.DownloadProvenance.matches(
        candidate: ServerSearchCandidate
    ): Boolean {
        if (
            serverId != candidate.identity.serverId ||
                userId != candidate.identity.userId ||
                itemId != candidate.identity.itemId
        ) {
            return false
        }
        if (mediaSourceId == null) return true
        return candidate.item.sources.any {
            it.type == FindroidSourceType.REMOTE && it.id == mediaSourceId
        }
    }

    private fun DownloadMediaRecord.downloadIdentityKey(): String =
        listOf(kind.name, itemId, serverId.orEmpty(), userId.orEmpty(), sourceId.orEmpty())
            .joinToString("\u0000")
}
