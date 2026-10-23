package com.zhangwenkang.cinefin.database.music

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPlaylistDaoTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun removeDatabase() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun reopenedDatabase_preservesPlaylistEntryOrder() = runBlocking {
        withDatabase { database ->
            val dao = database.localPlaylistDao()
            dao.insertPlaylist(playlist(PLAYLIST_ID))
            assertTrue(dao.addEntries(entries("a", "b", "c")))
            assertEquals(listOf("a", "b", "c"), dao.getEntries(PLAYLIST_ID).map { it.itemId })
        }

        withDatabase { database ->
            assertEquals(
                listOf("a", "b", "c"),
                database.localPlaylistDao().getEntries(PLAYLIST_ID).map { it.itemId },
            )
        }
    }

    @Test
    fun duplicateTrackRefs_areIgnoredWithinPlaylist() = runBlocking {
        withDatabase { database ->
            val dao = database.localPlaylistDao()
            dao.insertPlaylist(playlist(PLAYLIST_ID))
            val first = entry(id = "entry-a", itemId = SAME_ITEM)
            val duplicate = first.copy(id = "entry-b")

            assertTrue(dao.addEntries(listOf(first, duplicate, duplicate.copy(id = "entry-c"))))

            assertEquals(listOf("entry-a"), dao.getEntries(PLAYLIST_ID).map { it.id })
        }
    }

    @Test
    fun sameItemId_isIsolatedByServerUserAndLocalLibrary() = runBlocking {
        withDatabase { database ->
            val dao = database.localPlaylistDao()
            dao.insertPlaylist(playlist(PLAYLIST_ID))
            val serverA =
                entry(
                    id = "server-a",
                    itemId = SAME_ITEM,
                    serverId = "server-a",
                    userId = USER_A,
                )
            val serverB = serverA.copy(id = "server-b", serverId = "server-b")
            val userB = serverA.copy(id = "user-b", userId = USER_B)
            val localA =
                entry(
                    id = "local-a",
                    itemId = SAME_ITEM,
                    trackKind = "LOCAL",
                    serverId = "",
                    userId = "",
                    libraryId = 7,
                )
            val localB = localA.copy(id = "local-b", libraryId = 8)

            assertTrue(dao.addEntries(listOf(serverA, serverB, userB, localA, localB)))

            assertEquals(5, dao.getEntries(PLAYLIST_ID).size)
        }
    }

    @Test
    fun moveEntry_handlesUniquePositionCollisionAtomically() = runBlocking {
        withDatabase { database ->
            val dao = database.localPlaylistDao()
            dao.insertPlaylist(playlist(PLAYLIST_ID))
            dao.addEntries(entries("a", "b", "c", "d"))
            val last = dao.getEntries(PLAYLIST_ID).last()

            assertTrue(dao.moveEntry(PLAYLIST_ID, last.id, targetPosition = 1))

            assertEquals(
                listOf("entry-a", "entry-d", "entry-b", "entry-c"),
                dao.getEntries(PLAYLIST_ID).map { it.id },
            )
        }
    }

    @Test
    fun illegalMove_doesNotChangeRowsOrOrder() = runBlocking {
        withDatabase { database ->
            val dao = database.localPlaylistDao()
            dao.insertPlaylist(playlist(PLAYLIST_ID))
            dao.addEntries(entries("a", "b", "c"))
            val before = dao.getEntries(PLAYLIST_ID)

            assertFalse(dao.moveEntry(PLAYLIST_ID, before.first().id, targetPosition = -1))
            assertFalse(dao.moveEntry(PLAYLIST_ID, before.first().id, targetPosition = 3))
            assertFalse(dao.moveEntry(PLAYLIST_ID, "missing", targetPosition = 1))

            assertEquals(before, dao.getEntries(PLAYLIST_ID))
        }
    }

    @Test
    fun deletePlaylist_cascadesEntriesButLeavesOtherPlaylistRows() = runBlocking {
        withDatabase { database ->
            val dao = database.localPlaylistDao()
            dao.insertPlaylist(playlist(PLAYLIST_ID))
            dao.insertPlaylist(playlist(OTHER_PLAYLIST_ID))
            dao.addEntries(entries("a", "b"))
            dao.addEntries(
                entries("x").map { it.copy(id = "entry-x", playlistId = OTHER_PLAYLIST_ID) }
            )

            dao.deletePlaylist(PLAYLIST_ID)

            assertTrue(dao.getEntries(PLAYLIST_ID).isEmpty())
            assertEquals(listOf("entry-x"), dao.getEntries(OTHER_PLAYLIST_ID).map { it.id })
        }
    }

    private fun openDatabase(): MusicDatabase =
        Room.databaseBuilder(context, MusicDatabase::class.java, DB_NAME)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(MusicDatabase.MIGRATION_1_2)
            .build()

    private suspend fun <T> withDatabase(block: suspend (MusicDatabase) -> T): T {
        val database = openDatabase()
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun playlist(id: String) =
        LocalPlaylistEntity(id = id, name = "Playlist $id", createdAt = 1, updatedAt = 1)

    private fun entries(vararg itemIds: String) = itemIds.mapIndexed { index, itemId ->
        entry(id = "entry-$itemId", itemId = itemId, position = index)
    }

    private fun entry(
        id: String,
        itemId: String,
        playlistId: String = PLAYLIST_ID,
        trackKind: String = "SERVER",
        serverId: String = "server-a",
        userId: String = USER_A,
        libraryId: Long = -1L,
        position: Int = 0,
    ) =
        LocalPlaylistEntryEntity(
            id = id,
            playlistId = playlistId,
            trackKind = trackKind,
            serverId = serverId,
            userId = userId,
            libraryId = libraryId,
            itemId = itemId,
            displayName = "Track $itemId",
            albumName = "Album",
            artist = "Artist",
            imageUri = null,
            mediaSourceUri = null,
            runtimeTicks = 1,
            position = position,
        )

    private companion object {
        const val DB_NAME = "w87-a-local-playlist-dao.db"
        const val PLAYLIST_ID = "00000000-0000-0000-0000-000000000401"
        const val OTHER_PLAYLIST_ID = "00000000-0000-0000-0000-000000000402"
        const val SAME_ITEM = "00000000-0000-0000-0000-000000000403"
        const val USER_A = "00000000-0000-0000-0000-000000000404"
        const val USER_B = "00000000-0000-0000-0000-000000000405"
    }
}
