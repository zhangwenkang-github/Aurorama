package com.zhangwenkang.cinefin.database.music

import androidx.room3.Database
import androidx.room3.RoomDatabase

/**
 * 音乐线独立数据库（W21-R2）。
 *
 * 队列与最近播放不挤进 `ServerDatabase`：W20 正在改 data 层的服务器库 schema / 迁移， 独立库让两波改动在文件级别完全隔离（本波全部为新增文件）。
 */
@Database(
    entities =
        [MusicQueueItemEntity::class, MusicQueueStateEntity::class, MusicRecentEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun musicQueueDao(): MusicQueueDao

    abstract fun musicRecentDao(): MusicRecentDao
}
