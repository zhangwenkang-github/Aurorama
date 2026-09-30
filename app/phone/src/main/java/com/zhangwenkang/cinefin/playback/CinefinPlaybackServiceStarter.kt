package com.zhangwenkang.cinefin.playback

import android.content.Context
import android.content.Intent
import com.zhangwenkang.cinefin.player.local.domain.PlaybackServiceStarter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * [PlaybackServiceStarter] 的宿主实现（W1 R2）。
 *
 * 音乐起播时由 `modes:music` 经 Hilt 调用：确保 [CinefinPlaybackService] 已启动， 通知栏 / 锁屏 / 蓝牙按键才有控制入口。与播放页一致用
 * `startService`（用户在前台点击播放）。
 */
@Singleton
class CinefinPlaybackServiceStarter
@Inject
constructor(@ApplicationContext private val context: Context) : PlaybackServiceStarter {

    override fun ensureSessionService() {
        try {
            context.startService(Intent(context, CinefinPlaybackService::class.java))
        } catch (e: Exception) {
            // 通知 / 锁屏只是增强能力：起不来也不影响页面内播放
            Timber.e(e, "启动播放会话服务失败：通知与锁屏控制不可用")
        }
    }
}
