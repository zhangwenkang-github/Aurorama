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
)

fun MediaStream.toFindroidMediaStream(jellyfinRepository: JellyfinRepository): FindroidMediaStream {
    return FindroidMediaStream(
        title = title.orEmpty(),
        displayTitle = displayTitle,
        language = language.orEmpty(),
        type = type,
        codec = codec.orEmpty(),
        isExternal = isExternal,
        // deliveryUrl 为空时不要拼出 "http://hostnull" 这种脏地址：这里直接给 null，
        // 调用方（外挂字幕 / 字幕面板）会按「拿不到文件」处理
        path = deliveryUrl?.let { jellyfinRepository.getBaseUrl() + it },
        channelLayout = channelLayout,
        videoRangeType = videoRangeType,
        height = height,
        width = width,
        videoDoViTitle = videoDoViTitle,
        index = index,
        isDefault = isDefault,
        isForced = isForced,
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
    )
}
