package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.UndeclaredThrowableException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Debug-only, structured SERIES top-play trace.
 *
 * `BaseApplication` only plants a Timber tree for debug builds, so these calls are silent in
 * release while the production selection and playback branches remain unchanged.
 */
internal object SeriesResolutionTraceLog {
    const val PREFIX = "AURORAMA_SERIES_TRACE"

    private const val TAG = "SeriesResolutionTrace"

    fun isEnabled(): Boolean = Timber.treeCount > 0

    fun emit(line: String) {
        val normalized = if (line.startsWith(PREFIX)) line else "$PREFIX $line"
        Timber.tag(TAG).d("%s", normalized)
    }
}

internal enum class NextUpTraceResult {
    SUCCESS,
    EMPTY,
    EXCEPTION,
}

internal enum class SeriesPlaybackSelection {
    NEXTUP,
    FALLBACK,
}

internal enum class CompletedLocalSourceState {
    PRESENT,
    ABSENT,
}

internal enum class LocalFileState {
    FILE_EXISTS,
    FILE_MISSING,
    NOT_APPLICABLE,
}

internal enum class SeriesSourceKind {
    DIRECT_PLAY,
    HLS,
    LOCAL_DOWNLOAD,
    ERROR,
}

internal enum class SeriesBackendKind {
    EXOPLAYER,
    MPV,
}

internal data class SeriesSourceTraceState(
    val completedLocalSource: CompletedLocalSourceState,
    val localFile: LocalFileState,
    val sourceKind: SeriesSourceKind,
    val sourceType: String,
    val container: String,
)

internal data class SeriesResolutionTraceContext(val traceId: String) {
    private val lock = Any()
    private var nextUpRecorded = false
    private var episodeRecorded = false
    private var sourceRecorded = false

    fun recordNextUp(result: Result<List<FindroidEpisode>>) {
        val trace =
            result.fold(
                onSuccess = { episodes ->
                    val outcome =
                        if (episodes.isEmpty()) NextUpTraceResult.EMPTY
                        else NextUpTraceResult.SUCCESS
                    "result=${outcome.name} count=${episodes.size}"
                },
                onFailure = { error ->
                    "result=${NextUpTraceResult.EXCEPTION.name} " +
                        "exceptionCategory=${safeExceptionCategory(error)}"
                },
            )
        val line =
            synchronized(lock) {
                if (nextUpRecorded) return
                nextUpRecorded = true
                "event=NEXTUP traceId=$traceId $trace"
            }
        SeriesResolutionTraceLog.emit(line)
    }

    fun recordEpisode(episode: FindroidEpisode, fallback: Boolean) {
        val selection =
            if (fallback) SeriesPlaybackSelection.FALLBACK else SeriesPlaybackSelection.NEXTUP
        val fields =
            "episode=${opaqueSha256(episode.id.toString())} " +
                "season=${episode.parentIndexNumber} " +
                "episodeIndex=${episode.indexNumber} " +
                "fallback=$fallback selection=${selection.name}"
        val line =
            synchronized(lock) {
                if (episodeRecorded) return
                episodeRecorded = true
                "event=EPISODE traceId=$traceId $fields"
            }
        SeriesResolutionTraceLog.emit(line)
    }

    fun recordSource(
        source: FindroidSource?,
        playbackUri: String,
        fileExists: (String) -> Boolean = { File(it).exists() },
    ) {
        val state = classifySeriesSource(source, playbackUri, fileExists)
        val fields =
            "completedLocalSource=${state.completedLocalSource.name} " +
                "localFile=${state.localFile.name} " +
                "sourceKind=${state.sourceKind.name} " +
                "sourceType=${state.sourceType} " +
                "container=${state.container}"
        val line =
            synchronized(lock) {
                if (sourceRecorded) return
                sourceRecorded = true
                "event=SOURCE traceId=$traceId $fields"
            }
        SeriesResolutionTraceLog.emit(line)
    }

    fun recordSourceFailure(error: Throwable) {
        val line =
            synchronized(lock) {
                if (sourceRecorded) return
                sourceRecorded = true
                "event=SOURCE traceId=$traceId " +
                    "completedLocalSource=ABSENT " +
                    "localFile=NOT_APPLICABLE " +
                    "sourceKind=ERROR sourceType=NONE " +
                    "exceptionCategory=${safeExceptionCategory(error)}"
            }
        SeriesResolutionTraceLog.emit(line)
    }

    companion object {
        fun start(
            seed: String = UUID.randomUUID().toString(),
            seriesId: UUID,
        ): SeriesResolutionTraceContext {
            val traceId = "SRT-${opaqueSha256(seed).take(16)}"
            SeriesResolutionTraceLog.emit(
                "event=ENTRY traceId=$traceId itemKind=SERIES " +
                    "series=${opaqueSha256(seriesId.toString()).take(16)}"
            )
            return SeriesResolutionTraceContext(traceId)
        }
    }
}

internal class SeriesPlaybackTraceTracker(
    private val traceId: String,
    backend: SeriesBackendKind,
    private val emit: (String) -> Unit = SeriesResolutionTraceLog::emit,
) {
    private val lock = Any()
    private var firstFrameRecorded = false
    private var positionAdvancedRecorded = false
    private var engineErrorRecorded = false
    private var terminalRecorded = false
    private var positionBaselineMs: Long? = null

    init {
        emit(
            "${SeriesResolutionTraceLog.PREFIX} event=BACKEND traceId=$traceId " +
                "backend=${backend.name}"
        )
    }

    fun recordFirstFrame() {
        val line =
            synchronized(lock) {
                if (firstFrameRecorded) return
                firstFrameRecorded = true
                "${SeriesResolutionTraceLog.PREFIX} event=FIRST_FRAME traceId=$traceId observed=true"
            }
        emit(line)
    }

    fun observePosition(
        positionMs: Long?,
        terminalKind: String?,
        hasPlayerError: Boolean,
    ) {
        val lines = mutableListOf<String>()
        synchronized(lock) {
            if (positionMs != null) {
                val baseline = positionBaselineMs
                if (baseline == null) {
                    positionBaselineMs = positionMs
                } else if (!positionAdvancedRecorded && positionMs >= baseline + ADVANCE_STEP_MS) {
                    positionAdvancedRecorded = true
                    lines +=
                        "${SeriesResolutionTraceLog.PREFIX} event=POSITION traceId=$traceId " +
                            "advancing=true"
                }
            }
            if (!terminalRecorded && (terminalKind != null || hasPlayerError)) {
                terminalRecorded = true
                val reason =
                    terminalKind?.let(::safeToken)
                        ?: if (hasPlayerError) "PLAYER_ERROR" else "UNKNOWN"
                lines +=
                    "${SeriesResolutionTraceLog.PREFIX} event=TERMINAL traceId=$traceId " +
                        "reason=$reason"
            }
        }
        lines.forEach(emit)
    }

    fun recordEngineError(codeName: String) {
        val line =
            synchronized(lock) {
                if (engineErrorRecorded) return
                engineErrorRecorded = true
                "${SeriesResolutionTraceLog.PREFIX} event=ENGINE_ERROR traceId=$traceId " +
                    "code=${safeToken(codeName)}"
            }
        emit(line)
    }

    private companion object {
        const val ADVANCE_STEP_MS = 1_000L
    }
}

internal fun classifySeriesSource(
    source: FindroidSource?,
    playbackUri: String,
    fileExists: (String) -> Boolean = { File(it).exists() },
): SeriesSourceTraceState {
    if (source == null) {
        return SeriesSourceTraceState(
            completedLocalSource = CompletedLocalSourceState.ABSENT,
            localFile = LocalFileState.NOT_APPLICABLE,
            sourceKind = SeriesSourceKind.ERROR,
            sourceType = "NONE",
            container = "UNKNOWN",
        )
    }

    val localPath = source.path.trim()
    val contentUri = localPath.startsWith("content://", ignoreCase = true)
    val completedLocal =
        source.type == FindroidSourceType.LOCAL &&
            !contentUri &&
            !localPath.endsWith(".download", ignoreCase = true) &&
            (source.downloadTaskStatus == null ||
                source.downloadTaskStatus.equals("COMPLETED", ignoreCase = true))
    val localFile =
        when {
            source.type != FindroidSourceType.LOCAL || localPath.isBlank() ->
                LocalFileState.NOT_APPLICABLE
            contentUri -> LocalFileState.NOT_APPLICABLE
            runCatching { fileExists(localPath) }.getOrDefault(false) -> LocalFileState.FILE_EXISTS
            else -> LocalFileState.FILE_MISSING
        }
    val sourceKind =
        when {
            playbackUri.isBlank() -> SeriesSourceKind.ERROR
            source.type == FindroidSourceType.LOCAL -> SeriesSourceKind.LOCAL_DOWNLOAD
            isHlsSource(source, playbackUri) -> SeriesSourceKind.HLS
            else -> SeriesSourceKind.DIRECT_PLAY
        }
    return SeriesSourceTraceState(
        completedLocalSource =
            if (completedLocal) CompletedLocalSourceState.PRESENT
            else CompletedLocalSourceState.ABSENT,
        localFile = localFile,
        sourceKind = sourceKind,
        sourceType = source.type.name,
        container = safeContainer(source.container),
    )
}

internal fun safeExceptionCategory(error: Throwable): String {
    val unwrapped =
        when (error) {
            is InvocationTargetException -> error.targetException ?: error
            is UndeclaredThrowableException -> error.undeclaredThrowable ?: error
            else -> error
        }
    if (unwrapped !== error) return safeExceptionCategory(unwrapped)
    return when (error) {
        is CancellationException -> "CANCELLED"
        is PlaybackStartException -> "SOURCE_UNAVAILABLE"
        is IOException -> "IO"
        is SecurityException -> "AUTH"
        is IllegalArgumentException -> "INVALID"
        else -> "OTHER"
    }
}

internal fun seriesBackendKind(backend: String): SeriesBackendKind =
    if (backend.equals("mpv", ignoreCase = true)) SeriesBackendKind.MPV
    else SeriesBackendKind.EXOPLAYER

internal fun opaqueSha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
        separator = ""
    ) { byte ->
        "%02X".format(Locale.ROOT, byte.toInt() and 0xff)
    }

private fun isHlsSource(source: FindroidSource, playbackUri: String): Boolean =
    !source.transcodingPath.isNullOrBlank() ||
        source.container?.equals("hls", ignoreCase = true) == true ||
        playbackUri
            .substringBefore('?')
            .substringBefore('#')
            .endsWith(
                ".m3u8",
                ignoreCase = true,
            )

private fun safeContainer(raw: String?): String {
    val value = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
    return value.takeIf { it.matches(Regex("[A-Z0-9_-]{1,24}")) } ?: "UNKNOWN"
}

private fun safeToken(raw: String?): String =
    raw?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) }
        ?: "UNKNOWN"
