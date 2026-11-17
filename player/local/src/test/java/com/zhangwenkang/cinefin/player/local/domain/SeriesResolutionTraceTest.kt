package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

class SeriesResolutionTraceTest {
    private val seriesId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val seasonOneId = UUID.fromString("22222222-2222-4222-8222-222222222222")

    @Test
    fun `series top play emits safe next up episode and source trace`() = runBlocking {
        val episode = episode(seasonOneId, parentIndexNumber = 1, indexNumber = 3)
        val sourcePath = "https://example.invalid/secret-token/master.m3u8"
        val logs = captureTimber {
            val repository =
                seriesRepository(
                    nextUp = { listOf(episode) },
                    episodes = { seasonId -> listOf(episode.copy(seasonId = seasonId)) },
                    sources = { listOf(source(path = sourcePath, container = "hls")) },
                )

            PlaylistManager(repository)
                .getInitialItem(itemId = seriesId, itemKind = BaseItemKind.SERIES)
        }

        val trace = logs.filter { it.startsWith(TRACE_PREFIX) }
        val joined = trace.joinToString(separator = "\n")
        assertTrue(
            "Expected structured NEXTUP/EPISODE/SOURCE trace but got: $trace",
            joined.contains("event=NEXTUP") &&
                joined.contains("result=SUCCESS") &&
                joined.contains("event=EPISODE") &&
                joined.contains("season=1") &&
                joined.contains("episodeIndex=3") &&
                joined.contains("fallback=false") &&
                joined.contains("event=SOURCE") &&
                joined.contains("completedLocalSource=ABSENT") &&
                joined.contains("sourceKind=HLS"),
        )
        assertFalse("Trace must not contain the media URI", joined.contains(sourcePath))
        assertFalse(
            "Trace must not contain the raw episode UUID",
            joined.contains(episode.id.toString()),
        )
    }

    @Test
    fun `series resolution trace context is not created without a debug tree`() = runBlocking {
        Timber.uprootAll()
        try {
            val episode = episode(seasonOneId, parentIndexNumber = 1, indexNumber = 3)
            val manager =
                PlaylistManager(
                    seriesRepository(
                        nextUp = { listOf(episode) },
                        episodes = { seasonId -> listOf(episode.copy(seasonId = seasonId)) },
                        sources = {
                            listOf(
                                source(
                                    path = "https://example.invalid/master.m3u8",
                                    container = "hls",
                                )
                            )
                        },
                    )
                )

            manager.getInitialItem(itemId = seriesId, itemKind = BaseItemKind.SERIES)

            assertNull(manager.lastSeriesResolutionTraceContext)
        } finally {
            Timber.uprootAll()
        }
    }

    @Test
    fun `next up exception falls back without leaking exception text`() = runBlocking {
        val fallbackEpisode = episode(seasonOneId, parentIndexNumber = 1, indexNumber = 1)
        val logs = captureTimber {
            val repository =
                seriesRepository(
                    nextUp = { throw IOException("secret-token at /private/path") },
                    seasons = { listOf(season(seasonOneId, indexNumber = 1)) },
                    episodes = { seasonId -> listOf(fallbackEpisode.copy(seasonId = seasonId)) },
                    sources = {
                        listOf(
                            source(
                                path = "https://example.invalid/private/movie.mkv",
                                container = "mkv",
                            )
                        )
                    },
                )

            PlaylistManager(repository)
                .getInitialItem(itemId = seriesId, itemKind = BaseItemKind.SERIES)
        }

        val trace = logs.filter { it.startsWith(TRACE_PREFIX) }.joinToString(separator = "\n")
        assertTrue(
            "Expected safe exception and fallback trace but got: $trace",
            trace.contains("event=NEXTUP") &&
                trace.contains("result=EXCEPTION") &&
                trace.contains("exceptionCategory=IO") &&
                trace.contains("event=EPISODE") &&
                trace.contains("fallback=true"),
        )
        assertFalse(trace.contains("secret-token"))
        assertFalse(trace.contains("/private/path"))
    }

    @Test
    fun `next up empty result is distinguishable from success`() = runBlocking {
        val logs = captureTimber {
            SeriesResolutionTraceContext.start(seed = "empty", seriesId = seriesId)
                .recordNextUp(Result.success(emptyList()))
        }

        assertTrue(
            logs.any {
                it.contains("event=NEXTUP") && it.contains("result=EMPTY") && it.contains("count=0")
            }
        )
    }

    @Test
    fun `completed local source remains present when its file is missing`() {
        val state =
            classifySeriesSource(
                source =
                    source(
                            path = "/private/downloads/episode.mkv",
                            container = "mkv",
                        )
                        .copy(
                            type = FindroidSourceType.LOCAL,
                            downloadTaskStatus = "COMPLETED",
                        ),
                playbackUri = "/private/downloads/episode.mkv",
                fileExists = { false },
            )

        assertEquals(CompletedLocalSourceState.PRESENT, state.completedLocalSource)
        assertEquals(LocalFileState.FILE_MISSING, state.localFile)
        assertEquals(SeriesSourceKind.LOCAL_DOWNLOAD, state.sourceKind)
    }

    @Test
    fun `legacy completed local source distinguishes an existing file`() {
        val state =
            classifySeriesSource(
                source =
                    source(
                            path = "/private/downloads/legacy.mkv",
                            container = "mkv",
                        )
                        .copy(
                            type = FindroidSourceType.LOCAL,
                            downloadTaskStatus = null,
                        ),
                playbackUri = "/private/downloads/legacy.mkv",
                fileExists = { true },
            )

        assertEquals(CompletedLocalSourceState.PRESENT, state.completedLocalSource)
        assertEquals(LocalFileState.FILE_EXISTS, state.localFile)
        assertEquals(SeriesSourceKind.LOCAL_DOWNLOAD, state.sourceKind)
    }

    @Test
    fun `local source trace never emits its absolute path`() = runBlocking {
        val localPath = "/private/downloads/missing-episode.mkv"
        val logs = captureTimber {
            SeriesResolutionTraceContext.start(seed = "local", seriesId = seriesId)
                .recordSource(
                    source =
                        source(path = localPath, container = "mkv")
                            .copy(
                                type = FindroidSourceType.LOCAL,
                                downloadTaskStatus = "COMPLETED",
                            ),
                    playbackUri = localPath,
                    fileExists = { false },
                )
        }

        val trace = logs.filter { it.startsWith(TRACE_PREFIX) }.joinToString(separator = "\n")
        assertTrue(
            trace.contains("completedLocalSource=PRESENT") &&
                trace.contains("localFile=FILE_MISSING") &&
                trace.contains("sourceKind=LOCAL_DOWNLOAD")
        )
        assertFalse(trace.contains(localPath))
    }

    @Test
    fun `remote hls source has no completed local row`() {
        val state =
            classifySeriesSource(
                source =
                    source(
                        path = "https://example.invalid/secret/master.m3u8",
                        container = "mkv",
                    ),
                playbackUri = "https://example.invalid/secret/master.m3u8",
                fileExists = { error("remote source must not probe a local file") },
            )

        assertEquals(CompletedLocalSourceState.ABSENT, state.completedLocalSource)
        assertEquals(LocalFileState.NOT_APPLICABLE, state.localFile)
        assertEquals(SeriesSourceKind.HLS, state.sourceKind)
    }

    @Test
    fun `playback tracker emits backend first frame advance and terminal once`() {
        val lines = mutableListOf<String>()
        val tracker =
            SeriesPlaybackTraceTracker(
                traceId = "SRT-TEST",
                backend = SeriesBackendKind.MPV,
                emit = lines::add,
            )

        tracker.recordFirstFrame()
        tracker.recordFirstFrame()
        tracker.observePosition(positionMs = 1_000L, terminalKind = null, hasPlayerError = false)
        tracker.observePosition(positionMs = 2_500L, terminalKind = null, hasPlayerError = false)
        tracker.observePosition(positionMs = 3_000L, terminalKind = null, hasPlayerError = false)
        tracker.recordEngineError("io_file_not_found")
        tracker.recordEngineError("io_file_not_found")
        tracker.observePosition(
            positionMs = 3_000L,
            terminalKind = "PLAYBACK_PROGRESS_TIMEOUT",
            hasPlayerError = true,
        )
        tracker.observePosition(
            positionMs = 3_000L,
            terminalKind = "PLAYBACK_PROGRESS_TIMEOUT",
            hasPlayerError = true,
        )

        val joined = lines.joinToString(separator = "\n")
        assertTrue(joined.contains("event=BACKEND traceId=SRT-TEST backend=MPV"))
        assertEquals(1, lines.count { it.contains("event=FIRST_FRAME") })
        assertEquals(1, lines.count { it.contains("event=POSITION") })
        assertEquals(1, lines.count { it.contains("event=ENGINE_ERROR") })
        assertEquals(1, lines.count { it.contains("event=TERMINAL") })
        assertTrue(joined.contains("code=IO_FILE_NOT_FOUND"))
    }

    private suspend fun captureTimber(block: suspend () -> Unit): List<String> {
        val messages = CopyOnWriteArrayList<String>()
        Timber.uprootAll()
        Timber.plant(
            object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    messages += message
                }
            }
        )
        return try {
            block()
            messages.toList()
        } finally {
            Timber.uprootAll()
        }
    }

    private fun seriesRepository(
        nextUp: () -> List<FindroidEpisode>,
        seasons: () -> List<FindroidSeason> = { emptyList() },
        episodes: (UUID) -> List<FindroidEpisode>,
        sources: () -> List<FindroidSource>,
    ): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getNextUp" -> nextUp()
                "getSeasons" -> seasons()
                "getEpisodes" -> episodes(args!![1] as UUID)
                "getMediaSources" -> sources()
                "toString" -> "SeriesResolutionTraceTestRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as JellyfinRepository

    private fun episode(
        seasonId: UUID,
        parentIndexNumber: Int,
        indexNumber: Int,
    ): FindroidEpisode =
        FindroidEpisode(
            id = UUID.nameUUIDFromBytes("episode-$parentIndexNumber-$indexNumber".toByteArray()),
            name = "fixture episode",
            originalTitle = null,
            overview = "",
            indexNumber = indexNumber,
            indexNumberEnd = null,
            parentIndexNumber = parentIndexNumber,
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesId = seriesId,
            seriesName = "fixture series",
            seasonId = seasonId,
            seasonName = null,
            communityRating = null,
            people = emptyList(),
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private fun season(seasonId: UUID, indexNumber: Int): FindroidSeason =
        FindroidSeason(
            id = seasonId,
            name = "fixture season",
            seriesId = seriesId,
            seriesName = "fixture series",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            indexNumber = indexNumber,
            episodes = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            unplayedItemCount = 0,
            images = FindroidImages(),
        )

    private fun source(path: String, container: String): FindroidSource =
        FindroidSource(
            id = "fixture-source",
            name = "fixture",
            type = FindroidSourceType.REMOTE,
            path = path,
            size = 1L,
            mediaStreams = emptyList(),
            container = container,
        )

    private companion object {
        const val TRACE_PREFIX = "AURORAMA_SERIES_TRACE"
    }
}
