package com.zhangwenkang.cinefin.utils

import com.zhangwenkang.cinefin.models.FindroidMediaStreamDto
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.toFindroidMediaStreamDto
import java.util.UUID
import org.jellyfin.sdk.model.api.MediaStreamType

/**
 * 下载完成后的媒体流行持久化 seam。
 *
 * 文件下载引擎仍负责传输；这里只把已经落入最终容器的流元数据映射到现有 `mediastreams` 表，便于在 JVM 单测中经过与 Downloader 相同的编排，同时不引入 Room
 * schema 变更。
 */
internal suspend fun persistDownloadedMediaStreams(
    source: FindroidSource,
    localPath: String,
    upsert: suspend (FindroidMediaStreamDto) -> Unit,
) {
    source.mediaStreams
        .filter { stream ->
            !stream.isExternal &&
                (stream.type == MediaStreamType.VIDEO || stream.type == MediaStreamType.AUDIO)
        }
        .withIndex()
        .forEach { (ordinal, stream) ->
            upsert(
                stream.toFindroidMediaStreamDto(
                    id = downloadedMediaStreamId(source.id, stream.type, stream.index, ordinal),
                    sourceId = source.id,
                    path = localPath,
                )
            )
        }
}

private fun downloadedMediaStreamId(
    sourceId: String,
    type: MediaStreamType,
    index: Int,
    ordinal: Int,
): UUID {
    val identity = if (index >= 0) "index=$index" else "ordinal=$ordinal"
    return UUID.nameUUIDFromBytes("$sourceId|${type.name}|$identity".toByteArray(Charsets.UTF_8))
}
