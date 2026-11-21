package com.zhangwenkang.cinefin.film.presentation.detail

import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadNetworkPrompt
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadPrecheckKind
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.utils.DownloadNetworkDecision
import java.util.UUID
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W51 下载粒度纯函数单测：状态判定 / 批量集选择 / 去重 / 上限。 */
class DetailDownloadRulesTest {

    private val episodeA = UUID.randomUUID()
    private val episodeB = UUID.randomUUID()
    private val episodeC = UUID.randomUUID()
    private val episodeD = UUID.randomUUID()

    @Test
    fun `单集三态_已下载优先于已在队列`() {
        assertEquals(
            DetailDownloadState.DOWNLOADED,
            DetailDownloadRules.stateOf(
                episodeA,
                downloaded = setOf(episodeA),
                queued = emptySet(),
            ),
        )
        assertEquals(
            DetailDownloadState.IN_QUEUE,
            DetailDownloadRules.stateOf(
                episodeA,
                downloaded = emptySet(),
                queued = setOf(episodeA),
            ),
        )
        assertEquals(
            DetailDownloadState.DOWNLOADED,
            DetailDownloadRules.stateOf(
                episodeA,
                downloaded = setOf(episodeA),
                queued = setOf(episodeA),
            ),
        )
        assertEquals(
            DetailDownloadState.NOT_DOWNLOADED,
            DetailDownloadRules.stateOf(episodeA, downloaded = emptySet(), queued = emptySet()),
        )
    }

    @Test
    fun `容器聚合_缺失集优先_全落定后区分队列与已下载`() {
        val ids = listOf(episodeA, episodeB)
        assertEquals(
            DetailDownloadState.NOT_DOWNLOADED,
            DetailDownloadRules.containerState(
                ids,
                downloaded = setOf(episodeA),
                queued = emptySet(),
            ),
        )
        assertEquals(
            DetailDownloadState.IN_QUEUE,
            DetailDownloadRules.containerState(
                ids,
                downloaded = setOf(episodeA),
                queued = setOf(episodeB),
            ),
        )
        assertEquals(
            DetailDownloadState.DOWNLOADED,
            DetailDownloadRules.containerState(
                ids,
                downloaded = setOf(episodeA, episodeB),
                queued = emptySet(),
            ),
        )
        assertEquals(
            DetailDownloadState.NOT_DOWNLOADED,
            DetailDownloadRules.containerState(
                emptyList(),
                downloaded = emptySet(),
                queued = emptySet(),
            ),
        )
    }

    @Test
    fun `批量选择_默认仅补齐缺失集并保持顺序`() {
        val ids = listOf(episodeA, episodeB, episodeC, episodeD)
        val result =
            DetailDownloadRules.selectBatch(
                itemIds = ids,
                downloaded = setOf(episodeB),
                queued = setOf(episodeD),
            )
        assertEquals(listOf(episodeA, episodeC), result.selected)
        assertEquals(1, result.skippedDownloaded)
        assertEquals(1, result.skippedQueued)
        assertEquals(0, result.overflow)
    }

    @Test
    fun `批量选择_默认上限一百_超出计入溢出`() {
        val ids = (1..105).map { UUID.randomUUID() }
        val result =
            DetailDownloadRules.selectBatch(
                itemIds = ids,
                downloaded = emptySet(),
                queued = emptySet(),
                limit = DetailDownloadRules.DEFAULT_BATCH_LIMIT,
            )
        assertEquals(100, result.selected.size)
        assertEquals(5, result.overflow)
        assertEquals(ids.take(100), result.selected)
    }

    @Test
    fun `批量选择_上限为空表示不限`() {
        val ids = (1..150).map { UUID.randomUUID() }
        val result =
            DetailDownloadRules.selectBatch(
                itemIds = ids,
                downloaded = emptySet(),
                queued = emptySet(),
                limit = null,
            )
        assertEquals(150, result.selected.size)
        assertEquals(0, result.overflow)
    }

    @Test
    fun `批量选择_全部已下载或已在队列时没有可加入条目`() {
        val ids = listOf(episodeA, episodeB)
        val downloaded =
            DetailDownloadRules.selectBatch(ids, downloaded = ids.toSet(), queued = emptySet())
        assertTrue(downloaded.selected.isEmpty())
        assertEquals(2, downloaded.skippedDownloaded)
        val queued =
            DetailDownloadRules.selectBatch(ids, downloaded = emptySet(), queued = ids.toSet())
        assertTrue(queued.selected.isEmpty())
        assertEquals(2, queued.skippedQueued)
    }

    @Test
    fun `批量目标_有媒体源即入选_不因CanDownload字段缺失被误杀`() {
        // W51b 真机缺陷：取集只请求 Overview 时，服务器不返回 CanDownload / MediaSources，
        // SDK 把 canDownload 映射成 false → 整剧目标被全部过滤成 0 条。
        val canDownloadMissing = dummyEpisode.copy(canDownload = false)
        val withoutSource = dummyEpisode.copy(sources = emptyList())
        val virtual = dummyEpisode.copy(missing = true)

        val targets =
            DetailDownloadRules.downloadTargets(listOf(canDownloadMissing, withoutSource, virtual))

        assertEquals(listOf(canDownloadMissing.id), targets.map { episode -> episode.id })
    }

    @Test
    fun `取集字段_必须显式请求媒体源与下载权限`() {
        assertTrue(DetailDownloadRules.EPISODE_FETCH_FIELDS.contains(ItemFields.MEDIA_SOURCES))
        assertTrue(DetailDownloadRules.EPISODE_FETCH_FIELDS.contains(ItemFields.CAN_DOWNLOAD))
    }

    // ── W7-T1 下载前置检查 ─────────────────────────────────────────────────────

    private fun episodeWithSize(id: UUID, size: Long): FindroidEpisode =
        dummyEpisode.copy(
            id = id,
            sources = dummyEpisode.sources.map { source -> source.copy(size = size) },
        )

    @Test
    fun `批量大小聚合_只累加已知大小并单独计数未知`() {
        val sized = episodeWithSize(episodeA, 1_500L)
        val unknown = dummyEpisode.copy(id = episodeB)
        val excluded = episodeWithSize(episodeC, 9_000L)

        val summary =
            DetailDownloadRules.summarizeBatchBytes(
                episodes = listOf(sized, unknown, excluded),
                selectedIds = setOf(episodeA, episodeB),
            )

        assertEquals(1_500L, summary.knownBytes)
        assertEquals(1, summary.knownCount)
        assertEquals(1, summary.unknownCount)
        assertEquals(1_500L, summary.sizeBytesOrNull)
        assertTrue(summary.hasUnknown)
    }

    @Test
    fun `批量大小聚合_全部未知时合计返回空值而不是0B`() {
        val summary =
            DetailDownloadRules.summarizeBatchBytes(
                episodes = listOf(dummyEpisode.copy(id = episodeA)),
                selectedIds = setOf(episodeA),
            )

        assertEquals(null, summary.sizeBytesOrNull)
        assertEquals(1, summary.unknownCount)
    }

    @Test
    fun `空间预检_需要大于可用时判定不足`() {
        assertTrue(
            DetailDownloadRules.spaceStatus(requiredBytes = 2_000L, availableBytes = 1_999L)
                .insufficient
        )
        assertTrue(
            !DetailDownloadRules.spaceStatus(requiredBytes = 2_000L, availableBytes = 2_000L)
                .insufficient
        )
        assertTrue(
            !DetailDownloadRules.spaceStatus(requiredBytes = null, availableBytes = 0L).insufficient
        )
    }

    @Test
    fun `空间截断_只选装得下的前N集并保留剩余计数`() {
        val ordered = listOf(episodeA, episodeB, episodeC)
        val sizes = mapOf(episodeA to 600L, episodeB to 600L, episodeC to 600L)

        val plan =
            DetailDownloadRules.fitBatchToSpace(
                orderedIds = ordered,
                sizeOf = { id -> sizes[id] },
                availableBytes = 1_500L,
            )

        assertEquals(listOf(episodeA, episodeB), plan.selected)
        assertEquals(1, plan.deferred)
        assertEquals(1_200L, plan.requiredBytes)
        assertTrue(plan.truncated)
    }

    @Test
    fun `空间截断_未知大小的集不阻断后续已知大小条目`() {
        val ordered = listOf(episodeA, episodeB)
        val sizes = mapOf<UUID, Long?>(episodeA to null, episodeB to 400L)

        val plan =
            DetailDownloadRules.fitBatchToSpace(
                orderedIds = ordered,
                sizeOf = { id -> sizes[id] },
                availableBytes = 500L,
            )

        assertEquals(listOf(episodeA, episodeB), plan.selected)
        assertEquals(0, plan.deferred)
        assertEquals(400L, plan.requiredBytes)
    }

    @Test
    fun `移动数据提示_仅WiFi策略命中时要求确认`() {
        assertEquals(
            DownloadNetworkPrompt.NONE,
            DetailDownloadRules.networkPrompt(DownloadNetworkDecision.ALLOW),
        )
        assertEquals(
            DownloadNetworkPrompt.CONFIRM_MOBILE_DATA,
            DetailDownloadRules.networkPrompt(DownloadNetworkDecision.WAIT_FOR_MOBILE_DATA),
        )
        assertEquals(
            DownloadNetworkPrompt.CONFIRM_MOBILE_DATA,
            DetailDownloadRules.networkPrompt(DownloadNetworkDecision.WAIT_FOR_ROAMING),
        )
    }

    @Test
    fun `视频前置内容_带原始文件标注与外部字幕提示`() {
        val stream = dummyEpisode.sources.first().mediaStreams.first()
        val episode =
            dummyEpisode.copy(
                sources =
                    listOf(
                        dummyEpisode.sources
                            .first()
                            .copy(
                                size = 4_000L,
                                container = "mkv",
                                mediaStreams =
                                    listOf(
                                        stream.copy(
                                            type = MediaStreamType.SUBTITLE,
                                            isExternal = true,
                                        )
                                    ),
                            )
                    )
            )

        val content = DetailDownloadRules.videoPrecheckContent(episode)

        assertEquals(DownloadPrecheckKind.VIDEO, content?.kind)
        assertEquals(4_000L, content?.sizeBytes)
        assertEquals("MKV", content?.formatLabel)
        assertTrue(content?.hasSidecarFiles == true)
    }

    @Test
    fun `源格式标签_容器优先_缺失时回退扩展名`() {
        val source = dummyEpisode.sources.first()
        assertEquals("FLAC", DetailDownloadRules.formatLabelFor(source.copy(container = "flac")))
        assertEquals(
            "MP4",
            DetailDownloadRules.formatLabelFor(
                source.copy(container = null, path = "/media/a/01.mp4")
            ),
        )
        assertEquals(
            null,
            DetailDownloadRules.formatLabelFor(source.copy(container = null, path = "no-ext")),
        )
    }

    // ── W7-T1b 媒体库多选批量前置检查 ─────────────────────────────────────────

    private fun movieWithSize(id: UUID, size: Long) =
        dummyMovie.copy(
            id = id,
            sources = dummyMovie.sources.map { source -> source.copy(size = size) },
        )

    /** 书籍 / 容器形态：没有任何媒体源（大小未知，按「可入队、不计空间」处理）。 */
    private fun itemWithoutSource(id: UUID) = dummyMovie.copy(id = id, sources = emptyList())

    @Test
    fun `混选批量大小聚合_电影与单集累加_无媒体源条目计入未知`() {
        val summary =
            DetailDownloadRules.summarizeItemsBytes(
                listOf(
                    movieWithSize(episodeA, 2_000L),
                    episodeWithSize(episodeB, 1_500L),
                    itemWithoutSource(episodeC),
                )
            )

        assertEquals(3_500L, summary.knownBytes)
        assertEquals(2, summary.knownCount)
        assertEquals(1, summary.unknownCount)
        assertEquals(3_500L, summary.sizeBytesOrNull)
        assertTrue(summary.hasUnknown)
    }

    @Test
    fun `混选批量大小聚合_全部未知时合计返回空值而不是0B`() {
        val summary =
            DetailDownloadRules.summarizeItemsBytes(
                listOf(
                    itemWithoutSource(episodeA),
                    dummyEpisode.copy(id = episodeB, sources = emptyList()),
                )
            )

        assertEquals(null, summary.sizeBytesOrNull)
        assertEquals(0, summary.knownCount)
        assertEquals(2, summary.unknownCount)
    }

    @Test
    fun `批量前置检查_空间不足时只保留装得下的前N项并保持原选择顺序`() {
        val snapshot =
            DetailDownloadRules.mediaBatchPrecheck(
                items =
                    listOf(
                        movieWithSize(episodeA, 600L),
                        episodeWithSize(episodeB, 600L),
                        movieWithSize(episodeC, 600L),
                    ),
                availableBytes = 1_500L,
                title = "Download",
            )

        assertEquals(listOf(episodeA, episodeB), snapshot.items.map { item -> item.id })
        assertEquals(1, snapshot.deferredCount)
        assertEquals(1_200L, snapshot.requiredBytes)
        assertTrue(snapshot.truncated)
        assertTrue(!snapshot.blocked)
        assertTrue(!snapshot.space.insufficient)
        assertEquals(DownloadPrecheckKind.GENERIC_BATCH, snapshot.content.kind)
        // 明细行 = 用户所选（3 项 / 共约 1.8 KB）；截断说明另说子集。
        assertEquals(3, snapshot.content.itemCount)
        assertEquals(1_800L, snapshot.content.sizeBytes)
    }

    @Test
    fun `批量前置检查_一项都装不下时判定阻塞并禁用确认`() {
        val snapshot =
            DetailDownloadRules.mediaBatchPrecheck(
                items = listOf(movieWithSize(episodeA, 2_000L), movieWithSize(episodeB, 2_000L)),
                availableBytes = 1_000L,
                title = "Download",
            )

        assertTrue(snapshot.blocked)
        assertTrue(snapshot.items.isEmpty())
        assertEquals(2, snapshot.deferredCount)
        assertTrue(snapshot.space.insufficient)
    }
}
