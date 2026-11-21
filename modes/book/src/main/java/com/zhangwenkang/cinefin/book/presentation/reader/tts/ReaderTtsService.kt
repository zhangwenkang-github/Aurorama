package com.zhangwenkang.cinefin.book.presentation.reader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 听书后台播放服务（W6 T9 / READER-32–36，定稿六 §5）：前台 Service + MediaSession 通知。
 *
 * 只做「镜像 + 远程控制」：播放本身在阅读页的 [ReaderTtsController]，服务把 [ReaderTtsSession.state] 画成通知与媒体状态，通知 / 锁屏 /
 * 耳机键的动作回灌给 [ReaderTtsSession.Controls]。状态回到 `Idle`（停止 / 定时到点 / 书末）即自行退出。
 *
 * 通知骨架独立于 `modes/music` 与 `app/phone` 的播放器通知（不跨模块依赖），只共用平台 `androidx.media` 的 MediaSession +
 * MediaStyle。
 */
class ReaderTtsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSessionCompat? = null
    private var observation: Job? = null
    private var foreground = false
    private var sawActiveState = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        mediaSession =
            MediaSessionCompat(this, SESSION_TAG).apply {
                setCallback(
                    object : MediaSessionCompat.Callback() {
                        override fun onPlay() {
                            ReaderTtsSession.controls?.play()
                        }

                        override fun onPause() {
                            ReaderTtsSession.controls?.pause()
                        }

                        override fun onSkipToNext() {
                            ReaderTtsSession.controls?.next()
                        }

                        override fun onSkipToPrevious() {
                            ReaderTtsSession.controls?.previous()
                        }

                        override fun onStop() {
                            ReaderTtsSession.controls?.stop()
                        }
                    }
                )
                isActive = true
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> ReaderTtsSession.controls?.toggle()
            ACTION_NEXT -> ReaderTtsSession.controls?.next()
            ACTION_PREVIOUS -> ReaderTtsSession.controls?.previous()
            ACTION_STOP -> {
                ReaderTtsSession.controls?.stop()
                // 不 detach：控制条「停止」（W6 T12）与定时到点都走这里，阅读页仍在场，
                // 保留 controls 才能从「更多 → 听书」干净重启（页面退出时由 onDispose detach）。
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_START,
            null -> {
                if (ReaderTtsSession.state.value.playback == TtsPlayback.Idle) {
                    // 阅读页先发 START 又立刻 stop 的竞态：不要留下一条「已停止」的常驻通知。
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                ensureForeground(ReaderTtsSession.state.value)
                observeState()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 被系统清理任务时同步收摊：阅读页进程里的朗读一并停掉，避免留下孤儿通知。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        ReaderTtsSession.controls?.stop()
        ReaderTtsSession.detach()
        stopSelfSafely()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        observation?.cancel()
        scope.cancel()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    private fun observeState() {
        if (observation != null) return
        observation = scope.launch {
            ReaderTtsSession.state.collect { state ->
                if (state.playback != TtsPlayback.Idle) {
                    sawActiveState = true
                    updateSession(state)
                    ensureForeground(state)
                } else if (sawActiveState) {
                    stopSelfSafely()
                }
            }
        }
    }

    private fun ensureForeground(state: ReaderTtsUiState) {
        val notification = buildNotification(state)
        if (!foreground) {
            val started = runCatching {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    } else {
                        0
                    },
                )
            }
                .onFailure { Timber.w(it, "听书通知进入前台失败") }
                .isSuccess
            if (!started) {
                stopSelfSafely()
                return
            }
            foreground = true
        } else {
            runCatching {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            }
                .onFailure { Timber.w(it, "更新听书通知失败") }
        }
    }

    private fun buildNotification(state: ReaderTtsUiState): Notification {
        val contentIntent =
            ReaderTtsSession.readerIntent?.let { reader ->
                PendingIntent.getActivity(this, 0, reader, pendingIntentFlags())
            }
                ?: packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                    PendingIntent.getActivity(this, 0, launch, pendingIntentFlags())
                }
        val playing = state.playback == TtsPlayback.Playing
        val title = ReaderTtsSession.bookTitle.ifBlank { "听书" }
        val text =
            when (state.playback) {
                TtsPlayback.Preparing -> "正在准备章节…"
                TtsPlayback.Idle -> "已停止"
                else -> state.active?.text?.take(NOTIFICATION_TEXT_CHARS) ?: "等待朗读"
            }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.zhangwenkang.cinefin.core.R.drawable.ic_speaker)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(state.playback != TtsPlayback.Idle)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                0,
                "上一句",
                servicePendingIntent(ACTION_PREVIOUS, REQUEST_PREVIOUS),
            )
            .addAction(
                0,
                if (playing) "暂停" else "播放",
                servicePendingIntent(ACTION_TOGGLE, REQUEST_TOGGLE),
            )
            .addAction(0, "下一句", servicePendingIntent(ACTION_NEXT, REQUEST_NEXT))
            .addAction(0, "停止", servicePendingIntent(ACTION_STOP, REQUEST_STOP))
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateSession(state: ReaderTtsUiState) {
        val session = mediaSession ?: return
        val title = ReaderTtsSession.bookTitle.ifBlank { "听书" }
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(
                    MediaMetadataCompat.METADATA_KEY_ARTIST,
                    state.active?.text?.take(NOTIFICATION_TEXT_CHARS) ?: "",
                )
                .build()
        )
        val playing = state.playback == TtsPlayback.Playing
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                )
                .build()
        )
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ReaderTtsService::class.java).setAction(action),
            pendingIntentFlags(),
        )

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun stopSelfSafely() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        }
        foreground = false
        stopSelf()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    // 朗读控制不是「新消息」：不发声、不震动，只在通知栏与锁屏常驻。
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    description = "听书播放控制"
                    setShowBadge(false)
                }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val SESSION_TAG = "CinefinReaderTts"
        private const val CHANNEL_ID = "reader_tts"
        private const val CHANNEL_NAME = "听书"
        private const val NOTIFICATION_ID = 0x7EAD
        private const val NOTIFICATION_TEXT_CHARS = 120
        private const val REQUEST_TOGGLE = 10
        private const val REQUEST_PREVIOUS = 11
        private const val REQUEST_NEXT = 12
        private const val REQUEST_STOP = 13

        internal const val ACTION_START = "com.zhangwenkang.cinefin.book.tts.START"
        internal const val ACTION_TOGGLE = "com.zhangwenkang.cinefin.book.tts.TOGGLE"
        internal const val ACTION_NEXT = "com.zhangwenkang.cinefin.book.tts.NEXT"
        internal const val ACTION_PREVIOUS = "com.zhangwenkang.cinefin.book.tts.PREVIOUS"
        internal const val ACTION_STOP = "com.zhangwenkang.cinefin.book.tts.STOP"
    }
}
