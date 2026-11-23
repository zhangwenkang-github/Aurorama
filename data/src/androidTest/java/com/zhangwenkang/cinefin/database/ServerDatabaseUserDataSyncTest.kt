package com.zhangwenkang.cinefin.database

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhangwenkang.cinefin.models.FindroidSeasonDto
import com.zhangwenkang.cinefin.models.FindroidShowDto
import com.zhangwenkang.cinefin.models.FindroidUserDataDto
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W9-T4：补同步扫描集的 SQL 边界（W8-T1r 只扫 movies/episodes 的残留收口）。
 *
 * 季表没有 serverId 列（归属经剧）、排队行要能全量列出、无投影条目要能被识别——三条都在真库上钉住。
 */
@RunWith(AndroidJUnit4::class)
class ServerDatabaseUserDataSyncTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: ServerDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
        database = Room.databaseBuilder(context, ServerDatabase::class.java, DB_NAME).build()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun seasonsAreScopedByOwningShowServer() = runBlocking {
        val dao = database.getServerDatabaseDao()
        dao.insertShow(show(SHOW_A, SERVER_A))
        dao.insertShow(show(SHOW_B, SERVER_B))
        dao.insertSeason(season(SEASON_A2, SHOW_A, indexNumber = 2))
        dao.insertSeason(season(SEASON_B1, SHOW_B, indexNumber = 1))
        dao.insertSeason(season(SEASON_A1, SHOW_A, indexNumber = 1))

        val seasons = dao.getSeasonsByServerId(SERVER_A)

        assertEquals(listOf(SEASON_A1, SEASON_A2), seasons.map { it.id })
    }

    @Test
    fun queuedRowsListOnlyPendingOnesForUser() = runBlocking {
        val dao = database.getServerDatabaseDao()
        dao.insertUserData(userData(SHOW_ID, toBeSynced = true))
        dao.insertUserData(userData(SEASON_ID, toBeSynced = false))
        dao.insertUserData(userData(AUDIO_ID, toBeSynced = true))

        val queued = dao.getUserDataToBeSynced(USER_ID)

        assertEquals(setOf(SHOW_ID, AUDIO_ID), queued.map { it.itemId }.toSet())
        assertNotNull(dao.getUserDataToBeSynced(USER_ID, AUDIO_ID))
        assertNull(dao.getUserDataToBeSynced(USER_ID, SEASON_ID))
    }

    @Test
    fun mirroredItemsAreProjectionRowsOnly() = runBlocking {
        val dao = database.getServerDatabaseDao()
        dao.insertShow(show(SHOW_ID, SERVER_A))
        dao.insertSeason(season(SEASON_ID, SHOW_ID, indexNumber = 1))

        assertTrue(dao.hasUserDataMirror(SHOW_ID))
        assertTrue(dao.hasUserDataMirror(SEASON_ID))
        assertFalse(dao.hasUserDataMirror(AUDIO_ID))
    }

    private fun show(id: UUID, serverId: String) =
        FindroidShowDto(
            id = id,
            serverId = serverId,
            name = "show-$id",
            originalTitle = null,
            overview = "",
            runtimeTicks = 0L,
            communityRating = null,
            officialRating = null,
            status = "",
            productionYear = null,
            endDate = null,
        )

    private fun season(id: UUID, seriesId: UUID, indexNumber: Int) =
        FindroidSeasonDto(
            id = id,
            seriesId = seriesId,
            name = "season-$indexNumber",
            seriesName = "show-$seriesId",
            overview = "",
            indexNumber = indexNumber,
        )

    private fun userData(itemId: UUID, toBeSynced: Boolean) =
        FindroidUserDataDto(
            userId = USER_ID,
            itemId = itemId,
            played = false,
            favorite = true,
            playbackPositionTicks = 0L,
            toBeSynced = toBeSynced,
        )

    private companion object {
        const val DB_NAME = "user-data-sync-test.db"
        const val SERVER_A = "server-a"
        const val SERVER_B = "server-b"
        val USER_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val SHOW_A: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val SHOW_B: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val SEASON_A1: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val SEASON_A2: UUID = UUID.fromString("55555555-5555-4555-8555-555555555555")
        val SEASON_B1: UUID = UUID.fromString("66666666-6666-4666-8666-666666666666")
        val SHOW_ID: UUID = UUID.fromString("77777777-7777-4777-8777-777777777777")
        val SEASON_ID: UUID = UUID.fromString("88888888-8888-4888-8888-888888888888")
        val AUDIO_ID: UUID = UUID.fromString("99999999-9999-4999-8999-999999999999")
    }
}
