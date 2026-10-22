package com.zhangwenkang.cinefin.database

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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerDatabaseMigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper =
        MigrationTestHelper(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            file = File(context.cacheDir, DB_NAME),
            driver = AndroidSQLiteDriver(),
            databaseClass = ServerDatabase::class,
            databaseFactory = {
                Room.databaseBuilder(context, ServerDatabase::class.java, DB_NAME).build()
            },
            autoMigrationSpecs = emptyList(),
        )

    @After
    fun removeDatabase() {
        context.deleteDatabase(DB_NAME)
        File(context.cacheDir, DB_NAME).delete()
    }

    @Test
    fun migrate13To14_preservesExistingRowsAndAddsUnknownColumns() = runBlocking {
        helper.createDatabase(13).use { db ->
            db.execSQL(
                "INSERT INTO sources (id, itemId, name, type, path) " +
                    "VALUES ('source-a', '00000000-0000-0000-0000-000000000043', " +
                    "'movie.mkv', 'REMOTE', 'https://example.test/movie')"
            )
            db.execSQL(
                "INSERT INTO mediastreams " +
                    "(id, sourceId, title, displayTitle, language, type, codec, isExternal, path, " +
                    "channelLayout, videoRangeType, height, width, videoDoViTitle, downloadId) " +
                    "VALUES ('stream-a', 'source-a', 'Main', '4K HEVC', 'eng', 'VIDEO', " +
                    "'hevc', 0, '', NULL, 'HDR10', 2160, 3840, NULL, NULL)"
            )
        }

        helper.runMigrationsAndValidate(14).use { db ->
            db.prepare("SELECT COUNT(*) FROM sources").use { statement ->
                assertTrue(statement.step())
                assertEquals(1L, statement.getLong(0))
            }
            db.prepare("SELECT id, itemId, name, type, path, bitrate, container FROM sources")
                .use { statement ->
                    assertTrue(statement.step())
                    assertEquals("source-a", statement.getText(0))
                    assertEquals(
                        "00000000-0000-0000-0000-000000000043",
                        statement.getText(1),
                    )
                    assertEquals("movie.mkv", statement.getText(2))
                    assertEquals("REMOTE", statement.getText(3))
                    assertEquals("https://example.test/movie", statement.getText(4))
                    assertTrue(statement.isNull(5))
                    assertTrue(statement.isNull(6))
                }

            db.prepare(
                    "SELECT id, sourceId, width, height, bitRate, frameRate, bitDepth, " +
                        "channels, sampleRate, profile FROM mediastreams"
                )
                .use { statement ->
                    assertTrue(statement.step())
                    assertEquals("stream-a", statement.getText(0))
                    assertEquals("source-a", statement.getText(1))
                    assertEquals(3840L, statement.getLong(2))
                    assertEquals(2160L, statement.getLong(3))
                    for (index in 4..9) {
                        assertTrue(
                            "new mediastream column $index must be NULL",
                            statement.isNull(index),
                        )
                    }
                }
        }
    }

    private companion object {
        const val DB_NAME = "w86-a-migration-test.db"
    }
}
