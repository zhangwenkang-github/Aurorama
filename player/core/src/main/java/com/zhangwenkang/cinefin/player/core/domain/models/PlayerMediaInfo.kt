package com.zhangwenkang.cinefin.player.core.domain.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 播放信息面板的数据快照（§1.8）。
 *
 * 字段分两类来源：
 * - **媒体源（Jellyfin）**：容器 / 文件大小 / 路径 / 编码 / 分辨率等元数据，两个内核都拿得到同一份；
 * - **播放内核（ExoPlayer / mpv）**：解码时的真实编码、帧率、码率、HDR 等，取不到就是 null。
 *
 * 约定：**null = 该字段在这条链路上取不到**，UI 一律降级显示「—」， 不允许把内核差异变成空白行或崩溃（见 `PlayerMediaInfoFormat`）。
 */
@Parcelize
data class PlayerMediaInfo(
    /** 容器（MKV / MP4 / HLS…），来源是媒体地址后缀或内核解复用器名 */
    val container: String? = null,
    /** 视频编码（HEVC / H.264 / AV1…） */
    val videoCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** 视频码率（bit/s） */
    val videoBitrate: Int? = null,
    /** 帧率（fps） */
    val frameRate: Float? = null,
    /** 动态范围（HDR10 / HLG / 杜比视界 / SDR…） */
    val hdr: String? = null,
    /** 音频编码（AAC / E-AC-3…） */
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    /** 音频码率（bit/s） */
    val audioBitrate: Int? = null,
    /** 音频采样率（Hz） */
    val audioSampleRate: Int? = null,
    /** 文件大小（字节）；转码 / 流式地址取不到时为 null */
    val fileSizeBytes: Long? = null,
    /** 播放地址（已去掉鉴权参数；只用于展示） */
    val path: String? = null,
) : Parcelable
