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
    version = 5,
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

        /** v2 → v3 只扩展队列状态行；所有旧值与歌单 / 最近播放行保持不变。 */
        val MIGRATION_2_3 =
            object : Migration(startVersion = 2, endVersion = 3) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "ALTER TABLE `music_queue_state` ADD COLUMN `shuffleOrder` TEXT"
                    )
                    connection.execSQL(
                        "ALTER TABLE `music_queue_state` ADD COLUMN `shuffleSeed` INTEGER"
                    )
                }
            }

        /**
         * v3 → v4（W5 T5，MUSIC-09/12）：队列条目加分区列、状态行加随机细分列。
         *
         * 全部是带默认值的追加列，旧行自动归为「主队列 + 全随机」，不重建表、不动歌单与最近播放。 迁移**幂等**：语句由
         * [musicQueueMigration3To4Statements] 按现有列裁剪，重复执行不会因为列已存在而失败。
         */
        val MIGRATION_3_4 =
            object : Migration(startVersion = 3, endVersion = 4) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    musicQueueMigration3To4Statements { table -> connection.tableColumns(table) }
                        .forEach { sql -> connection.execSQL(sql) }
                }
            }

        /**
         * v4 → v5（W5 T8，MUSIC-49）：队列条目追加「不可用」标记列。
         *
         * 带默认值的单列追加，旧行自动视为可播；迁移幂等（列已存在则跳过），不重建表。
         */
        val MIGRATION_4_5 =
            object : Migration(startVersion = 4, endVersion = 5) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    musicQueueMigration4To5Statements { table -> connection.tableColumns(table) }
                        .forEach { sql -> connection.execSQL(sql) }
                }
            }
    }
}

/**
 * v3 → v4 的追加列语句（纯函数，可 JVM 单测）。
 *
 * 入参是「读某张表现有列名」的探针；列已存在就跳过，因此迁移可安全重放（幂等）。
 */
internal fun musicQueueMigration3To4Statements(
    existingColumns: (String) -> Set<String>
): List<String> = buildList {
    if ("kind" !in existingColumns("music_queue_items")) {
        add("ALTER TABLE `music_queue_items` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'MAIN'")
    }
    if ("shuffleKeepCurrentFirst" !in existingColumns("music_queue_state")) {
        add(
            "ALTER TABLE `music_queue_state` ADD COLUMN " +
                "`shuffleKeepCurrentFirst` INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/** v4 → v5 的追加列语句（纯函数，可 JVM 单测）：列已存在就跳过，迁移可安全重放。 */
internal fun musicQueueMigration4To5Statements(
    existingColumns: (String) -> Set<String>
): List<String> = buildList {
    if ("unavailable" !in existingColumns("music_queue_items")) {
        add("ALTER TABLE `music_queue_items` ADD COLUMN `unavailable` INTEGER NOT NULL DEFAULT 0")
    }
}

private fun SQLiteConnection.tableColumns(table: String): Set<String> = buildSet {
    prepare("PRAGMA table_info(`$table`)").use { statement ->
        while (statement.step()) {
            add(statement.getText(1))
        }
    }
}
