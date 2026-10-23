package com.zhangwenkang.cinefin.database.music

import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * 音乐线独立数据库（W21-R2）。
 *
 * 队列与最近播放不挤进 `ServerDatabase`：W20 正在改 data 层的服务器库 schema / 迁移， 独立库让两波改动在文件级别完全隔离（本波全部为新增文件）。
 */
@Database(
    entities =
        [
            MusicQueueItemEntity::class,
            MusicQueueStateEntity::class,
            MusicRecentEntity::class,
            LocalPlaylistEntity::class,
            LocalPlaylistEntryEntity::class,
        ],
    version = 2,
    exportSchema = true,
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun musicQueueDao(): MusicQueueDao

    abstract fun musicRecentDao(): MusicRecentDao

    abstract fun localPlaylistDao(): LocalPlaylistDao

    companion object {
        /** v1 → v2 仅新增本地歌单表和索引；队列 / 最近播放不迁移、不重建。 */
        val MIGRATION_1_2 =
            object : Migration(startVersion = 1, endVersion = 2) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS `local_playlists` (" +
                            "`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`id`))"
                    )
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS `local_playlist_entries` (" +
                            "`id` TEXT NOT NULL, `playlistId` TEXT NOT NULL, " +
                            "`trackKind` TEXT NOT NULL, `serverId` TEXT NOT NULL, " +
                            "`userId` TEXT NOT NULL, `libraryId` INTEGER NOT NULL, " +
                            "`itemId` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                            "`albumName` TEXT NOT NULL, `artist` TEXT, `imageUri` TEXT, " +
                            "`mediaSourceUri` TEXT, `runtimeTicks` INTEGER NOT NULL, " +
                            "`position` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                            "FOREIGN KEY(`playlistId`) REFERENCES `local_playlists`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )"
                    )
                    connection.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "`index_local_playlist_entries_playlistId` " +
                            "ON `local_playlist_entries` (`playlistId`)"
                    )
                    connection.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "`index_local_playlist_entries_identity` " +
                            "ON `local_playlist_entries` (`playlistId`, `trackKind`, " +
                            "`serverId`, `userId`, `libraryId`, `itemId`)"
                    )
                    connection.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "`index_local_playlist_entries_position` " +
                            "ON `local_playlist_entries` (`playlistId`, `position`)"
                    )
                }
            }
    }
}
