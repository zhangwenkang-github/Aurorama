package com.zhangwenkang.cinefin.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.zhangwenkang.cinefin.PlayerActivity
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.player.local.R as PlayerR
import com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import timber.log.Timber

/**
 * 播放前台服务（阶段 4）。
 *
 * 职责：
 * 1. 承载 [MediaSession]，让通知栏 / 锁屏 / 蓝牙 / 手表这些"系统入口"能控制播放（4.1）；
 * 2. 播放进入前台服务（`foregroundServiceType=mediaPlayback`），离开播放页后不被回收（4.3）；
 * 3. 通知里的按钮由 Media3 依据会话可用命令生成，另加一个「关闭」按钮（4.2）。
 *
 * 播放器实例不在服务里单独创建，而是复用进程级单例 [PlayerHolder]：播放页与通知栏操作的是
 * 同一个实例，命令无需跨进程往返，行为与页面上点按完全一致。
 */
@AndroidEntryPoint
class CinefinPlaybackService : MediaSessionService() {

    @Inject lateinit var playerHolder: PlayerHolder

    private var mediaSession: MediaSession? = null

    /** 常驻控制者：只为让媒体通知有"控制来源"，不参与命令分发（见 [connectSelfController]） */
    private var selfController: MediaController? = null

    override fun onCreate() {
        super.onCreate()
        // 媒体样式通知（标题 / 副标题 / 进度 / 传输按钮）由自己的 provider 提供（阶段 4.2）
        setMediaNotificationProvider(CinefinMediaNotificationProvider(this))
        val player = playerHolder.player
        mediaSession =
            MediaSession.Builder(this, player)
                .setSessionActivity(sessionActivityIntent())
                .setCustomLayout(
                    listOf(
                        CommandButton.Builder(CoreR.drawable.ic_close)
                            .setDisplayName(getString(PlayerR.string.player_controls_exit))
                            .setPlayerCommand(Player.COMMAND_STOP)
                            .build()
                    )
                )
                .build()
        connectSelfController()
        Timber.d("播放会话建立：backend=%s", playerHolder.backend)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        Timber.d(
            "更新播放通知：需要前台=%s 正在播=%s 待播=%s 条目=%d",
            startInForegroundRequired,
            session.player.isPlaying,
            session.player.playWhenReady,
            session.player.mediaItemCount,
        )
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    /**
     * 从最近任务里划掉 App：
     * - 仍在播放（后台播放开启）→ 保留前台服务与通知，继续可控；
     * - 已暂停或没有内容 → 收摊，服务与通知一起结束。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    /**
     * 自我连接一个 [MediaController]。
     *
     * Media3 的媒体通知只在该会话**有控制者连接**时才显示
     * （`MediaNotificationManager.shouldShowNotification` 会先取连接中的 controller）。
     * 播放页操作的是进程内共享的播放器实例，本身不产生 controller，所以由服务挂一个常驻控制者，
     * 通知栏 / 锁屏 / 车机才会出现控制入口。它只维持"被控制"状态，不参与实际命令分发。
     */
    private fun connectSelfController() {
        val token = SessionToken(this, ComponentName(this, CinefinPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { selfController = it }
                    .onFailure { Timber.w(it, "建立会话控制者失败，通知栏可能不可见") }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    override fun onDestroy() {
        selfController?.release()
        selfController = null
        mediaSession?.release()
        mediaSession = null
        // 会话结束即释放播放器：避免"服务没了、播放器还在"的半死状态
        playerHolder.release()
        Timber.d("播放服务已停止")
        super.onDestroy()
    }

    /**
     * 点通知回到播放页。
     *
     * 刻意不带 `itemId`：播放页看到没有条目参数、而播放器里已有内容时，只把自己带会前台，
     * 不重新拉流，避免"点一下通知进度就跳回开头"。
     */
    private fun sessionActivityIntent(): PendingIntent {
        val intent =
            Intent(this, PlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
