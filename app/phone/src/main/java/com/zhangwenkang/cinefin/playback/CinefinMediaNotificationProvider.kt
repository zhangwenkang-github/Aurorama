package com.zhangwenkang.cinefin.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.bitmapConfig
import com.google.common.collect.ImmutableList
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_EPISODE_NUMBER
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_SEASON_NUMBER
import com.zhangwenkang.cinefin.player.local.R as PlayerR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 播放通知（阶段 4.2 / D4）。
 *
 * 为什么不用 Media3 的默认通知：默认实现只依据"会话是否有控制者"来决定是否显示， 与"播放页直接操作进程内播放器实例"的架构不匹配（表现为：会话正常、通知一直不出现）。
 * 这里自己构建通知，同时把可控性拿回来：
 *
 * - 内容：标题 + 季集副标题 + 进度条 + 五个传输按钮（上一集 / 快退 / 播放暂停 / 快进 / 下一集）；
 * - 点通知回播放页，划线删除 = 停止播放并收掉服务；
 * - 封面：等 `MediaItem` 带上 artworkUri 后再补（见 PLAYER_PLAN 阶段 4 待办）。
 *
 * Android 的媒体通知最多显示 5 个按钮，所以"关闭"不做成第 6 个按钮， 而是用通知的删除手势 + 播放页返回键承担。
 */
class CinefinMediaNotificationProvider(
    private val context: Context,
    /** W68：当前媒体项是否为音乐（决定通知内容点击的落点）。 */
    private val isMusicItem: () -> Boolean,
) : MediaNotification.Provider {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "cinefin_playback"
        private const val CHANNEL_NAME = "播放控制"

        /** 通知封面解码边长：通知栏大图标用不到原图，512px 足够且省内存 */
        private const val ARTWORK_SIZE_PX = 512
    }

    /** 通知构建与封面加载都在主线程：用 Main.immediate 保证 callback 线程正确， 图片解码在 IO 线程做（通知线程绝不能阻塞）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 当前封面缓存：只留最近一张，避免整剧播放时把每集封面都攒在内存里 */
    private var cachedArtworkUri: String? = null
    private var cachedArtwork: Bitmap? = null

    /** 正在加载 / 加载失败过的封面 uri，避免 Media3 每次重建通知都重复请求 */
    private val loadingArtwork = mutableSetOf<String>()
    private val failedArtwork = mutableSetOf<String>()

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
                // W68：音乐 → 歌手（无则空）；视频 → 现有季集副标题
                isMusicItem() -> metadata.artist?.toString().orEmpty()
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
                .setContentIntent(contentIntent())
                .setDeleteIntent(actionFactory.createNotificationDismissalIntent(mediaSession))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setOngoing(player.isPlaying)

        /*
         * 封面（§1.4）：命中缓存就带上；没有就异步加载，加载完成后再回调刷新一次通知。
         * 通知线程（主线程）在这里绝不做网络 / 解码，避免卡住通知体系。
         */
        val artworkUri = metadata.artworkUri?.toString()?.takeIf { it.isNotBlank() }
        val largeIcon = artworkUri?.takeIf { it == cachedArtworkUri }?.let { cachedArtwork }
        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        } else if (artworkUri != null && artworkUri !in failedArtwork) {
            requestArtwork(
                artworkUri,
                mediaSession,
                customLayout,
                actionFactory,
                onNotificationChangedCallback,
            )
        }

        val duration = player.duration
        if (duration > 0L) {
            val position = player.currentPosition.coerceIn(0L, duration)
            builder.setProgress(1000, ((position * 1000) / duration).toInt(), false)
        }

        /*
         * 传输按钮（W68b）：5 个槽位 = 上一集 / 快进 / 播放暂停 / 下一集 / 关闭。
         *
         * 用户 2026-10-04 复验要求系统媒体面板（通知栏 + 锁屏）出现「关闭」键；媒体面板最多 5 个动作，
         * 因此按负责人口径把「快退」让位给「关闭」（快进保留，切集与播放/暂停保留）。
         * 关闭 = COMMAND_STOP：停止播放并收起通知 / 前台服务，不删除任何数据、不清队列快照。
         */
        builder.addAction(
            mediaAction(
                actionFactory,
                mediaSession,
                CoreR.drawable.ic_skip_back,
                PlayerR.string.player_controls_previous_episode,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
        )
        builder.addAction(
            mediaAction(
                actionFactory,
                mediaSession,
                CoreR.drawable.ic_fast_forward,
                PlayerR.string.player_controls_fast_forward,
                Player.COMMAND_SEEK_FORWARD,
            )
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
            mediaAction(
                actionFactory,
                mediaSession,
                CoreR.drawable.ic_skip_forward,
                PlayerR.string.player_controls_next_episode,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            )
        )
        builder.addAction(
            mediaAction(
                actionFactory,
                mediaSession,
                CoreR.drawable.ic_close,
                CoreR.string.close,
                Player.COMMAND_STOP,
            )
        )

        // 紧凑视图（媒体面板折叠态）：快进 / 播放暂停 / 关闭（索引对应上方动作列表）。
        // W68b 真机（K60 MIUI）实测：第 5 个展开动作不会出现在系统媒体卡片上，必须让「关闭」进 compact。
        builder.setStyle(
            MediaStyleNotificationHelper.MediaStyle(mediaSession)
                .setShowActionsInCompactView(1, 2, 4)
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

    /**
     * 异步加载通知封面（§1.4）。
     *
     * 调用方（主线程构建通知）先拿到一张不带封面的通知；图片解码放在 IO 线程， 完成后用 [MediaNotification.Provider.Callback] 让 Media3
     * 重建一次通知， 这次就能命中缓存、把封面画上去。
     */
    private fun requestArtwork(
        uri: String,
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        callback: MediaNotification.Provider.Callback,
    ) {
        if (!loadingArtwork.add(uri)) return
        scope.launch {
            val bitmap =
                withContext(Dispatchers.IO) {
                    val request =
                        ImageRequest.Builder(context)
                            .data(uri)
                            .size(ARTWORK_SIZE_PX)
                            // 通知大图标要软件位图：ARGB_8888 配置下不会拿到硬件位图，
                            // 硬件位图在部分 ROM 的通知栏里画不出来
                            .bitmapConfig(Bitmap.Config.ARGB_8888)
                            .build()
                    runCatching { SingletonImageLoader.get(context).execute(request) }
                        .getOrNull()
                        ?.image
                        ?.let { image -> (image as? BitmapImage)?.bitmap }
                }
            loadingArtwork.remove(uri)
            if (bitmap == null) {
                failedArtwork.add(uri)
                // 不打完整地址：Jellyfin 的图片地址带 api_key
                Timber.w("通知封面加载失败（%s）", uri.substringBefore('?'))
                return@launch
            }
            cachedArtworkUri = uri
            cachedArtwork = bitmap
            Timber.d("通知封面就绪：%d×%d，刷新通知", bitmap.width, bitmap.height)
            callback.onNotificationChanged(
                createNotification(mediaSession, customLayout, actionFactory, callback)
            )
        }
    }

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

    /**
     * 点通知回到对应界面（W68：音乐 → 音乐播放覆盖层；视频 → 视频播放页）。
     *
     * 刻意不带 `itemId`：页面看到没有条目参数、而播放器里已有内容时，只把自己带回前台， 不重新拉流，避免"点一下通知进度就跳回开头"。
     */
    private fun contentIntent(): PendingIntent =
        buildSessionActivityPendingIntent(
            context = context,
            isMusicItem = isMusicItem(),
        )
}
