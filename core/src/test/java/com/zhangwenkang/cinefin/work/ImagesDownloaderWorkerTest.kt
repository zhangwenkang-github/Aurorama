package com.zhangwenkang.cinefin.work

import androidx.work.ListenableWorker.Result
import com.zhangwenkang.cinefin.models.FindroidSeasonDto
import com.zhangwenkang.cinefin.models.FindroidShowDto
import com.zhangwenkang.cinefin.utils.ImageDownloadParentKind
import com.zhangwenkang.cinefin.utils.ImageDownloadParentOwner
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagesDownloaderWorkerTest {

    @Test
    fun `父快照fence精确校验show和season所属namespace`() = runBlocking {
        val owner =
            ImageDownloadParentOwner(
                kind = ImageDownloadParentKind.SEASON,
                itemId = SEASON_ID,
                serverId = SERVER_ID,
                userId = USER_ID,
            )
        val getShow: suspend (UUID) -> FindroidShowDto = { show }
        val getSeason: suspend (UUID) -> FindroidSeasonDto = { season }

        assertTrue(isParentSnapshotCurrent(owner, getShow, getSeason))
        assertFalse(
            isParentSnapshotCurrent(
                owner.copy(serverId = "server-b"),
                getShow,
                getSeason,
            )
        )
        assertFalse(
            isParentSnapshotCurrent(
                owner,
                { show.copy(serverId = "server-b") },
                getSeason,
            )
        )
    }

    @Test
    fun `父快照fence传播协程取消`() {
        val owner =
            ImageDownloadParentOwner(
                kind = ImageDownloadParentKind.SHOW,
                itemId = SHOW_ID,
                serverId = SERVER_ID,
                userId = USER_ID,
            )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                isParentSnapshotCurrent(
                    owner = owner,
                    getShow = { throw CancellationException("worker stopped") },
                    getSeason = { error("unused") },
                )
            }
        }
    }

    @Test
    fun `父快照fence传播瞬时数据库异常`() {
        val owner =
            ImageDownloadParentOwner(
                kind = ImageDownloadParentKind.SEASON,
                itemId = SEASON_ID,
                serverId = SERVER_ID,
                userId = USER_ID,
            )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                isParentSnapshotCurrent(
                    owner = owner,
                    getShow = { show },
                    getSeason = { error("database temporarily unavailable") },
                )
            }
        }
    }

    @Test
    fun `父快照正常缺失返回不匹配`() {
        val owner =
            ImageDownloadParentOwner(
                kind = ImageDownloadParentKind.SHOW,
                itemId = SHOW_ID,
                serverId = SERVER_ID,
                userId = USER_ID,
            )

        assertFalse(
            runBlocking {
                isParentSnapshotCurrent(
                    owner = owner,
                    getShow = { throw missingRequiredRow(FindroidShowDto::class.java) },
                    getSeason = { error("unused") },
                )
            }
        )
    }

    @Test
    fun `worker重试边界将正常父行缺失视为成功跳过`() {
        val result = runBlocking {
            runImageDownloadAttempt(runAttemptCount = 0) {
                isParentSnapshotCurrent(
                    owner = showOwner,
                    getShow = { throw missingRequiredRow(FindroidShowDto::class.java) },
                    getSeason = { error("unused") },
                )
                false
            }
        }

        assertEquals(Result.success().toString(), result.toString())
    }

    @Test
    fun `worker重试边界将真实数据库异常交给WorkManager重试`() {
        val result = runBlocking {
            runImageDownloadAttempt(runAttemptCount = 0) {
                isParentSnapshotCurrent(
                    owner = showOwner,
                    getShow = { error("database temporarily unavailable") },
                    getSeason = { error("unused") },
                )
                false
            }
        }

        assertEquals(Result.retry().toString(), result.toString())
    }

    @Test
    fun `worker重试边界不吞取消异常`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                runImageDownloadAttempt(runAttemptCount = 0) {
                    isParentSnapshotCurrent(
                        owner = showOwner,
                        getShow = { throw CancellationException("worker stopped") },
                        getSeason = { error("unused") },
                    )
                    false
                }
            }
        }
    }

    private companion object {
        val SHOW_ID: UUID = UUID(0L, 31L)
        val SEASON_ID: UUID = UUID(0L, 32L)
        const val SERVER_ID = "server-a"
        const val USER_ID = "user-a"
        val showOwner =
            ImageDownloadParentOwner(
                kind = ImageDownloadParentKind.SHOW,
                itemId = SHOW_ID,
                serverId = SERVER_ID,
                userId = USER_ID,
            )

        val show =
            FindroidShowDto(
                id = SHOW_ID,
                serverId = SERVER_ID,
                name = "Show",
                originalTitle = null,
                overview = "",
                runtimeTicks = 0L,
                communityRating = null,
                officialRating = null,
                status = "Continuing",
                productionYear = null,
                endDate = null,
            )
        val season =
            FindroidSeasonDto(
                id = SEASON_ID,
                seriesId = SHOW_ID,
                name = "Season",
                seriesName = "Show",
                overview = "",
                indexNumber = 1,
            )

        fun missingRequiredRow(type: Class<*>): Nothing =
            error(
                "The query result was empty, but expected a single row to return a " +
                    "NON-NULL object of type '${type.name}'."
            )
    }
}
