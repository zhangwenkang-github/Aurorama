package com.zhangwenkang.cinefin.local

import com.zhangwenkang.cinefin.models.FindroidMediaStream
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import org.jellyfin.sdk.model.api.MediaStreamType

/**
 * 本地视频 → [FindroidSource]（播放链路统一入口）。
 *
 * 本地库的文件直接以 SAF `content://` 交付；VIDEO-48 的同目录外挂字幕随索引带在这里， 走与服务器外挂字幕完全相同的面板 / 渲染通路。
 */
internal fun LocalPlayableSource.toFindroidSource(): FindroidSource =
    FindroidSource(
        id = "local-$itemId",
        name = "本地文件",
        type = FindroidSourceType.LOCAL,
        path = uri,
        size = 0L,
        mediaStreams = subtitleStreams(),
        downloadId = null,
        transcodingPath = null,
    )

/** 外挂字幕流：`isExternal=true` + `path` 为 SAF 文档 URI，序号按文件名顺序从 0 起（本地源没有内封轨）。 */
private fun LocalPlayableSource.subtitleStreams(): List<FindroidMediaStream> =
    subtitles.mapIndexed { position, subtitle ->
        FindroidMediaStream(
            title = subtitle.fileName,
            displayTitle = subtitle.tag.ifBlank { subtitle.fileName },
            language = subtitle.tag,
            type = MediaStreamType.SUBTITLE,
            codec = subtitle.codec,
            isExternal = true,
            path = subtitle.uri,
            channelLayout = null,
            videoRangeType = null,
            height = null,
            width = null,
            videoDoViTitle = null,
            index = position,
            isDefault = false,
            isForced = false,
        )
    }
