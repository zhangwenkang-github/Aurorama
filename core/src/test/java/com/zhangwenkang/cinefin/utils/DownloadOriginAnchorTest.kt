package com.zhangwenkang.cinefin.utils

import com.zhangwenkang.cinefin.models.DownloadIntentDto
import com.zhangwenkang.cinefin.models.DownloadIntentStatus
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadOriginAnchorTest {

    @Test
    fun `wrong namespace source and pending stay legacy while trusted anchors remain visible`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            val sourceItemId = UUID.fromString("10101010-1010-4010-8010-101010101010")
            val pendingItemId = UUID.fromString("20202020-2020-4020-8020-202020202020")
            val sourceId = "anchor-source"
            try {
                fixture.seedAnchoredSource(sourceItemId, sourceId)
                fixture.putMusicRecord(
                    musicRecord(
                        itemId = sourceItemId,
                        sourceId = sourceId,
                        serverId = "server-wrong",
                        userId = OTHER_USER_ID,
                        placement =
                            DownloadPlacement(
                                batchId = "wrong-source-batch",
                                policy = ContainerPolicy.SHOW,
                                workKey = "wrong-source-work",
                            ),
                        albumName = "Wrong Album",
                        artist = "Wrong Artist",
                    )
                )
                fixture.seedAnchoredIntent(pendingItemId, "pending anchor")
                fixture.putMusicRecord(
                    musicRecord(
                        itemId = pendingItemId,
                        sourceId = null,
                        serverId = "server-wrong",
                        userId = OTHER_USER_ID,
                        placement =
                            DownloadPlacement(
                                batchId = "wrong-pending-batch",
                                policy = ContainerPolicy.ALBUM_BATCH,
                                workKey = "wrong-pending-work",
                            ),
                        albumName = "Wrong Pending Album",
                        artist = "Wrong Pending Artist",
                    )
                )

                val tasks = fixture.downloader.refreshDownloadTasks().associateBy { it.itemId }
                val sourceTask = tasks.getValue(sourceItemId)
                assertEquals(DownloadPlacement.LEGACY, sourceTask.placement)
                assertEquals(DownloadMediaKind.VIDEO, sourceTask.mediaKind)
                assertNull(sourceTask.albumName)
                assertNull(sourceTask.artist)
                assertEquals(SERVER_ID, sourceTask.serverId)
                assertEquals(USER_ID.toString(), sourceTask.userId)

                val pendingTask = tasks.getValue(pendingItemId)
                assertEquals(DownloadPlacement.LEGACY, pendingTask.placement)
                assertEquals("Anchor Album", pendingTask.albumName)
                assertEquals("Anchor Artist", pendingTask.artist)
                assertEquals(SERVER_ID, pendingTask.serverId)
                assertEquals(USER_ID.toString(), pendingTask.userId)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `pending exact selection requires music and ignores exact video beside music`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            val wrongOnlyItemId = UUID.fromString("11111111-1111-4111-8111-111111111111")
            val mixedItemId = UUID.fromString("12121212-1212-4212-8212-121212121212")
            val musicPlacement =
                DownloadPlacement(
                    batchId = "pending-music-batch",
                    policy = ContainerPolicy.ALBUM_BATCH,
                    workKey = "pending-music-work",
                )
            try {
                fixture.seedAnchoredIntent(wrongOnlyItemId, "wrong kind only")
                fixture.putMusicRecord(
                    musicRecord(
                            itemId = wrongOnlyItemId,
                            sourceId = null,
                            serverId = SERVER_ID,
                            userId = USER_ID,
                            placement =
                                DownloadPlacement(
                                    batchId = "wrong-kind-batch",
                                    policy = ContainerPolicy.SHOW,
                                    workKey = "wrong-kind-work",
                                ),
                        )
                        .copy(kind = DownloadMediaKind.VIDEO)
                )

                fixture.seedAnchoredIntent(mixedItemId, "music beside video")
                fixture.putMusicRecord(
                    musicRecord(
                            itemId = mixedItemId,
                            sourceId = null,
                            serverId = SERVER_ID,
                            userId = USER_ID,
                            placement =
                                DownloadPlacement(
                                    batchId = "video-batch",
                                    policy = ContainerPolicy.SHOW,
                                    workKey = "video-work",
                                ),
                        )
                        .copy(kind = DownloadMediaKind.VIDEO)
                )
                fixture.putMusicRecord(
                    musicRecord(
                        itemId = mixedItemId,
                        sourceId = null,
                        serverId = SERVER_ID,
                        userId = USER_ID,
                        placement = musicPlacement,
                    )
                )

                val tasks = fixture.downloader.refreshDownloadTasks().associateBy { it.itemId }
                val wrongOnlyTask = tasks.getValue(wrongOnlyItemId)
                assertEquals(DownloadPlacement.LEGACY, wrongOnlyTask.placement)

                val mixedTask = tasks.getValue(mixedItemId)
                assertEquals(DownloadMediaKind.MUSIC, mixedTask.mediaKind)
                assertEquals(musicPlacement, mixedTask.placement)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `only unique exact record supplies all placement fields`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        val itemId = UUID.fromString("30303030-3030-4030-8030-303030303030")
        val sourceId = "anchor-exact-source"
        val exactPlacement =
            DownloadPlacement(
                batchId = "exact-batch",
                policy = ContainerPolicy.ALBUM_BATCH,
                workKey = "exact-work",
            )
        try {
            fixture.seedAnchoredSource(itemId, sourceId)
            fixture.putMusicRecord(
                musicRecord(
                    itemId = itemId,
                    sourceId = sourceId,
                    serverId = SERVER_ID,
                    userId = USER_ID,
                    placement = exactPlacement,
                    albumName = "Exact Album",
                    artist = "Exact Artist",
                )
            )
            fixture.putMusicRecord(
                musicRecord(
                    itemId = itemId,
                    sourceId = sourceId,
                    serverId = "server-wrong",
                    userId = OTHER_USER_ID,
                    placement =
                        DownloadPlacement(
                            batchId = "wrong-batch",
                            policy = ContainerPolicy.SHOW,
                            workKey = "wrong-work",
                        ),
                    albumName = "Wrong Album",
                    artist = "Wrong Artist",
                )
            )

            val task =
                fixture.downloader.refreshDownloadTasks().single {
                    it.itemId == itemId && it.sourceId == sourceId
                }

            assertEquals(exactPlacement, task.placement)
            assertEquals("Exact Album", task.albumName)
            assertEquals("Exact Artist", task.artist)
            assertEquals(DownloadMediaKind.MUSIC, task.mediaKind)
            assertEquals(SERVER_ID, task.serverId)
            assertEquals(USER_ID.toString(), task.userId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `multiple exact sidecars fail closed`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        val itemId = UUID.fromString("40404040-4040-4040-8040-404040404040")
        val sourceId = "anchor-multiple-exact"
        try {
            fixture.seedAnchoredSource(itemId, sourceId)
            val first =
                musicRecord(
                    itemId = itemId,
                    sourceId = sourceId,
                    serverId = SERVER_ID,
                    userId = USER_ID,
                    placement =
                        DownloadPlacement(
                            batchId = "first",
                            policy = ContainerPolicy.SHOW,
                            workKey = "first",
                        ),
                )
            val second = first.copy(placement = first.placement.copy(batchId = "second"))
            fixture
                .sidecarFile()
                .writeText(
                    listOf(first, second)
                        .joinToString("\n", transform = DownloadMediaCodec::encodeLine)
                )

            val task =
                fixture.downloader.refreshDownloadTasks().single {
                    it.itemId == itemId && it.sourceId == sourceId
                }

            assertEquals(DownloadPlacement.LEGACY, task.placement)
            assertNull(task.albumName)
            assertNull(task.artist)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `missing partial corrupt and unreadable sidecars all fail closed`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        val itemId = UUID.fromString("50505050-5050-4050-8050-505050505050")
        val sourceId = "anchor-unavailable-source"
        try {
            fixture.seedAnchoredSource(itemId, sourceId)
            assertLegacy(fixture, itemId, sourceId)

            fixture
                .sidecarFile()
                .writeText(
                    DownloadMediaCodec.encodeLine(
                        DownloadMediaRecord(
                            itemId = itemId.toString(),
                            kind = DownloadMediaKind.MUSIC,
                            serverId = SERVER_ID,
                            userId = null,
                            sourceId = sourceId,
                            placement =
                                DownloadPlacement(
                                    batchId = "partial",
                                    policy = ContainerPolicy.FLAT,
                                    workKey = "partial",
                                ),
                        )
                    )
                )
            assertLegacy(fixture, itemId, sourceId)

            fixture.sidecarFile().writeText("not-a-sidecar-record")
            assertLegacy(fixture, itemId, sourceId)

            assertTrue(fixture.sidecarFile().delete())
            assertTrue(fixture.sidecarFile().mkdirs())
            assertLegacy(fixture, itemId, sourceId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy null anchors never borrow the current session or self proved sidecars`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            val sourceItemId = UUID.fromString("60606060-6060-4060-8060-606060606060")
            val pendingItemId = UUID.fromString("70707070-7070-4070-8070-707070707070")
            val sourceId = "anchor-legacy-source"
            try {
                fixture.dao.putSource(
                    fixture
                        .seedPendingSource(sourceItemId, sourceId)
                        .copy(originServerId = null, originUserId = null)
                )
                fixture.putMusicRecord(
                    musicRecord(
                        itemId = sourceItemId,
                        sourceId = sourceId,
                        serverId = SERVER_ID,
                        userId = USER_ID,
                        placement =
                            DownloadPlacement(
                                batchId = "legacy-source",
                                policy = ContainerPolicy.SHOW,
                                workKey = "legacy-source",
                            ),
                    )
                )
                fixture.dao.putDownloadIntent(
                    DownloadIntentDto(
                        itemId = pendingItemId,
                        name = "legacy pending",
                        albumName = "Legacy Album",
                        artist = "Legacy Artist",
                        trackIndex = 0,
                        status = DownloadIntentStatus.RECEIVED.name,
                        createdAt = 1L,
                        updatedAt = 1L,
                        taskGeneration = 3L,
                    )
                )
                fixture.putMusicRecord(
                    musicRecord(
                        itemId = pendingItemId,
                        sourceId = null,
                        serverId = SERVER_ID,
                        userId = USER_ID,
                        placement =
                            DownloadPlacement(
                                batchId = "legacy-pending",
                                policy = ContainerPolicy.ALBUM_BATCH,
                                workKey = "legacy-pending",
                            ),
                    )
                )
                fixture.resetCurrentOriginReads()

                val tasks = fixture.downloader.refreshDownloadTasks().associateBy { it.itemId }
                assertEquals(DownloadPlacement.LEGACY, tasks.getValue(sourceItemId).placement)
                assertNull(tasks.getValue(sourceItemId).serverId)
                assertNull(tasks.getValue(sourceItemId).userId)
                assertEquals(DownloadPlacement.LEGACY, tasks.getValue(pendingItemId).placement)
                assertNull(tasks.getValue(pendingItemId).serverId)
                assertNull(tasks.getValue(pendingItemId).userId)
                fixture.assertCurrentOriginReadsZero()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `new pending and direct source rows persist frozen anchors and pending supersedes in place`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            val itemId = UUID.fromString("80808080-8080-4080-8080-808080808080")
            val serverB = "server-b"
            val userB = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
            try {
                fixture.prepareMusicOrigin(itemId, "anchor supersede")
                val first =
                    fixture
                        .completeMusicRequest(itemId, "anchor supersede")
                        .copy(serverId = SERVER_ID, userId = USER_ID.toString(), sourceId = null)
                val second =
                    first.copy(
                        serverId = serverB,
                        userId = userB.toString(),
                        placement =
                            DownloadPlacement(
                                batchId = "server-b",
                                policy = ContainerPolicy.ALBUM_BATCH,
                                workKey = "server-b",
                            ),
                    )

                val receipt = fixture.downloader.receiveMusicBatch(listOf(first, second))
                assertEquals(2, receipt.acceptedRequests.size)
                val persisted = checkNotNull(fixture.dao.downloadIntent(itemId))
                assertEquals(serverB, persisted.originServerId)
                assertEquals(userB.toString(), persisted.originUserId)
                assertEquals(1L, persisted.taskGeneration)

                fixture.downloader.downloadItem(fixture.movie(), SOURCE_ID, 0)
                val source = checkNotNull(fixture.dao.lastInsertedSource(SOURCE_ID))
                assertEquals(SERVER_ID, source.originServerId)
                assertEquals(USER_ID.toString(), source.originUserId)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `sidecar written before Room anchor cannot become accepted or resurrect a task`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            val itemId = UUID.fromString("90909090-9090-4090-8090-909090909090")
            try {
                fixture.dao.interruptDownloadIntentSaves(1)
                val failure = runCatching {
                    fixture.downloader.receiveMusicBatch(
                        listOf(fixture.completeMusicRequest(itemId, "orphan sidecar"))
                    )
                }
                assertTrue(failure.isFailure)
                assertEquals(0, fixture.dao.intentCount())
                assertTrue(
                    fixture.downloader.mediaSidecar().allRecords().requireComplete().any {
                        it.itemId == itemId.toString() && it.sourceId.isNullOrBlank()
                    }
                )

                val restarted = fixture.newDownloader()
                restarted.runIntentQueue()
                restarted.recoverOnStartup()
                assertNull(fixture.dao.downloadIntent(itemId))
                assertTrue(restarted.refreshDownloadTasks().none { it.itemId == itemId })
                assertEquals(0, fixture.dao.sourceInsertCount("orphan-source"))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `source row committed before source created cas is reconciled from source anchor and exact sidecar`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            val itemId = UUID.fromString("a0a0a0a0-a0a0-40a0-80a0-a0a0a0a0a0a0")
            val sourceId = "reconcile-source-anchor"
            try {
                fixture.prepareMusicOrigin(itemId, "anchor reconcile", sourceId)
                fixture.seedAnchoredIntent(itemId, "anchor reconcile")
                assertTrue(
                    fixture.putMusicRecord(
                        musicRecord(
                            itemId = itemId,
                            sourceId = null,
                            serverId = SERVER_ID,
                            userId = USER_ID,
                            placement =
                                DownloadPlacement(
                                    batchId = "reconcile",
                                    policy = ContainerPolicy.ALBUM_BATCH,
                                    workKey = "reconcile",
                                ),
                        )
                    )
                )
                fixture.armIntentSourceCreatedCasGate()
                val oldWorker =
                    async(Dispatchers.IO) { runCatching { fixture.downloader.runIntentQueue() } }
                fixture.awaitIntentSourceCreatedCas()
                val source = checkNotNull(fixture.dao.sourceById(sourceId))
                assertEquals(SERVER_ID, source.originServerId)
                assertEquals(USER_ID.toString(), source.originUserId)
                assertEquals(
                    DownloadIntentStatus.RESOLVING.name,
                    fixture.dao.downloadIntent(itemId)?.status,
                )

                val restarted = fixture.newDownloader()
                restarted.runIntentQueue()

                assertEquals(1, fixture.dao.sourceInsertCount(sourceId))
                assertTrue(
                    restarted.refreshDownloadTasks().any {
                        it.itemId == itemId && it.sourceId == sourceId
                    }
                )
                fixture.releaseIntentSourceCreatedCas()
                oldWorker.await()
                Unit
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `wrong sidecar cannot reconcile a committed source row`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        val itemId = UUID.fromString("b0b0b0b0-b0b0-40b0-80b0-b0b0b0b0b0b0")
        val sourceId = "reconcile-wrong-sidecar"
        try {
            fixture.seedAnchoredSource(itemId, sourceId, generation = 4L)
            fixture.seedAnchoredIntent(
                itemId,
                "wrong sidecar reconcile",
                generation = 4L,
                status = DownloadIntentStatus.RESOLVING,
            )
            fixture.putMusicRecord(
                musicRecord(
                    itemId = itemId,
                    sourceId = sourceId,
                    serverId = "server-wrong",
                    userId = OTHER_USER_ID,
                )
            )

            val restarted = fixture.newDownloader()
            restarted.runIntentQueue()

            assertNotNull(fixture.dao.sourceById(sourceId))
            assertTrue(
                fixture.dao.downloadIntent(itemId)?.status !=
                    DownloadIntentStatus.SOURCE_CREATED.name
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `same item different source ids stay independent and active source reuse rejects anchor mismatch`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            val itemId = UUID.fromString("c0c0c0c0-c0c0-40c0-80c0-c0c0c0c0c0c0")
            val firstSourceId = "anchor-independent-a"
            val secondSourceId = "anchor-independent-b"
            val serverB = "server-b"
            val userB = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
            try {
                fixture.seedAnchoredSource(itemId, firstSourceId)
                fixture.seedAnchoredSource(
                    itemId,
                    secondSourceId,
                    serverId = serverB,
                    userId = userB,
                )
                fixture.putMusicRecord(
                    musicRecord(
                        itemId = itemId,
                        sourceId = firstSourceId,
                        serverId = SERVER_ID,
                        userId = USER_ID,
                        placement =
                            DownloadPlacement(
                                batchId = "a",
                                policy = ContainerPolicy.ALBUM_BATCH,
                                workKey = "a",
                            ),
                    )
                )
                fixture.putMusicRecord(
                    musicRecord(
                        itemId = itemId,
                        sourceId = secondSourceId,
                        serverId = serverB,
                        userId = userB,
                        placement =
                            DownloadPlacement(
                                batchId = "b",
                                policy = ContainerPolicy.FLAT,
                                workKey = "b",
                            ),
                    )
                )

                val tasks =
                    fixture.downloader.refreshDownloadTasks().associateBy(DownloadTask::sourceId)
                assertEquals("a", tasks.getValue(firstSourceId).placement.batchId)
                assertEquals("b", tasks.getValue(secondSourceId).placement.batchId)
                assertEquals(SERVER_ID, tasks.getValue(firstSourceId).serverId)
                assertEquals(serverB, tasks.getValue(secondSourceId).serverId)

                fixture.preferences.currentServer = serverB
                fixture.repository.userId = userB
                val before = checkNotNull(fixture.dao.sourceById(firstSourceId))
                val mismatch = runCatching {
                    fixture.downloader.downloadItem(
                        fixture.movie().copy(id = itemId),
                        firstSourceId,
                        0,
                    )
                }
                assertTrue(mismatch.isFailure)
                val after = checkNotNull(fixture.dao.sourceById(firstSourceId))
                assertEquals(before.originServerId, after.originServerId)
                assertEquals(before.originUserId, after.originUserId)
                assertEquals(before.taskGeneration, after.taskGeneration)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `retry cancel and interrupted delete preserve the original anchor`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        val itemId = UUID.fromString("d0d0d0d0-d0d0-40d0-80d0-d0d0d0d0d0d0")
        val sourceId = "anchor-lifecycle-source"
        try {
            fixture.seedAnchoredSource(itemId, sourceId)
            val stale = fixture.downloader.refreshDownloadTasks().single { it.sourceId == sourceId }
            assertTrue(fixture.downloader.cancelTask(stale))
            val cancelled = checkNotNull(fixture.dao.sourceById(sourceId))
            assertEquals(SERVER_ID, cancelled.originServerId)
            assertEquals(USER_ID.toString(), cancelled.originUserId)
            assertEquals(stale.taskGeneration + 1L, cancelled.taskGeneration)

            fixture.downloader.retryTask(stale)
            val afterStaleRetry = checkNotNull(fixture.dao.sourceById(sourceId))
            assertEquals(cancelled.taskGeneration, afterStaleRetry.taskGeneration)
            assertEquals(SERVER_ID, afterStaleRetry.originServerId)
            assertEquals(USER_ID.toString(), afterStaleRetry.originUserId)

            val blockedPath = java.io.File(afterStaleRetry.path)
            blockedPath.delete()
            assertTrue(blockedPath.mkdirs())
            blockedPath.resolve("blocked").writeText("hold")
            runCatching { fixture.downloader.deleteTaskById(sourceId) }
            val deleting = checkNotNull(fixture.dao.sourceById(sourceId))
            assertEquals(DownloadTaskStatus.DELETING.name, deleting.taskStatus)
            assertEquals(SERVER_ID, deleting.originServerId)
            assertEquals(USER_ID.toString(), deleting.originUserId)
        } finally {
            fixture.close()
        }
    }

    private fun assertLegacy(
        fixture: DownloaderImplRecoveryTest.Fixture,
        itemId: UUID,
        sourceId: String,
    ) = runBlocking {
        val task =
            fixture.downloader.refreshDownloadTasks().single {
                it.itemId == itemId && it.sourceId == sourceId
            }
        assertEquals(DownloadPlacement.LEGACY, task.placement)
    }

    private fun musicRecord(
        itemId: UUID,
        sourceId: String?,
        serverId: String,
        userId: UUID,
        placement: DownloadPlacement = DownloadPlacement.LEGACY,
        albumName: String? = "Album",
        artist: String? = "Artist",
    ): DownloadMediaRecord =
        DownloadMediaRecord(
            itemId = itemId.toString(),
            kind = DownloadMediaKind.MUSIC,
            albumName = albumName,
            artist = artist,
            serverId = serverId,
            userId = userId.toString(),
            sourceId = sourceId,
            placement = placement,
        )

    private companion object {
        val USER_ID: UUID = UUID(0L, 12L)
        val OTHER_USER_ID: UUID = UUID(0L, 16L)
        const val SERVER_ID = "server-a"
        const val SOURCE_ID = "direct-anchor-source"
    }
}
