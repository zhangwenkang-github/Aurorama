package com.zhangwenkang.cinefin.models

import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.io.File
import java.net.URI
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo

data class FindroidSource(
    val id: String,
    val name: String,
    val type: FindroidSourceType,
    val path: String,
    val size: Long,
    val mediaStreams: List<FindroidMediaStream>,
    val downloadId: Long? = null,
    /**
     * 服务器判定「需要转码」时给出的播放地址（通常是 HLS）。
     *
     * 极光幕：Hi10P（H.264 High 10）这类片源，设备硬解会输出黑画面但进度照走， 必须走服务器转码；播放时优先用它，下载仍然用 [path]（原始文件）。
     */
    val transcodingPath: String? = null,
    /** 媒体源总码率（bit/s）；null / 0 表示未知。 */
    val bitrate: Int? = null,
    /** 服务端返回的真实容器名，例如 mkv / mp4 / hls。 */
    val container: String? = null,
    /** 下载源映射白盒证据；不含令牌。 */
    val downloadMarker: DownloadSourceMarker? = null,
    /** LOCAL source 的持久生命周期状态；REMOTE / legacy 可为 null。 */
    val downloadTaskStatus: String? = null,
)

enum class DownloadPathKind {
    SYNTHETIC_STREAM,
    OFFICIAL_DOWNLOAD,
    ABSOLUTE_HTTP,
    RELATIVE_HTTP,
    EMPTY,
    INVALID,
}

data class DownloadSourceMarker(
    val itemId: UUID,
    val sourceId: String?,
    val protocol: String,
    val pathKind: DownloadPathKind,
    val requestSent: Boolean,
    val status: String,
    val failureReason: String? = null,
)

suspend fun MediaSourceInfo.toFindroidSource(
    jellyfinRepository: JellyfinRepository,
    itemId: UUID,
    includePath: Boolean = false,
): FindroidSource {
    val path =
        when (protocol) {
            MediaProtocol.FILE -> {
                try {
                    if (includePath) jellyfinRepository.getStreamUrl(itemId, id.orEmpty()) else ""
                } catch (_: Exception) {
                    ""
                }
            }
            MediaProtocol.HTTP -> this.path.orEmpty()
            else -> ""
        }

    // 服务器已经算好要转码：把相对地址补成绝对地址，播放时优先使用
    val resolvedTranscodingPath =
        transcodingUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { url ->
                if (url.startsWith("http", ignoreCase = true)) {
                    url
                } else {
                    jellyfinRepository.getBaseUrl().trimEnd('/') + "/" + url.trimStart('/')
                }
            }

    return toFindroidSource(
        baseUrl = jellyfinRepository.getBaseUrl(),
        itemId = itemId,
        resolvedPath = path,
        resolvedTranscodingPath = resolvedTranscodingPath,
    )
}

internal fun MediaSourceInfo.toFindroidSource(
    baseUrl: String,
    itemId: UUID,
): FindroidSource {
    val resolution = resolveDownloadPath(baseUrl, itemId)
    val resolvedTranscodingPath =
        transcodingUrl?.takeIf { it.isNotBlank() }?.let { resolveHttpUrl(baseUrl, it) }
    return toFindroidSource(
        baseUrl = baseUrl,
        itemId = itemId,
        resolvedPath = resolution.path,
        resolvedTranscodingPath = resolvedTranscodingPath,
        downloadMarker = resolution.marker,
    )
}

internal fun MediaSourceInfo.toOfficialDownloadSource(
    baseUrl: String,
    itemId: UUID,
    downloadUrl: String,
): FindroidSource {
    val metadata = toFindroidSource(baseUrl, itemId)
    val normalizedDownloadUrl = downloadUrl.toHttpUrlOrNull()?.toString()
    if (normalizedDownloadUrl == null) {
        return metadata.copy(
            path = "",
            transcodingPath = null,
            downloadMarker =
                DownloadSourceMarker(
                    itemId = itemId,
                    sourceId = id,
                    protocol = "DOWNLOAD_GET",
                    pathKind = DownloadPathKind.INVALID,
                    requestSent = false,
                    status = "REJECTED",
                    failureReason = "invalid_download_url",
                ),
        )
    }
    return metadata.copy(
        path = normalizedDownloadUrl,
        transcodingPath = null,
        downloadMarker =
            DownloadSourceMarker(
                itemId = itemId,
                sourceId = id,
                protocol = "DOWNLOAD_GET",
                pathKind = DownloadPathKind.OFFICIAL_DOWNLOAD,
                requestSent = false,
                status = "READY",
            ),
    )
}

private data class DownloadPathResolution(
    val path: String,
    val marker: DownloadSourceMarker,
)

private fun MediaSourceInfo.resolveDownloadPath(
    baseUrl: String,
    itemId: UUID,
): DownloadPathResolution {
    val protocolName = protocol.name
    fun rejected(pathKind: DownloadPathKind, reason: String) =
        DownloadPathResolution(
            path = "",
            marker = downloadMarker(itemId, protocolName, pathKind, reason),
        )

    return when (protocol) {
        MediaProtocol.FILE -> {
            val origin = baseUrl.toHttpUrlOrNull()
            if (origin == null) {
                rejected(DownloadPathKind.INVALID, "invalid_frozen_origin")
            } else {
                val streamUrl =
                    origin
                        .newBuilder()
                        .query(null)
                        .fragment(null)
                        .addPathSegments("Videos")
                        .addPathSegment(itemId.toString())
                        .addPathSegment("stream")
                        .addQueryParameter("static", "true")
                        .addQueryParameter("mediaSourceId", id.orEmpty())
                        .build()
                        .toString()
                DownloadPathResolution(
                    path = streamUrl,
                    marker =
                        downloadMarker(itemId, protocolName, DownloadPathKind.SYNTHETIC_STREAM),
                )
            }
        }
        MediaProtocol.HTTP -> {
            val rawPath = path.orEmpty()
            val pathKind =
                when {
                    rawPath.isBlank() -> DownloadPathKind.EMPTY
                    rawPath.toHttpUrlOrNull() != null -> DownloadPathKind.ABSOLUTE_HTTP
                    else -> DownloadPathKind.RELATIVE_HTTP
                }
            if (pathKind == DownloadPathKind.EMPTY) {
                rejected(pathKind, "empty_url")
            } else {
                val resolved = resolveHttpUrl(baseUrl, rawPath)
                if (resolved == null) {
                    rejected(DownloadPathKind.INVALID, "invalid_http_url")
                } else {
                    DownloadPathResolution(
                        path = resolved,
                        marker = downloadMarker(itemId, protocolName, pathKind),
                    )
                }
            }
        }
        else -> rejected(DownloadPathKind.INVALID, "non_http_protocol")
    }
}

private fun resolveHttpUrl(baseUrl: String, rawUrl: String): String? {
    rawUrl.toHttpUrlOrNull()?.let {
        return rawUrl
    }
    if (runCatching { URI(rawUrl).scheme }.getOrNull() != null) return null
    val origin = baseUrl.toHttpUrlOrNull() ?: return null
    return if (rawUrl.startsWith('/')) {
        origin.resolve(rawUrl)?.toString()
    } else {
        val baseDirectory =
            origin.newBuilder().query(null).fragment(null).build().toString().trimEnd('/') + "/"
        baseDirectory.toHttpUrlOrNull()?.resolve(rawUrl.trimStart('/'))?.toString()
    }
}

private fun MediaSourceInfo.downloadMarker(
    itemId: UUID,
    protocolName: String,
    pathKind: DownloadPathKind,
    failureReason: String? = null,
): DownloadSourceMarker =
    DownloadSourceMarker(
        itemId = itemId,
        sourceId = id,
        protocol = protocolName,
        pathKind = pathKind,
        requestSent = false,
        status = if (failureReason == null) "READY" else "REJECTED",
        failureReason = failureReason,
    )

private fun MediaSourceInfo.toFindroidSource(
    baseUrl: String,
    itemId: UUID,
    resolvedPath: String,
    resolvedTranscodingPath: String?,
    downloadMarker: DownloadSourceMarker? = null,
): FindroidSource {
    return FindroidSource(
        id = id.orEmpty(),
        name = name.orEmpty(),
        type = FindroidSourceType.REMOTE,
        path = resolvedPath,
        size = size ?: 0,
        mediaStreams = mediaStreams?.map { it.toFindroidMediaStream(baseUrl) } ?: emptyList(),
        transcodingPath = resolvedTranscodingPath,
        bitrate = bitrate?.takeIf { it > 0 },
        container = container?.takeIf { it.isNotBlank() },
        downloadMarker = downloadMarker,
    )
}

suspend fun FindroidSourceDto.toFindroidSource(
    serverDatabaseDao: ServerDatabaseDao
): FindroidSource {
    return toFindroidSource(serverDatabaseDao.getMediaStreamsBySourceId(id))
}

fun FindroidSourceDto.toFindroidSource(mediaStreams: List<FindroidMediaStreamDto>): FindroidSource {
    return FindroidSource(
        id = id,
        name = name,
        type = type,
        path = path,
        size = File(path).length(),
        mediaStreams = mediaStreams.map { it.toFindroidMediaStream() },
        downloadId = downloadId,
        bitrate = bitrate?.takeIf { it > 0 },
        container = container?.takeIf { it.isNotBlank() },
        downloadTaskStatus = taskStatus,
    )
}

enum class FindroidSourceType {
    REMOTE,
    LOCAL,
}
