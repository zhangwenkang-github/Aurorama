package com.zhangwenkang.cinefin.models

import com.zhangwenkang.cinefin.repository.JellyfinRepository
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType

data class FindroidMediaStream(
    val title: String,
    val displayTitle: String?,
    val language: String,
    val type: MediaStreamType,
    val codec: String,
    val isExternal: Boolean,
    val path: String?,
    val channelLayout: String?,
    val videoRangeType: VideoRangeType?,
    val height: Int?,
    val width: Int?,
    val videoDoViTitle: String?,
    /** Jellyfin 流序号（字幕按它定位交付地址） */
    val index: Int = -1,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    /** 媒体流码率（bit/s）；null / 0 表示服务端未提供。 */
    val bitRate: Int? = null,
    /** 归一后的帧率；优先 reference -> average -> real。 */
    val frameRate: Float? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val sampleRate: Int? = null,
    val profile: String? = null,
)

fun MediaStream.toFindroidMediaStream(jellyfinRepository: JellyfinRepository): FindroidMediaStream {
    return toFindroidMediaStream(baseUrl = jellyfinRepository.getBaseUrl())
}

internal fun MediaStream.toFindroidMediaStream(baseUrl: String): FindroidMediaStream {
    return FindroidMediaStream(
        title = title.orEmpty(),
        displayTitle = displayTitle,
        language = language.orEmpty(),
        type = type,
        codec = codec.orEmpty(),
        isExternal = isExternal,
        // deliveryUrl 为空时不要拼出 "http://hostnull" 这种脏地址：这里直接给 null，
        // 调用方（外挂字幕 / 字幕面板）会按「拿不到文件」处理
        path = deliveryUrl?.let { baseUrl + it },
        channelLayout = channelLayout,
        videoRangeType = videoRangeType,
        height = height,
        width = width,
        videoDoViTitle = videoDoViTitle,
        index = index,
        isDefault = isDefault,
        isForced = isForced,
        bitRate = bitRate.positiveOrNull(),
        frameRate =
            referenceFrameRate.positiveOrNull()
                ?: averageFrameRate.positiveOrNull()
                ?: realFrameRate.positiveOrNull(),
        bitDepth = bitDepth.positiveOrNull(),
        channels = channels.positiveOrNull(),
        sampleRate = sampleRate.positiveOrNull(),
        profile = profile?.takeIf { it.isNotBlank() },
    )
}

fun FindroidMediaStreamDto.toFindroidMediaStream(): FindroidMediaStream {
    return FindroidMediaStream(
        title = title,
        displayTitle = displayTitle,
        language = language,
        type = type,
        codec = codec,
        isExternal = isExternal,
        path = path,
        channelLayout = channelLayout,
        videoRangeType = VideoRangeType.fromNameOrNull(videoRangeType ?: ""),
        height = height,
        width = width,
        videoDoViTitle = videoDoViTitle,
        // 离线数据库没有存这三项：下载播放时退回「非默认、非强制、无序号」，
        // 字幕面板仍然按语言匹配，只是丢掉了 forced 的优先级判断
        index = -1,
        isDefault = false,
        isForced = false,
        bitRate = bitRate.positiveOrNull(),
        frameRate = frameRate.positiveOrNull(),
        bitDepth = bitDepth.positiveOrNull(),
        channels = channels.positiveOrNull(),
        sampleRate = sampleRate.positiveOrNull(),
        profile = profile?.takeIf { it.isNotBlank() },
    )
}

private fun Int?.positiveOrNull(): Int? = this?.takeIf { it > 0 }

private fun Float?.positiveOrNull(): Float? = this?.takeIf { it.isFinite() && it > 0f }
