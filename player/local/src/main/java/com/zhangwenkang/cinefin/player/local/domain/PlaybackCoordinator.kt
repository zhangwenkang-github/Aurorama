package com.zhangwenkang.cinefin.player.local.domain

/**
 * 音视频互斥仲裁（W0 冻结接口，MU-8 / ARCHITECTURE §4.1）。
 *
 * 音乐与视频共用唯一播放器实例与单个 MediaSession，任何一方起播前都必须经过本接口：
 * - 音乐起播：先停视频队列并按播放上报协议发 `Sessions/Playing/Stopped`（挂起等待上报）；
 * - 视频起播：先停音乐会话（本地即时操作，不等待上报）。
 *
 * 实现由 W1（R2-SKELETON）提供，`@Singleton` 注入；两条路径禁止绕过它直接 `player.play()`。
 */
interface PlaybackCoordinator {
    /** 音乐起播前调用；挂起以等待视频停止上报完成。 */
    suspend fun onMusicStartRequested()

    /** 视频起播前调用；停音乐并释放音频焦点。 */
    fun onVideoStartRequested()
}
