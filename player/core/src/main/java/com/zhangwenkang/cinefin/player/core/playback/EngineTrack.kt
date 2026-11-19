package com.zhangwenkang.cinefin.player.core.playback

/** 轨道类型：与 media3 的 `C.TRACK_TYPE_*` 一一对应，但不依赖 media3。 */
enum class EngineTrackType {
    VIDEO,
    AUDIO,
    SUBTITLE,
}

/**
 * 内核视角的单条轨道（W4-T3a-1）。
 *
 * [id] 是内核内的不透明 token：Exo 用 `group/track` 索引编码、mpv 用轨道 id，格式属于实现细节。
 * 调用方只把它当作「同一播放会话内稳定」的引用——内核原地切换后重选轨、比较选中态都靠它， 不得解析、持久化或跨会话复用。
 *
 * 纯数据类，无 Android 依赖，JVM 单测可直接断言。
 */
data class EngineTrack(
    /** 内核内的稳定 id（不透明 token，见类注释） */
    val id: String,
    val type: EngineTrackType,
    /** BCP-47 语言标签；未知为 null */
    val language: String?,
    val title: String?,
    /** 编码名小写（ass / pgsub / aac / h264 …），供图形字幕判定与跨内核映射 */
    val codec: String?,
    /** 是否外挂轨（容器外附加的字幕 / 音轨） */
    val isExternal: Boolean,
    /** 是否为该类型的默认轨 */
    val isDefault: Boolean,
    /** 是否为强制轨（forced subtitle 常见） */
    val isForced: Boolean,
)
