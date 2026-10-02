package com.zhangwenkang.cinefin.database.music

/**
 * 音乐本地存储入口（W21-R2）。
 *
 * 接口只暴露 DTO 与基元类型，不泄露 Room 类型；音乐模块通过它持久化队列与最近播放。
 */
interface MusicStorage {
    suspend fun saveQueue(queue: StoredQueue)

    suspend fun loadQueue(): StoredQueue?

    suspend fun clearQueue()

    suspend fun recordRecent(entry: StoredRecentSong)

    suspend fun loadRecent(limit: Int = DEFAULT_RECENT_LIMIT): List<StoredRecentSong>

    companion object {
        /** 最近播放最多保留的条目数。 */
        const val DEFAULT_RECENT_LIMIT = 100
    }
}

class MusicStorageImpl
constructor(
    private val musicQueueDao: MusicQueueDao,
    private val musicRecentDao: MusicRecentDao,
) : MusicStorage {

    override suspend fun saveQueue(queue: StoredQueue) {
        musicQueueDao.replace(
            items =
                queue.items.map { item ->
                    MusicQueueItemEntity(
                        position = item.position,
                        itemId = item.itemId,
                        name = item.name,
                        mediaSourceId = item.mediaSourceId,
                        playbackPositionMs = item.playbackPositionMs,
                        mediaSourceUri = item.mediaSourceUri,
                        thumbnailUri = item.thumbnailUri,
                        indexNumber = item.indexNumber,
                    )
                },
            state =
                MusicQueueStateEntity(
                    currentIndex = queue.state.currentIndex,
                    positionMs = queue.state.positionMs,
                    repeatMode = queue.state.repeatMode,
                    shuffleEnabled = queue.state.shuffleEnabled,
                    source = queue.state.source,
                    sourceId = queue.state.sourceId,
                    savedAt = queue.state.savedAt,
                ),
        )
    }

    override suspend fun loadQueue(): StoredQueue? {
        val state = musicQueueDao.getState() ?: return null
        val items = musicQueueDao.getItems()
        if (items.isEmpty()) return null
        return StoredQueue(
            items =
                items.map { item ->
                    StoredQueueItem(
                        position = item.position,
                        itemId = item.itemId,
                        name = item.name,
                        mediaSourceId = item.mediaSourceId,
                        playbackPositionMs = item.playbackPositionMs,
                        mediaSourceUri = item.mediaSourceUri,
                        thumbnailUri = item.thumbnailUri,
                        indexNumber = item.indexNumber,
                    )
                },
            state =
                StoredQueueState(
                    currentIndex = state.currentIndex,
                    positionMs = state.positionMs,
                    repeatMode = state.repeatMode,
                    shuffleEnabled = state.shuffleEnabled,
                    source = state.source,
                    sourceId = state.sourceId,
                    savedAt = state.savedAt,
                ),
        )
    }

    override suspend fun clearQueue() {
        musicQueueDao.clear()
    }

    override suspend fun recordRecent(entry: StoredRecentSong) {
        musicRecentDao.upsert(
            MusicRecentEntity(
                itemId = entry.itemId,
                name = entry.name,
                albumName = entry.albumName,
                artist = entry.artist,
                imageUri = entry.imageUri,
                runtimeTicks = entry.runtimeTicks,
                playedAt = entry.playedAt,
            )
        )
        musicRecentDao.trim(MusicStorage.DEFAULT_RECENT_LIMIT)
    }

    override suspend fun loadRecent(limit: Int): List<StoredRecentSong> =
        musicRecentDao.getRecent(limit).map { entity ->
            StoredRecentSong(
                itemId = entity.itemId,
                name = entity.name,
                albumName = entity.albumName,
                artist = entity.artist,
                imageUri = entity.imageUri,
                runtimeTicks = entity.runtimeTicks,
                playedAt = entity.playedAt,
            )
        }
}
