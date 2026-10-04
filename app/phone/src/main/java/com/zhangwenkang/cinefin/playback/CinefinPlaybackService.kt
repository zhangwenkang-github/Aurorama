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
 * 播放器实例不在服务里单独创建，而是复用进程级单例 [PlayerHolder]：播放页与通知栏操作的是 同一个实例，命令无需跨进程往返，行为与页面上点按完全一致。
 */
@AndroidEntryPoint
class CinefinPlaybackService : MediaSessionService() {

    @Inject lateinit var playerHolder: PlayerHolder

    private var mediaSession: MediaSession? = null

    /** 常驻控制者：只为让媒体通知有"控制来源"，不参与命令分发（见 [connectSelfController]） */
    private var selfController: MediaController? = null

    /** W68：当前会话点击路由是否指向音乐界面（只在媒体类型变化时才重设 sessionActivity）。 */
    private var sessionActivityIsMusicItem = false

    /**
     * W68：音乐 / 视频共用同一个播放器实例，媒体项切换时同步会话点击路由。
     *
     * `onEvents` 覆盖媒体项 / 时间线 / 元数据的所有变化；[syncSessionActivityTarget] 内部先比较类型， 类型没变时不做任何事。
     */
    private val mediaTypeListener =
        object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                syncSessionActivityTarget()
            }
        }

    override fun onCreate() {
        super.onCreate()
        // 媒体样式通知（标题 / 副标题 / 进度 / 传输按钮）由自己的 provider 提供（阶段 4.2）；
        // W68：通知内容点击按当前媒体类型分派（音乐 → 音乐界面，视频 → 视频播放页）。
        setMediaNotificationProvider(
            CinefinMediaNotificationProvider(this) { playerHolder.isCurrentItemMusic }
        )
        // 优先复用"活动实例"：音乐会话期间实例已被 audioSession() 固定为 ExoPlayer，
        // 若这里按偏好重建（例如偏好 mpv）会把正在播放的音乐换掉
        val player = playerHolder.existingPlayer ?: playerHolder.player
        mediaSession = buildMediaSession(player)
        connectSelfController()
        Timber.d("播放会话建立：backend=%s", playerHolder.backend)
    }

    /**
     * 每次 `startService` 都会走到这里。
     *
     * 播放器实例可能已被切换（例如视频回退 mpv 之后再起音乐，`audioSession()` 会把实例重建为 ExoPlayer），这时旧 [MediaSession]
     * 仍指向已释放的实例，通知 / 锁屏会失联；检测到实例不一致 就重建会话与常驻控制者。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        rebuildSessionIfPlayerChanged()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun rebuildSessionIfPlayerChanged() {
        val active = playerHolder.existingPlayer ?: return
        if (mediaSession?.player === active) {
            // 同一实例也可能在音乐 / 视频条目之间切换：同步点击路由（W68）
            syncSessionActivityTarget()
            return
        }
        Timber.i("播放器实例已切换，重建播放会话")
        selfController?.release()
        selfController = null
        mediaSession?.player?.removeListener(mediaTypeListener)
        mediaSession?.release()
        mediaSession = buildMediaSession(active)
        connectSelfController()
    }

    private fun buildMediaSession(player: Player): MediaSession {
        sessionActivityIsMusicItem = playerHolder.isCurrentItemMusic
        val session =
            MediaSession.Builder(this, player)
                .setSessionActivity(sessionActivityIntent(sessionActivityIsMusicItem))
                .setCustomLayout(
                    listOf(
                        CommandButton.Builder(CoreR.drawable.ic_close)
                            .setDisplayName(getString(PlayerR.string.player_controls_exit))
                            .setPlayerCommand(Player.COMMAND_STOP)
                            .build()
                    )
                )
                .build()
        player.addListener(mediaTypeListener)
        return session
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
     * Media3 的媒体通知只在该会话**有控制者连接**时才显示 （`MediaNotificationManager.shouldShowNotification` 会先取连接中的
     * controller）。 播放页操作的是进程内共享的播放器实例，本身不产生 controller，所以由服务挂一个常驻控制者， 通知栏 / 锁屏 /
     * 车机才会出现控制入口。它只维持"被控制"状态，不参与实际命令分发。
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
        mediaSession?.player?.removeListener(mediaTypeListener)
        selfController?.release()
        selfController = null
        mediaSession?.release()
        mediaSession = null
        // 会话结束即释放播放器：避免"服务没了、播放器还在"的半死状态。
        // W68：音乐会话活跃时 [PlayerHolder.release] 内部会拒绝释放，保证退出 UI 后音乐继续可控。
        playerHolder.release()
        Timber.d("播放服务已停止")
        super.onDestroy()
    }

    /**
     * W68：媒体类型切换时更新会话点击路由。
     *
     * 通知栏的内容点击由 [CinefinMediaNotificationProvider] 每次重建通知时计算；这里负责 MediaSession 的
     * `sessionActivity`（锁屏 / 手表 / 蓝牙 / 车机等系统入口）。
     */
    private fun syncSessionActivityTarget() {
        val session = mediaSession ?: return
        val isMusic = playerHolder.isCurrentItemMusic
        if (isMusic == sessionActivityIsMusicItem) return
        sessionActivityIsMusicItem = isMusic
        session.setSessionActivity(sessionActivityIntent(isMusic))
        Timber.i("播放条目类型切换：会话点击路由 → %s", if (isMusic) "音乐播放界面" else "视频播放页")
    }

    private fun sessionActivityIntent(isMusicItem: Boolean): PendingIntent =
        buildSessionActivityPendingIntent(
            context = this,
            isMusicItem = isMusicItem,
        )
}
