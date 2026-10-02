package com.zhangwenkang.cinefin.music.data

/**
 * 桌面歌词悬浮窗的宿主接口（W23-MUSIC · D 组）。
 *
 * 悬浮窗是 `WindowManager` 覆盖层 + 一个普通 Service，Service 类只能放在 `app:phone` （`modes:music` 不能反向依赖 App
 * 模块）。这里沿用 `PlaybackServiceStarter` 的"模块定义 接口、宿主提供实现"约定，由 App 层 Hilt 绑定实现。
 */
interface MusicLyricsOverlayHost {
    /** 是否已授予「显示在其他应用上层」（`SYSTEM_ALERT_WINDOW`）权限。 */
    fun canDrawOverlays(): Boolean

    /** 确保悬浮窗 Service 已启动；无权限或后台启动受限时静默失败（只记日志）。 */
    fun ensureOverlayService()

    /** 停止悬浮窗 Service（关闭开关 / 退出播放时调用）。 */
    fun stopOverlayService()
}
