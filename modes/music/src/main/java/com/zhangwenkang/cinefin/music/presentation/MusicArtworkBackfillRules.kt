package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicItemSource
import com.zhangwenkang.cinefin.music.data.MusicSong
import java.util.UUID

/** W3-T20：列表加载后「无封面补取」的节流口径（保守：只发 GET，不发 Refresh）。 */
internal const val MUSIC_ARTWORK_BACKFILL_DELAY_MS = 2_000L

/** 每个加载周期最多补取的曲目数（防批量轰炸；同曲目整个会话只试一次）。 */
internal const val MUSIC_ARTWORK_BACKFILL_BATCH_LIMIT = 20

/** 补取并发（与项目补队列批次 6 同量级，取更小的 4 降低并发请求压力）。 */
internal const val MUSIC_ARTWORK_BACKFILL_CONCURRENCY = 4

/**
 * W3-T20 无封面补取候选（纯函数）。
 *
 * 只挑「服务器来源 + 没有封面地址 + 不是歌单占位 + 本会话尚未尝试过」的曲目，最多 [limit] 首。 本地曲目没有服务端图片可补，跳过。
 */
internal fun musicArtworkBackfillCandidates(
    songs: List<MusicSong>,
    triedItemIds: Set<UUID>,
    limit: Int = MUSIC_ARTWORK_BACKFILL_BATCH_LIMIT,
): List<UUID> =
    songs
        .asSequence()
        .filter { song -> song.source == MusicItemSource.SERVER }
        .filter { song -> song.imageUri.isNullOrBlank() }
        .filterNot { song -> song.isPlaylistPlaceholder }
        .map { song -> song.itemId }
        .filter { itemId -> itemId !in triedItemIds }
        .distinct()
        .take(limit.coerceAtLeast(0))
        .toList()

/** 补取批次切分（纯函数）：每批最多 [concurrency] 首。 */
internal fun musicArtworkBackfillChunks(
    itemIds: List<UUID>,
    concurrency: Int = MUSIC_ARTWORK_BACKFILL_CONCURRENCY,
): List<List<UUID>> = itemIds.chunked(concurrency.coerceAtLeast(1))
