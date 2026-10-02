package com.zhangwenkang.cinefin.database.music

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

/** 音乐队列持久化（W21-R2）：整表替换 + 状态单行。 */
@Dao
interface MusicQueueDao {
    @Query("SELECT * FROM music_queue_items ORDER BY position")
    suspend fun getItems(): List<MusicQueueItemEntity>

    @Query("SELECT * FROM music_queue_state WHERE id = 0")
    suspend fun getState(): MusicQueueStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<MusicQueueItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(state: MusicQueueStateEntity)

    @Query("DELETE FROM music_queue_items") suspend fun clearItems()

    @Query("DELETE FROM music_queue_state") suspend fun clearState()

    /** 保存队列：清掉旧快照后写入新快照（同一事务，避免读到半份队列）。 */
    @Transaction
    suspend fun replace(items: List<MusicQueueItemEntity>, state: MusicQueueStateEntity) {
        clearItems()
        clearState()
        insertItems(items)
        insertState(state)
    }

    @Transaction
    suspend fun clear() {
        clearItems()
        clearState()
    }
}

/** 本地最近播放（W21-R2）。 */
@Dao
interface MusicRecentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entry: MusicRecentEntity)

    @Query("SELECT * FROM music_recent ORDER BY playedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MusicRecentEntity>

    /** 只保留最近 [keep] 条，避免本地列表无限增长。 */
    @Query(
        "DELETE FROM music_recent WHERE itemId NOT IN (SELECT itemId FROM music_recent ORDER BY playedAt DESC LIMIT :keep)"
    )
    suspend fun trim(keep: Int)
}
