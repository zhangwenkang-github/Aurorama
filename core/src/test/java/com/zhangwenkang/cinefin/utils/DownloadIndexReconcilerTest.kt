package com.zhangwenkang.cinefin.utils

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W9 下载索引对账单测：分类（linked / orphanMedia / residual）、体积合计、sidecar 不误判，以及「清理只触碰 orphanMedia
 * 且限定在下载根目录内」的契约。
 */
class DownloadIndexReconcilerTest {

    private val item = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    private val otherItem = "9b2f0a7e-1c11-4d3a-9a55-0d0b7f1e2c33"
    private val linkedTarget = "$item.src1.g2"
    private val orphanTarget = "$otherItem.src1.g0"

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    private fun fileEntry(path: String, size: Long = 0L) = DownloadIndexFileEntry(path, size)

    private fun rowEntry(path: String) = DownloadIndexRowEntry(path)

    private fun report(files: List<DownloadIndexFileEntry>, rows: List<DownloadIndexRowEntry>) =
        DownloadIndexReconciler.reconcile(files = files, rows = rows)

    @Test
    fun `正式文件·残片·witness·旧sidecar有行时全部归入linked`() {
        val report =
            report(
                files =
                    listOf(
                        fileEntry(linkedTarget, 100L),
                        fileEntry("$linkedTarget.download", 40L),
                        fileEntry(".$linkedTarget.finalizing", 1L),
                        fileEntry(".$linkedTarget.js", 2L),
                    ),
                rows = listOf(rowEntry(linkedTarget)),
            )

        assertEquals(4, report.linkedCount)
        assertEquals(143L, report.linkedBytes)
        assertTrue(report.orphanMedia.isEmpty())
        assertEquals(0, report.residualCount)
    }

    @Test
    fun `无下载行的媒体文件归入orphanMedia并合计体积`() {
        val report =
            report(
                files = listOf(fileEntry(orphanTarget, 1_000L), fileEntry(linkedTarget, 5L)),
                rows = listOf(rowEntry(linkedTarget)),
            )

        assertEquals(1, report.linkedCount)
        assertEquals(listOf(DownloadIndexOrphanMedia(orphanTarget, 1_000L)), report.orphanMedia)
        assertEquals(1, report.orphanMediaCount)
        assertEquals(1_000L, report.orphanMediaBytes)
        assertEquals(2, report.scannedCount)
    }

    @Test
    fun `残留代次文件`() {
        val staleGeneration = "$item.src1.g3"
        val report =
            report(
                files = listOf(fileEntry(linkedTarget, 10L), fileEntry(staleGeneration, 20L)),
                rows = listOf(rowEntry(linkedTarget)),
            )

        assertEquals(1, report.linkedCount)
        assertEquals(1, report.orphanMediaCount)
        assertEquals(staleGeneration, report.orphanMedia.single().relativePath)
    }

    @Test
    fun `空目录返回空报告`() {
        val report = report(files = emptyList(), rows = emptyList())

        assertEquals(DownloadIndexReconciliationReport(), report)
        assertEquals(0, report.scannedCount)
    }

    @Test
    fun `仅残片时归入residual且不产生孤儿`() {
        val report =
            report(
                files =
                    listOf(
                        fileEntry("$orphanTarget.download", 300L),
                        fileEntry(".$orphanTarget.finalizing", 1L),
                        fileEntry(".$orphanTarget.js", 2L),
                    ),
                rows = emptyList(),
            )

        assertTrue(report.orphanMedia.isEmpty())
        assertEquals(3, report.residualCount)
        assertEquals(303L, report.residualBytes)
    }

    @Test
    fun `无关联行的sidecar与witness不得误判为孤儿媒体`() {
        val report =
            report(
                files =
                    listOf(
                        fileEntry(".$orphanTarget.finalizing", 1L),
                        fileEntry("$orphanTarget.js", 2L),
                    ),
                rows = emptyList(),
            )

        assertTrue(report.orphanMedia.isEmpty())
        assertEquals(2, report.residualCount)
    }

    @Test
    fun `不认识的文件名归入residual`() {
        val report =
            report(
                files =
                    listOf(
                        fileEntry("download_media.tsv", 12L),
                        fileEntry(".nomedia", 0L),
                        fileEntry("video.mp4", 50L),
                    ),
                rows = emptyList(),
            )

        assertTrue("非约定命名的文件不得成为可删孤儿", report.orphanMedia.isEmpty())
        assertEquals(3, report.residualCount)
        assertEquals(62L, report.residualBytes)
    }

    @Test
    fun `行路径是残片时正式文件与残片都算linked`() {
        val report =
            report(
                files =
                    listOf(fileEntry(linkedTarget, 10L), fileEntry("$linkedTarget.download", 20L)),
                rows = listOf(rowEntry("$linkedTarget.download")),
            )

        assertEquals(2, report.linkedCount)
        assertTrue(report.orphanMedia.isEmpty())
    }

    @Test
    fun `嵌套目录里的媒体文件没有同路径行时同样算孤儿`() {
        val report =
            report(
                files = listOf(fileEntry("sub/$linkedTarget", 10L)),
                rows = listOf(rowEntry(linkedTarget)),
            )

        assertEquals(0, report.linkedCount)
        assertEquals(1, report.orphanMediaCount)
        assertEquals("sub/$linkedTarget", report.orphanMedia.single().relativePath)
    }

    @Test
    fun `relativize按根目录精确前缀归一`() {
        val root = "/storage/emulated/0/Android/data/app/files/downloads"

        assertEquals(
            linkedTarget,
            DownloadIndexReconciler.relativize(root, "$root/$linkedTarget"),
        )
        assertNull(
            "前缀相同但不是子目录不得算命中",
            DownloadIndexReconciler.relativize(root, "$root-2/$linkedTarget"),
        )
        assertNull(
            "外置存储行不属于该根目录",
            DownloadIndexReconciler.relativize(
                root,
                "/storage/1234-5678/Android/data/app/files/downloads/$linkedTarget",
            ),
        )
        assertNull(DownloadIndexReconciler.relativize(root, "$root/../$linkedTarget"))
    }

    @Test
    fun `merge累计计数并按路径排序`() {
        val merged =
            DownloadIndexReconciler.merge(
                listOf(
                    report(files = listOf(fileEntry("b".let { orphanTarget })), rows = emptyList()),
                    DownloadIndexReconciliationReport(
                        linkedCount = 1,
                        linkedBytes = 5L,
                        orphanMedia = listOf(DownloadIndexOrphanMedia("$otherItem.aaa.g0", 7L)),
                        residualCount = 2,
                        residualBytes = 3L,
                    ),
                )
            )

        assertEquals(1, merged.linkedCount)
        assertEquals(2, merged.orphanMediaCount)
        assertEquals(
            listOf("$otherItem.aaa.g0", orphanTarget),
            merged.orphanMedia.map { it.relativePath },
        )
        assertEquals(2, merged.residualCount)
        assertEquals(3L, merged.residualBytes)
    }

    @Test
    fun `清理只删除orphanMedia且在根目录内`() {
        val root = tempDir("cinefin-dl-index-root")
        val linked = File(root, linkedTarget).apply { writeBytes(ByteArray(10)) }
        val orphan = File(root, orphanTarget).apply { writeBytes(ByteArray(120)) }
        val orphanPartial = File(root, "$orphanTarget.download").apply { writeBytes(ByteArray(7)) }
        val orphanWitness =
            File(root, ".$orphanTarget.finalizing").apply { writeBytes(ByteArray(1)) }
        val orphanSidecar = File(root, ".$orphanTarget.js").apply { writeBytes(ByteArray(2)) }
        val unknown = File(root, "download_media.tsv").apply { writeBytes(ByteArray(3)) }
        val outside =
            File(root.parentFile, "outside-${root.name}.mp4").apply { writeBytes(ByteArray(9)) }

        val report =
            report(
                files =
                    listOf(
                        fileEntry(linkedTarget),
                        fileEntry(orphanTarget),
                        fileEntry("$orphanTarget.download"),
                        fileEntry(".$orphanTarget.finalizing"),
                        fileEntry(".$orphanTarget.js"),
                        fileEntry("download_media.tsv"),
                    ),
                rows = listOf(rowEntry(linkedTarget)),
            )
        val requested =
            setOf(
                orphanTarget,
                "$orphanTarget.download",
                ".$orphanTarget.finalizing",
                unknown.name,
                "../${outside.name}",
            )

        val outcome = DownloadIndexReconciler.deleteOrphans(root, report, requested)

        assertEquals(1, outcome.deletedCount)
        assertEquals(120L, outcome.deletedBytes)
        assertEquals(0, outcome.failedCount)
        assertFalse("孤儿媒体应被删除", orphan.exists())
        assertTrue("有关联行的文件不得删除", linked.exists())
        assertTrue("残片默认永不清理", orphanPartial.exists())
        assertTrue("witness 默认永不清理", orphanWitness.exists())
        assertTrue("旧 sidecar 默认永不清理", orphanSidecar.exists())
        assertTrue("不认识的文件名默认永不清理", unknown.exists())
        assertTrue("根目录外文件不得删除", outside.exists())
    }

    @Test
    fun `空请求不删除任何文件`() {
        val root = tempDir("cinefin-dl-index-empty-request")
        val orphan = File(root, orphanTarget).apply { writeBytes(ByteArray(4)) }
        val report = report(files = listOf(fileEntry(orphanTarget)), rows = emptyList())

        val outcome = DownloadIndexReconciler.deleteOrphans(root, report, emptySet())

        assertEquals(DownloadIndexDeletionOutcome(), outcome)
        assertTrue(orphan.exists())
    }

    @Test
    fun `构造报告里的越界或非媒体条目不会被删除`() {
        val root = tempDir("cinefin-dl-index-crafted")
        val regular = File(root, "notes.txt").apply { writeBytes(ByteArray(1)) }
        val craftedReport =
            DownloadIndexReconciliationReport(
                orphanMedia =
                    listOf(
                        DownloadIndexOrphanMedia("../escape.mp4", 1L),
                        DownloadIndexOrphanMedia("notes.txt", 1L),
                    )
            )

        val outcome =
            DownloadIndexReconciler.deleteOrphans(
                root,
                craftedReport,
                setOf("../escape.mp4", "notes.txt"),
            )

        assertEquals(0, outcome.deletedCount)
        assertTrue(regular.exists())
    }

    @Test
    fun `resolveInsideRoot拒绝越界与派生文件`() {
        val root = tempDir("cinefin-dl-index-resolve")
        val media = File(root, orphanTarget).apply { writeBytes(ByteArray(1)) }
        val partial = File(root, "$orphanTarget.download").apply { writeBytes(ByteArray(1)) }

        assertNotNull(DownloadIndexReconciler.resolveInsideRoot(root, orphanTarget))
        assertEquals(
            media.canonicalFile,
            DownloadIndexReconciler.resolveInsideRoot(root, orphanTarget),
        )
        assertNull(DownloadIndexReconciler.resolveInsideRoot(root, "../escape.mp4"))
        assertNull(DownloadIndexReconciler.resolveInsideRoot(root, "$orphanTarget.download"))
        assertTrue(partial.exists())
    }
}
