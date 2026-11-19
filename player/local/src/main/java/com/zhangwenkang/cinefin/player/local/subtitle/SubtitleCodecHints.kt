package com.zhangwenkang.cinefin.player.local.subtitle

/**
 * Exo 路径下建议改走 mpv 的字幕编码（VIDEO-11）。
 *
 * ASS/SSA 的原生样式与 PGS/VobSub 等图形字幕只在 mpv（libass）下有完整支持；Exo 路径命中时字幕面板顶部出一条非阻断提示， 不改选轨、不弹对话框。
 */
fun isMpvPreferredSubtitleCodec(codec: String): Boolean =
    codec.trim().lowercase() in MPV_PREFERRED_CODECS

private val MPV_PREFERRED_CODECS =
    setOf(
        "ass",
        "ssa",
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
