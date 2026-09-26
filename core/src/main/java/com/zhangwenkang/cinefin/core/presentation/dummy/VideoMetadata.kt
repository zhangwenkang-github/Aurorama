package com.zhangwenkang.cinefin.core.presentation.dummy

import com.zhangwenkang.cinefin.models.AudioChannel
import com.zhangwenkang.cinefin.models.AudioCodec
import com.zhangwenkang.cinefin.models.DisplayProfile
import com.zhangwenkang.cinefin.models.Resolution
import com.zhangwenkang.cinefin.models.VideoCodec
import com.zhangwenkang.cinefin.models.VideoMetadata

val dummyVideoMetadata =
    VideoMetadata(
        size = 1000000000,
        videoTracks = emptyList(),
        audioTracks = emptyList(),
        subtitleTracks = emptyList(),
        resolution = listOf(Resolution.HD),
        videoCodecs = listOf(VideoCodec.AV1),
        displayProfiles = listOf(DisplayProfile.HDR10),
        audioChannels = listOf(AudioChannel.CH_5_1),
        audioCodecs = listOf(AudioCodec.OPUS),
        isAtmos = listOf(false),
    )
