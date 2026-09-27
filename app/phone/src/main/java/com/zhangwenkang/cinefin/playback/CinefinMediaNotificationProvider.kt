package com.zhangwenkang.cinefin.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.google.common.collect.ImmutableList
import com.zhangwenkang.cinefin.PlayerActivity
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_EPISODE_NUMBER
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_SEASON_NUMBER
import com.zhangwenkang.cinefin.player.local.R as PlayerR
import timber.log.Timber

/**
 * 播放通知（阶段 4.2 / D4）。
 *
 * 为什么不用 Media3 的默认通知：默认实现只依据"会话是否有控制者"来决定是否显示，
 * 与"播放页直接操作进程内播放器实例"的架构不匹配（表现为：会话正常、通知一直不出现）。
 * 这里自己构建通知，同时把可控性拿回来：
 *
 * - 内容：标题 + 季集副标题 + 进度条 + 五个传输按钮（上一集 / 快退 / 播放暂停 / 快进 / 下一集）；
 * - 点通知回播放页，划线删除 = 停止播放并收掉服务；
 * - 封面：等 `MediaItem` 带上 artworkUri 后再补（见 PLAYER_PLAN 阶段 4 待办）。
 *
 * Android 的媒体通知最多显示 5 个按钮，所以"关闭"不做成第 6 个按钮，
 * 而是用通知的删除手势 + 播放页返回键承担。
 */
class CinefinMediaNotificationProvider(private val context: Context) :
    MediaNotification.Provider {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "cinefin_playback"
        private const val CHANNEL_NAME = "播放控制"
    }

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val player = mediaSession.player
        val metadata = player.mediaMetadata
        val extras = metadata.extras
        val season = extras?.getInt(PLAYER_EXTRA_SEASON_NUMBER, -1) ?: -1
        val episode = extras?.getInt(PLAYER_EXTRA_EPISODE_NUMBER, -1) ?: -1
        val title = metadata.title?.toString().orEmpty().ifEmpty { CHANNEL_NAME }
        val subtitle =
            when {
                season >= 0 && episode >= 0 -> "S$season:E$episode"
                else -> ""
            }

        Timber.d("构建播放通知：%s %s", title, subtitle)
        /*
         * 渠道必须在这里确保存在：Android 14 上「前台服务通知的渠道不存在」会让 startForeground
         * 直接抛 RemoteServiceException（Bad notification for startForeground），进程当场崩溃。
         */
        ensureChannel()

        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(CoreR.drawable.ic_play)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setContentIntent(playerActivityIntent())
                .setDeleteIntent(
                    actionFactory.createNotificationDismissalIntent(mediaSession)
                )
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setOngoing(player.isPlaying)

        val duration = player.duration
        if (duration > 0L) {
            val position = player.currentPosition.coerceIn(0L, duration)
            builder.setProgress(1000, ((position * 1000) / duration).toInt(), false)
        }

        // 传输按钮：紧凑视图固定显示「快退 / 播放暂停 / 快进」三个
        builder.addAction(
            mediaAction(actionFactory, mediaSession, CoreR.drawable.ic_skip_back, PlayerR.string.player_controls_previous_episode, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        )
        builder.addAction(
            mediaAction(actionFactory, mediaSession, CoreR.drawable.ic_rewind, PlayerR.string.player_controls_rewind, Player.COMMAND_SEEK_BACK)
        )
        builder.addAction(
            mediaAction(
                actionFactory,
                mediaSession,
                if (player.isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play,
                PlayerR.string.player_controls_play_pause,
                Player.COMMAND_PLAY_PAUSE,
            )
        )
        builder.addAction(
            mediaAction(actionFactory, mediaSession, CoreR.drawable.ic_fast_forward, PlayerR.string.player_controls_fast_forward, Player.COMMAND_SEEK_FORWARD)
        )
        builder.addAction(
            mediaAction(actionFactory, mediaSession, CoreR.drawable.ic_skip_forward, PlayerR.string.player_controls_next_episode, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        )

        builder.setStyle(
            MediaStyleNotificationHelper.MediaStyle(mediaSession).setShowActionsInCompactView(1, 2, 3)
        )

        return MediaNotification(NOTIFICATION_ID, builder.build())
    }

    override fun handleCustomCommand(
        mediaSession: MediaSession,
        action: String,
        extras: Bundle,
    ): Boolean = false

    /** 渠道交给 Media3 创建（低重要性：不发声、不震动，只做常驻控制） */
    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        MediaNotification.Provider.NotificationChannelInfo(CHANNEL_ID, CHANNEL_NAME)

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                // 播放控制不是"新消息"：不发声、不震动，只在通知栏与锁屏常驻
                NotificationManager.IMPORTANCE_LOW,
            )
                .apply {
                    setShowBadge(false)
                    description = "播放进度与播放控制"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
        manager.createNotificationChannel(channel)
    }

    private fun mediaAction(
        actionFactory: MediaNotification.ActionFactory,
        mediaSession: MediaSession,
        iconRes: Int,
        titleRes: Int,
        command: Int,
    ): NotificationCompat.Action =
        actionFactory.createMediaAction(
            mediaSession,
            IconCompat.createWithResource(context, iconRes),
            context.getString(titleRes),
            command,
        )

    /** 点通知回到播放页（不带 itemId：页面据此接管正在跑的会话，不重新拉流） */
    private fun playerActivityIntent(): PendingIntent {
        val intent =
            Intent(context, PlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

}
