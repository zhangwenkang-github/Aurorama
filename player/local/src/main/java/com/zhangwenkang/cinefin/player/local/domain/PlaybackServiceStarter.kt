package com.zhangwenkang.cinefin.player.local.domain

/**
 * 播放会话服务启动入口（W1 R2 新增）。
 *
 * `player:local` 不能反向依赖宿主 App 模块，因此由宿主（app:phone）实现本接口并通过 Hilt 绑定： 音乐起播时用它确保承载 `MediaSession`
 * 的前台服务已运行，通知栏 / 锁屏 / 蓝牙按键才有控制入口。
 */
interface PlaybackServiceStarter {
    /** 确保播放前台服务已启动；启动失败应静默降级（页面内播放不受影响）。 */
    fun ensureSessionService()
}
