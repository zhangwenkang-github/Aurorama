package com.zhangwenkang.cinefin.models

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import java.util.UUID
import org.jellyfin.sdk.model.api.MediaStreamType

@Entity(tableName = "mediastreams")
data class FindroidMediaStreamDto(
    @PrimaryKey val id: UUID,
    val sourceId: String,
    val title: String,
    val displayTitle: String?,
    val language: String,
    val type: MediaStreamType,
    val codec: String,
    val isExternal: Boolean,
    val path: String,
    val channelLayout: String?,
    val videoRangeType: String?,
    val height: Int?,
    val width: Int?,
    val videoDoViTitle: String?,
    val downloadId: Long? = null,
    val bitRate: Int? = null,
    val frameRate: Float? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val sampleRate: Int? = null,
    val profile: String? = null,
)

fun FindroidMediaStream.toFindroidMediaStreamDto(
    id: UUID,
    sourceId: String,
    path: String,
): FindroidMediaStreamDto {
    return FindroidMediaStreamDto(
        id = id,
        sourceId = sourceId,
        title = title,
        displayTitle = displayTitle,
        language = language,
        type = type,
        codec = codec,
        isExternal = isExternal,
        path = path,
        channelLayout = channelLayout,
        videoRangeType = videoRangeType?.name,
        height = height,
        width = width,
        videoDoViTitle = videoDoViTitle,
        bitRate = bitRate,
        frameRate = frameRate,
        bitDepth = bitDepth,
        channels = channels,
        sampleRate = sampleRate,
        profile = profile,
    )
}
