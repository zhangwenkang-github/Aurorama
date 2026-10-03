package com.zhangwenkang.cinefin.film.presentation.detail

import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import java.util.UUID
import org.jellyfin.sdk.model.api.ItemFields
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
}
