package com.zhangwenkang.cinefin.playback

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.zhangwenkang.cinefin.music.data.MusicLyricsOverlayHost
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * [MusicLyricsOverlayHost] 的宿主实现（W23-MUSIC · D 组）。
 *
 * `modes:music` 只描述"该不该显示桌面歌词"，真正拉起 / 停掉悬浮窗 Service 由 App 层负责 （与 [CinefinPlaybackServiceStarter]
 * 同一套"模块定义接口、宿主实现"的约定）。
 */
@Singleton
class MusicLyricsOverlayHostImpl
@Inject
constructor(@ApplicationContext private val context: Context) : MusicLyricsOverlayHost {

    override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    override fun ensureOverlayService() {
        try {
            context.startService(Intent(context, MusicLyricsOverlayService::class.java))
        } catch (e: Exception) {
            // 后台启动受限 / 没有权限：桌面歌词只是增强能力，起不来不影响播放
            Timber.w(e, "桌面歌词：悬浮窗服务启动失败")
        }
    }

    override fun stopOverlayService() {
        runCatching { context.stopService(Intent(context, MusicLyricsOverlayService::class.java)) }
    }
}
