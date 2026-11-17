package com.zhangwenkang.cinefin.repository

import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.identity.IdentityReadLease
import com.zhangwenkang.cinefin.identity.IdentityReadLeasePort
import com.zhangwenkang.cinefin.identity.IdentityReadPort
import com.zhangwenkang.cinefin.identity.IdentitySnapshot
import com.zhangwenkang.cinefin.identity.IdentityStateStatus
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.isAvailableForSourceSelection
import com.zhangwenkang.cinefin.models.toFindroidSource
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.PlayerDecodeFallback
import com.zhangwenkang.cinefin.settings.domain.PlayerStreamingQuality
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.operations.MediaInfoApi
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackInfoResponse

enum class PlaybackSourceTerminal {
    RESOLVED,
    FAILED,
    TIMEOUT,
}

enum class PlaybackSourceFailure {
    IDENTITY_UNAVAILABLE,
    IDENTITY_CHANGED,
    HTTP_ERROR,
    NETWORK_ERROR,
    NO_MEDIA_SOURCE,
    UNKNOWN,
}

enum class PlaybackEndpointSchemeBucket {
    HTTPS,
    HTTP,
    OTHER,
    INVALID,
}

enum class PlaybackEndpointPortBucket {
    HTTPS_DEFAULT,
    HTTP_DEFAULT,
    NON_DEFAULT,
    INVALID,
}

data class PlaybackSourceIdentityDiagnostics(
    val identityGeneration: Long,
    val schemeBucket: PlaybackEndpointSchemeBucket,
    val portBucket: PlaybackEndpointPortBucket,
    val hostHash: String,
)

data class PlaybackSourceAttemptContext(
    val sourceIntentId: String,
    val attemptId: String,
) {
    init {
        require(sourceIntentId.isNotBlank())
        require(attemptId.isNotBlank())
    }

    companion object {
        fun new(
            sourceIntentId: String = UUID.randomUUID().toString()
        ): PlaybackSourceAttemptContext =
            PlaybackSourceAttemptContext(
                sourceIntentId = sourceIntentId,
                attemptId = UUID.randomUUID().toString(),
            )
    }
}

class PlaybackSourceResolution
internal constructor(
    val sourceIntentId: String,
    val attemptId: String,
    val terminal: PlaybackSourceTerminal,
    val failure: PlaybackSourceFailure?,
    val identity: PlaybackSourceIdentityDiagnostics?,
    val terminalPublishCount: Int,
    internal val sources: List<FindroidSource>,
) {
    fun resolvedSourcesOrThrow(): List<FindroidSource> {
        if (terminal != PlaybackSourceTerminal.RESOLVED) {
            throw PlaybackSourceResolutionException(this)
        }
        return sources
    }

    override fun toString(): String =
        "PlaybackSourceResolution(" +
            "terminal=$terminal, " +
            "failure=$failure, " +
            "identityGeneration=${identity?.identityGeneration}, " +
            "schemeBucket=${identity?.schemeBucket}, " +
            "portBucket=${identity?.portBucket}, " +
            "hostHash=${identity?.hostHash}, " +
            "terminalPublishCount=$terminalPublishCount" +
            ")"
}

class PlaybackSourceResolutionException(val resolution: PlaybackSourceResolution) :
    IllegalStateException(
        "Playback source ${resolution.terminal.name.lowercase()}: ${resolution.failure?.name ?: "NONE"}"
    )

internal class PlaybackSourceIdentity(
    val serverId: String,
    val itemId: UUID,
    val userId: UUID,
    val addressId: UUID,
    val baseUrl: String,
    val accessToken: String,
    val generation: Long,
    val diagnostics: PlaybackSourceIdentityDiagnostics,
) {
    override fun toString(): String =
        "PlaybackSourceIdentity(generation=$generation, diagnostics=$diagnostics)"
}

internal fun interface ScopedPlaybackInfoCall {
    suspend fun execute(
        identity: PlaybackSourceIdentity,
        callTimeoutMs: Long,
        itemId: UUID,
        request: PlaybackInfoDto,
    ): PlaybackInfoResponse

    fun videoStreamUrl(
        identity: PlaybackSourceIdentity,
        itemId: UUID,
        mediaSourceId: String,
    ): String =
        identity.baseUrl
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegments("Videos")
            ?.addPathSegment(itemId.toString())
            ?.addPathSegment("stream")
            ?.addQueryParameter("static", "true")
            ?.addQueryParameter("mediaSourceId", mediaSourceId)
            ?.addQueryParameter("streamOptions", "{}")
            ?.addQueryParameter("enableAudioVbrEncoding", "true")
            ?.build()
            ?.toString() ?: error("Invalid playback endpoint")
}

internal class PlaybackSourceAttemptLease(private val context: PlaybackSourceAttemptContext) {
    private val terminal = AtomicReference<PlaybackSourceResolution?>(null)
    private val publications = AtomicInteger(0)

    fun publishResolved(
        sources: List<FindroidSource>,
        identity: PlaybackSourceIdentityDiagnostics?,
    ): PlaybackSourceResolution =
        publish(
            PlaybackSourceTerminal.RESOLVED,
            failure = null,
            identity = identity,
            sources = sources,
        )

    fun publishFailed(
        failure: PlaybackSourceFailure,
        identity: PlaybackSourceIdentityDiagnostics?,
    ): PlaybackSourceResolution =
        publish(
            PlaybackSourceTerminal.FAILED,
            failure = failure,
            identity = identity,
            sources = emptyList(),
        )

    fun publishTimeout(identity: PlaybackSourceIdentityDiagnostics?): PlaybackSourceResolution =
        publish(
            PlaybackSourceTerminal.TIMEOUT,
            failure = null,
            identity = identity,
            sources = emptyList(),
        )

    fun current(): PlaybackSourceResolution? = terminal.get()

    fun publicationCount(): Int = publications.get()

    private fun publish(
        terminalKind: PlaybackSourceTerminal,
        failure: PlaybackSourceFailure?,
        identity: PlaybackSourceIdentityDiagnostics?,
        sources: List<FindroidSource>,
    ): PlaybackSourceResolution {
        terminal.get()?.let {
            return it
        }
        val candidate =
            PlaybackSourceResolution(
                sourceIntentId = context.sourceIntentId,
                attemptId = context.attemptId,
                terminal = terminalKind,
                failure = failure,
                identity = identity,
                terminalPublishCount = publications.get() + 1,
                sources = sources,
            )
        return if (terminal.compareAndSet(null, candidate)) {
            publications.incrementAndGet()
            candidate
        } else {
            checkNotNull(terminal.get())
        }
    }
}

class PlaybackSourceResolver
private constructor(
    private val database: ServerDatabaseDao,
    private val localLibrary: LocalLibraryRepository,
    private val appPreferences: AppPreferences,
    private val identityReadPort: IdentityReadPort,
    private val playbackInfoCall: ScopedPlaybackInfoCall,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) {
    @Inject
    constructor(
        database: ServerDatabaseDao,
        localLibrary: LocalLibraryRepository,
        appPreferences: AppPreferences,
        identityReadPort: IdentityReadPort,
        jellyfinApi: JellyfinApi,
    ) : this(
        database = database,
        localLibrary = localLibrary,
        appPreferences = appPreferences,
        identityReadPort = identityReadPort,
        playbackInfoCall = SdkScopedPlaybackInfoCall(jellyfinApi),
        constructorMarker = Unit,
    )

    internal constructor(
        database: ServerDatabaseDao,
        localLibrary: LocalLibraryRepository,
        appPreferences: AppPreferences,
        identityReadPort: IdentityReadPort,
        playbackInfoCall: ScopedPlaybackInfoCall,
    ) : this(
        database = database,
        localLibrary = localLibrary,
        appPreferences = appPreferences,
        identityReadPort = identityReadPort,
        playbackInfoCall = playbackInfoCall,
        constructorMarker = Unit,
    )

    suspend fun resolve(
        itemId: UUID,
        includePath: Boolean,
        startPositionTicks: Long,
        attempt: PlaybackSourceAttemptContext? = null,
    ): PlaybackSourceResolution =
        withContext<PlaybackSourceResolution>(Dispatchers.IO) {
            val context = attempt ?: PlaybackSourceAttemptContext.new()
            val terminal = PlaybackSourceAttemptLease(context)

            localLibrary.syntheticSources(itemId)?.let { sources ->
                return@withContext terminal.publishResolved(sources, identity = null)
            }
            completedLocalSource(itemId)?.let { source ->
                return@withContext terminal.publishResolved(listOf(source), identity = null)
            }

            val guard =
                captureIdentityGuard(itemId)
                    ?: return@withContext terminal.publishFailed(
                        PlaybackSourceFailure.IDENTITY_UNAVAILABLE,
                        identity = null,
                    )
            val identity = guard.identity
            if (!isIdentityCurrent(guard)) {
                return@withContext terminal.publishFailed(
                    PlaybackSourceFailure.IDENTITY_CHANGED,
                    identity.diagnostics,
                )
            }

            val callTimeoutMs = playbackInfoCallTimeoutMs()
            val response =
                try {
                    playbackInfoCall.execute(
                        identity = identity,
                        callTimeoutMs = callTimeoutMs,
                        itemId = itemId,
                        request =
                            PlaybackInfoSourceRequestFactory.create(
                                appPreferences = appPreferences,
                                userId = identity.userId,
                                startPositionTicks = startPositionTicks,
                            ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return@withContext publishIfIdentityCurrent(
                        guard = guard,
                        terminal = terminal,
                    ) {
                        if (error.isTimeoutFailure()) {
                            terminal.publishTimeout(identity.diagnostics)
                        } else {
                            terminal.publishFailed(
                                error.toPlaybackSourceFailure(),
                                identity.diagnostics,
                            )
                        }
                    }
                }

            if (!isIdentityCurrent(guard)) {
                return@withContext terminal.publishFailed(
                    PlaybackSourceFailure.IDENTITY_CHANGED,
                    identity.diagnostics,
                )
            }

            val sources = buildList {
                response.mediaSources.orEmpty().forEach { mediaSource ->
                    val mapped =
                        mediaSource.toScopedPlaybackSource(
                            identity = identity,
                            playbackInfoCall = playbackInfoCall,
                            includePath = includePath,
                            itemId = itemId,
                        )
                    add(mapped)
                }
                database
                    .getSources(itemId)
                    .map { it.toFindroidSource(database) }
                    .filter { it.isAvailableForSourceSelection() }
                    .let(::addAll)
            }
            return@withContext publishIfIdentityCurrent(
                guard = guard,
                terminal = terminal,
            ) {
                if (sources.isEmpty()) {
                    terminal.publishFailed(
                        PlaybackSourceFailure.NO_MEDIA_SOURCE,
                        identity.diagnostics,
                    )
                } else {
                    terminal.publishResolved(sources, identity.diagnostics)
                }
            }
        }

    private suspend fun completedLocalSource(itemId: UUID): FindroidSource? =
        database.getCompletedSource(itemId)?.toFindroidSource(database)?.takeIf {
            it.isAvailableForSourceSelection()
        }

    private suspend fun captureIdentityGuard(itemId: UUID): IdentityGuard? {
        val leasePort = identityReadPort as? IdentityReadLeasePort
        val lease = leasePort?.acquireReadyLease()
        val snapshot =
            lease?.snapshot
                ?: if (leasePort == null) {
                    identityReadPort.current()
                } else {
                    return null
                }
        if (snapshot.status != IdentityStateStatus.READY) return null
        val selection = snapshot.selection
        if (selection.offlineMode) return null
        val serverId = selection.serverId?.takeIf { it.isNotBlank() } ?: return null
        val userId = selection.userId?.toUuidOrNull() ?: return null
        val addressId = selection.addressId?.toUuidOrNull() ?: return null
        val relation = database.getServerWithAddressAndUser(serverId) ?: return null
        val address = relation.address ?: return null
        val user = relation.user ?: return null
        val token = user.accessToken?.takeIf { it.isNotBlank() } ?: return null
        val normalizedBaseUrl = normalizePlaybackEndpoint(address.address) ?: return null
        if (
            relation.server.id != serverId ||
                relation.server.currentServerAddressId != addressId ||
                relation.server.currentUserId != userId ||
                address.id != addressId ||
                address.serverId != serverId ||
                user.id != userId ||
                user.serverId != serverId
        ) {
            return null
        }
        val identity =
            PlaybackSourceIdentity(
                serverId = serverId,
                itemId = itemId,
                userId = userId,
                addressId = addressId,
                baseUrl = normalizedBaseUrl,
                accessToken = token,
                generation = snapshot.identityGeneration,
                diagnostics =
                    playbackIdentityDiagnostics(snapshot.identityGeneration, normalizedBaseUrl),
            )
        return IdentityGuard(
            identity = identity,
            snapshot = snapshot,
            lease = lease,
            leasePort = leasePort,
        )
    }

    private suspend fun isIdentityCurrent(guard: IdentityGuard): Boolean =
        try {
            val leasePort = guard.leasePort
            val lease = guard.lease
            if (leasePort != null && lease != null) {
                leasePort.isReadyLeaseCurrent(lease)
            } else {
                identityReadPort.current() == guard.snapshot
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    private suspend fun publishIfIdentityCurrent(
        guard: IdentityGuard,
        terminal: PlaybackSourceAttemptLease,
        publish: () -> PlaybackSourceResolution,
    ): PlaybackSourceResolution {
        val leasePort = guard.leasePort
        val lease = guard.lease
        if (leasePort != null && lease != null) {
            return leasePort.withReadyLease(lease) { publish() }
                ?: terminal.publishFailed(
                    PlaybackSourceFailure.IDENTITY_CHANGED,
                    guard.identity.diagnostics,
                )
        }
        return if (isIdentityCurrent(guard)) {
            publish()
        } else {
            terminal.publishFailed(
                PlaybackSourceFailure.IDENTITY_CHANGED,
                guard.identity.diagnostics,
            )
        }
    }

    private fun playbackInfoCallTimeoutMs(): Long =
        appPreferences
            .getValue(appPreferences.requestTimeout)
            .takeIf { it > 0L }
            ?.coerceAtMost(MAX_PLAYBACK_SOURCE_CALL_TIMEOUT_MS)
            ?: MAX_PLAYBACK_SOURCE_CALL_TIMEOUT_MS

    private data class IdentityGuard(
        val identity: PlaybackSourceIdentity,
        val snapshot: IdentitySnapshot,
        val lease: IdentityReadLease?,
        val leasePort: IdentityReadLeasePort?,
    )

    private companion object {
        const val MAX_PLAYBACK_SOURCE_CALL_TIMEOUT_MS = 30_000L
    }
}

private class SdkScopedPlaybackInfoCall(private val jellyfinApi: JellyfinApi) :
    ScopedPlaybackInfoCall {
    override suspend fun execute(
        identity: PlaybackSourceIdentity,
        callTimeoutMs: Long,
        itemId: UUID,
        request: PlaybackInfoDto,
    ): PlaybackInfoResponse {
        val apiClient =
            jellyfinApi.jellyfin.createApi(
                baseUrl = identity.baseUrl,
                accessToken = identity.accessToken,
                httpClientOptions =
                    HttpClientOptions(
                        requestTimeout = callTimeoutMs.toDuration(DurationUnit.MILLISECONDS),
                        connectTimeout = 30_000L.toDuration(DurationUnit.MILLISECONDS),
                        socketTimeout = 30_000L.toDuration(DurationUnit.MILLISECONDS),
                    ),
            )
        return MediaInfoApi(apiClient).getPostedPlaybackInfo(itemId, request).content
    }
}

internal object PlaybackInfoSourceRequestFactory {
    fun create(
        appPreferences: AppPreferences,
        userId: UUID,
        startPositionTicks: Long,
    ): PlaybackInfoDto {
        val streamingBitrate = appPreferences.getPlayerStreamingBitrate()
        val maxStreamingBitrate =
            PlayerStreamingQuality.maxStreamingBitrate(streamingBitrate).toInt()
        val transcodingEnabled = PlayerStreamingQuality.transcodingEnabled(streamingBitrate)
        val forceTranscode =
            PlayerDecodeFallback.forcesServerTranscode(
                appPreferences.getValue(appPreferences.playerDecodeFallbackStage)
            )
        return PlaybackInfoDto(
            userId = userId,
            deviceProfile = PlaybackDeviceProfileFactory.create(maxStreamingBitrate),
            maxStreamingBitrate = maxStreamingBitrate,
            startTimeTicks = startPositionTicks.takeIf { it > 0L },
            enableTranscoding = transcodingEnabled,
            enableDirectPlay = if (forceTranscode) false else null,
            enableDirectStream = if (forceTranscode) false else null,
            allowVideoStreamCopy = if (forceTranscode) false else null,
            allowAudioStreamCopy = if (forceTranscode) false else null,
        )
    }
}

private fun MediaSourceInfo.toScopedPlaybackSource(
    identity: PlaybackSourceIdentity,
    playbackInfoCall: ScopedPlaybackInfoCall,
    includePath: Boolean,
    itemId: UUID,
): FindroidSource {
    val mapped = toFindroidSource(baseUrl = identity.baseUrl, itemId = itemId)
    return when {
        protocol != MediaProtocol.FILE -> mapped
        includePath ->
            mapped.copy(
                path =
                    playbackInfoCall.videoStreamUrl(
                        identity = identity,
                        itemId = itemId,
                        mediaSourceId = id.orEmpty(),
                    )
            )
        else -> mapped.copy(path = "")
    }
}

private fun normalizePlaybackEndpoint(value: String): String? =
    value
        .trim()
        .toHttpUrlOrNull()
        ?.newBuilder()
        ?.query(null)
        ?.fragment(null)
        ?.build()
        ?.toString()
        ?.trimEnd('/')

private fun playbackIdentityDiagnostics(
    identityGeneration: Long,
    baseUrl: String,
): PlaybackSourceIdentityDiagnostics {
    val url = baseUrl.toHttpUrlOrNull()
    val schemeBucket =
        when (url?.scheme?.lowercase()) {
            "https" -> PlaybackEndpointSchemeBucket.HTTPS
            "http" -> PlaybackEndpointSchemeBucket.HTTP
            null -> PlaybackEndpointSchemeBucket.INVALID
            else -> PlaybackEndpointSchemeBucket.OTHER
        }
    val portBucket =
        when {
            url == null -> PlaybackEndpointPortBucket.INVALID
            url.scheme == "https" && url.port == 443 -> PlaybackEndpointPortBucket.HTTPS_DEFAULT
            url.scheme == "http" && url.port == 80 -> PlaybackEndpointPortBucket.HTTP_DEFAULT
            else -> PlaybackEndpointPortBucket.NON_DEFAULT
        }
    val hostHash =
        url?.host
            ?.lowercase()
            ?.let {
                MessageDigest.getInstance("SHA-256")
                    .digest(it.toByteArray(StandardCharsets.UTF_8))
                    .joinToString(separator = "") { byte -> "%02X".format(byte.toInt() and 0xFF) }
                    .take(16)
            }
            .orEmpty()
    return PlaybackSourceIdentityDiagnostics(
        identityGeneration = identityGeneration,
        schemeBucket = schemeBucket,
        portBucket = portBucket,
        hostHash = hostHash,
    )
}

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

private fun Throwable.isTimeoutFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (
            current is TimeoutException ||
                current is SocketTimeoutException ||
                current is InterruptedIOException
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

private fun Throwable.toPlaybackSourceFailure(): PlaybackSourceFailure =
    when {
        this is InvalidStatusException -> PlaybackSourceFailure.HTTP_ERROR
        this is IOException -> PlaybackSourceFailure.NETWORK_ERROR
        else -> PlaybackSourceFailure.UNKNOWN
    }
