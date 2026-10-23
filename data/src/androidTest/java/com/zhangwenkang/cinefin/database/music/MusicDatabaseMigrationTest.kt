package com.zhangwenkang.cinefin.database.music

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicDatabaseMigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper =
        MigrationTestHelper(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            file = File(context.cacheDir, DB_NAME),
            driver = AndroidSQLiteDriver(),
            databaseClass = MusicDatabase::class,
            databaseFactory = {
                Room.databaseBuilder(context, MusicDatabase::class.java, DB_NAME).build()
            },
            autoMigrationSpecs = emptyList(),
        )

    @After
    fun removeDatabase() {
        context.deleteDatabase(DB_NAME)
        File(context.cacheDir, DB_NAME).delete()
    }

    @Test
    fun migrate1To2_preservesQueueStateAndRecentRowsAndCreatesEmptyPlaylistTables() = runBlocking {
        helper.createDatabase(1).use { db ->
            db.execSQL(
                "INSERT INTO music_queue_items " +
                    "(position, itemId, name, mediaSourceId, playbackPositionMs, " +
                    "mediaSourceUri, thumbnailUri, indexNumber) VALUES " +
                    "(0, '00000000-0000-0000-0000-000000000101', 'First', 'source-a', " +
                    "1234, 'https://example.test/first', 'https://example.test/thumb', 3), " +
                    "(1, '00000000-0000-0000-0000-000000000102', 'Second', 'source-b', " +
                    "5678, 'https://example.test/second', NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO music_queue_state " +
                    "(id, currentIndex, positionMs, repeatMode, shuffleEnabled, source, " +
                    "sourceId, savedAt) VALUES " +
                    "(0, 1, 5678, 'ALL', 1, 'PLAYLIST', " +
                    "'00000000-0000-0000-0000-000000000200', 987654321)"
            )
            db.execSQL(
                "INSERT INTO music_recent " +
                    "(itemId, name, albumName, artist, imageUri, runtimeTicks, playedAt) " +
                    "VALUES ('00000000-0000-0000-0000-000000000101', 'First', " +
                    "'Album', 'Artist', NULL, 1800000000, 1700000000000)"
            )
        }

        helper.runMigrationsAndValidate(2, listOf(MusicDatabase.MIGRATION_1_2)).use { db ->
            db.prepare(
                    "SELECT position, itemId, name, mediaSourceId, playbackPositionMs, " +
                        "mediaSourceUri, thumbnailUri, indexNumber " +
                        "FROM music_queue_items ORDER BY position"
                )
                .use { statement ->
                    assertTrue(statement.step())
                    assertEquals(0L, statement.getLong(0))
                    assertEquals("00000000-0000-0000-0000-000000000101", statement.getText(1))
                    assertEquals("First", statement.getText(2))
                    assertEquals("source-a", statement.getText(3))
                    assertEquals(1234L, statement.getLong(4))
                    assertEquals("https://example.test/first", statement.getText(5))
                    assertEquals("https://example.test/thumb", statement.getText(6))
                    assertEquals(3L, statement.getLong(7))

                    assertTrue(statement.step())
                    assertEquals(1L, statement.getLong(0))
                    assertEquals("00000000-0000-0000-0000-000000000102", statement.getText(1))
                    assertEquals("Second", statement.getText(2))
                    assertEquals("source-b", statement.getText(3))
                    assertEquals(5678L, statement.getLong(4))
                    assertEquals("https://example.test/second", statement.getText(5))
                    assertTrue(statement.isNull(6))
                    assertTrue(statement.isNull(7))
                }
            db.prepare(
                    "SELECT id, currentIndex, positionMs, repeatMode, shuffleEnabled, source, " +
                        "sourceId, savedAt FROM music_queue_state"
                )
                .use { statement ->
                    assertTrue(statement.step())
                    assertEquals(0L, statement.getLong(0))
                    assertEquals(1L, statement.getLong(1))
                    assertEquals(5678L, statement.getLong(2))
                    assertEquals("ALL", statement.getText(3))
                    assertEquals(1L, statement.getLong(4))
                    assertEquals("PLAYLIST", statement.getText(5))
                    assertEquals("00000000-0000-0000-0000-000000000200", statement.getText(6))
                    assertEquals(987654321L, statement.getLong(7))
                }
            db.prepare(
                    "SELECT itemId, name, albumName, artist, imageUri, runtimeTicks, playedAt " +
                        "FROM music_recent"
                )
                .use { statement ->
                    assertTrue(statement.step())
                    assertEquals("00000000-0000-0000-0000-000000000101", statement.getText(0))
                    assertEquals("First", statement.getText(1))
                    assertEquals("Album", statement.getText(2))
                    assertEquals("Artist", statement.getText(3))
                    assertTrue(statement.isNull(4))
                    assertEquals(1800000000L, statement.getLong(5))
                    assertEquals(1700000000000L, statement.getLong(6))
                }
            db.prepare("SELECT COUNT(*) FROM local_playlists").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
            db.prepare("SELECT COUNT(*) FROM local_playlist_entries").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
            db.execSQL(
                "INSERT INTO local_playlists (id, name, createdAt, updatedAt) VALUES " +
                    "('00000000-0000-0000-0000-000000000300', 'Local', 1, 2)"
            )
            db.prepare("SELECT name FROM local_playlists").use { statement ->
                assertTrue(statement.step())
                assertEquals("Local", statement.getText(0))
                assertNull(if (statement.step()) "unexpected second playlist" else null)
            }
        }
    }

    private companion object {
        const val DB_NAME = "w87-a-music-migration-test.db"
    }
}
