package com.zhangwenkang.cinefin.player.core.domain.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 一条可供选择的字幕来源（来自 Jellyfin 的媒体流清单，而不是播放器解析出来的轨道）。
 *
 * 为什么不用播放器的 [androidx.media3.common.Tracks]：本项目的字幕面板要支持「延迟 / 双语 / 外观」， 播放内核原生的字幕渲染做不到（Media3
 * 没有字幕时间偏移 API，也一次只能渲染一条文字轨）， 所以字幕改由自研渲染管线负责——它需要一个能直接下载、解析的独立字幕文件地址。
 *
 * [uri] 为空表示这条流拿不到独立字幕文件（例如服务器没给 DeliveryUrl）， 这时只能退回播放内核的原生渲染，面板里会标注为不可调节。
 */
@Parcelize
data class PlayerSubtitleSource(
    /** Jellyfin 字幕流序号；同一媒体内唯一 */
    val index: Int,
    /** 显示名（优先用服务器给的标题，缺失时回退语言） */
    val title: String,
    /** 归一化后的语言标签（zh-Hans / zh-Hant / en ...），识别不出来时为空串 */
    val language: String,
    /** 字幕文件地址；空串 = 不能自研渲染 */
    val uri: String,
    /** 源编码：subrip / ass / webvtt / pgs ... */
    val codec: String,
    /** 图形字幕（PGS / VOBSUB 等）只能由播放内核渲染 */
    val isGraphic: Boolean = false,
    val isExternal: Boolean = false,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
) : Parcelable {
    /** 能否交给自研字幕渲染管线：得能拿到文件，且是文本字幕 */
    val isTextBased: Boolean
        get() = !isGraphic && uri.isNotBlank()

    /** 面板里显示的副标题：格式 + 外挂/内嵌标记 */
    val formatLabel: String
        get() = codec.uppercase()

    companion object {
        /** 图形字幕编码：这些只能交给播放内核渲染，解析不出文本 */
        private val GRAPHIC_CODECS =
            setOf(
                "pgs",
                "pgssub",
                "hdmv_pgs_subtitle",
                "dvdsub",
                "dvd_subtitle",
                "dvbsub",
                "dvb_subtitle",
                "vobsub",
                "xsub",
                "dvb_teletext",
            )

        fun isGraphicCodec(codec: String): Boolean = codec.lowercase() in GRAPHIC_CODECS
    }
}
