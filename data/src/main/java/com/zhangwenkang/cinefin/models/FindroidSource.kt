package com.zhangwenkang.cinefin.models

import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.io.File
import java.util.UUID
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
     * 影阁：Hi10P（H.264 High 10）这类片源，设备硬解会输出黑画面但进度照走， 必须走服务器转码；播放时优先用它，下载仍然用 [path]（原始文件）。
     */
    val transcodingPath: String? = null,
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

    return FindroidSource(
        id = id.orEmpty(),
        name = name.orEmpty(),
        type = FindroidSourceType.REMOTE,
        path = path,
        size = size ?: 0,
        mediaStreams =
            mediaStreams?.map { it.toFindroidMediaStream(jellyfinRepository) } ?: emptyList(),
        transcodingPath = resolvedTranscodingPath,
    )
}

suspend fun FindroidSourceDto.toFindroidSource(
    serverDatabaseDao: ServerDatabaseDao
): FindroidSource {
    return FindroidSource(
        id = id,
        name = name,
        type = type,
        path = path,
        size = File(path).length(),
        mediaStreams =
            serverDatabaseDao.getMediaStreamsBySourceId(id).map { it.toFindroidMediaStream() },
        downloadId = downloadId,
    )
}

enum class FindroidSourceType {
    REMOTE,
    LOCAL,
}
