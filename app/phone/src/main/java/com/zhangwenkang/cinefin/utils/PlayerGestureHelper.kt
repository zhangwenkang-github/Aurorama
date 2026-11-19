package com.zhangwenkang.cinefin.utils

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewPropertyAnimator
import android.view.WindowInsets
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.load
import coil3.request.crossfade
import coil3.request.transformations
import coil3.transform.RoundedCornersTransformation
import com.zhangwenkang.cinefin.PlayerActivity
import com.zhangwenkang.cinefin.core.Constants
import com.zhangwenkang.cinefin.isControlsLocked
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerChapter
import com.zhangwenkang.cinefin.player.local.R as PlayerR
import com.zhangwenkang.cinefin.player.local.domain.PlayerExtraPreferences
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import com.zhangwenkang.cinefin.player.local.syncplay.SyncPlayTransportGate
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.PlaybackSpeedOptions
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign
import kotlinx.coroutines.Dispatchers
import timber.log.Timber

/** W4-T5：双指双击（还原 1.0x）的单击时限与双击间隔。 */
private const val TWO_FINGER_TAP_MAX_MS = 300L
private const val TWO_FINGER_DOUBLE_TAP_WINDOW_MS = 300L

/** W4-T5：双指双击判定容忍的缩放变化（超过即视为捏合，不算「点」）。 */
private const val TWO_FINGER_TAP_SCALE_TOLERANCE = 0.02f

/** W4-T5：「越久越快」长按倍速的刷新间隔（档位本身每 2s 一档，见 [SPEED_RAMP_INTERVAL_MS]）。 */
private const val SPEED_RAMP_TICK_MS = 200L

class PlayerGestureHelper(
    private val appPreferences: AppPreferences,
    private val activity: PlayerActivity,
    private val playerView: PlayerView,
    private val audioManager: AudioManager,
    /** W4-T8d SyncPlay 闸门：入组期间播放 / 定位手势只发组命令（null = 未装配）。 */
    private val syncPlayGate: SyncPlayTransportGate? = null,
    /** 单击画面：切换控制层显隐（控制层由 Compose 渲染，这里只发意图） */
    private val onSingleTap: () -> Unit = {},
) {
    /**
     * Tracks whether video content should fill the screen, cutting off unwanted content on the
     * sides. Useful on wide-screen phones to remove black bars from some movies.
     */
    var isZoomEnabled = false

    /**
     * 手势总闸：画中画等场景由 Activity 置为 true 临时关掉全部手势。
     *
     * 注意不要再用 `playerView.useController` 当开关——控制层改成 Compose 之后 它永远是 false，会把手势全禁掉。
     */
    var isSuspended: Boolean = false

    /**
     * Tracks a value during a swipe gesture (between multiple onScroll calls). When the gesture
     * starts it's reset to an initial value and gets increased or decreased (depending on the
     * direction) as the gesture progresses.
     */
    private var swipeGestureValueTrackerVolume = -1f
    private var swipeGestureValueTrackerBrightness = -1f
    private var swipeGestureValueTrackerProgress = -1L

    /**
     * W67c：本次滑动 seek 的**相对增量**（目标 − 手势开始时的基准位置）。
     *
     * 应用改走 [requestGestureSeek]：播放器未就绪则排队、就绪后补上，不再依赖滑动结束瞬间的绝对落点。
     */
    private var swipeGestureSeekDelta = 0L

    private var swipeGestureVolumeOpen = false
    private var swipeGestureBrightnessOpen = false
    private var swipeGestureProgressOpen = false

    private var lastScaleEvent: Long = 0

    /**
     * 长按倍速的档位（W74 U5）。
     *
     * 原先在 Activity 创建时读一次：播放页内改档位（设置面板 / 全局设置）要重开页面才生效。 改成「用时现读」——[enableSpeedIncrease]
     * 每次按下时取当前偏好，改完档位下一次长按立即生效。
     */
    private val playbackSpeedIncrease: Float
        get() =
            appPreferences.getValue(appPreferences.playerGesturesSpeedMultiplier).toFloatOrNull()
                ?: 2f

    private var lastPlaybackSpeed: Float = 0f

    /**
     * W20（§1.11 Trickplay 按需加载）：手势 seek HUD 的预览图提供者。
     *
     * 只做「查缓存 + 触发后台拉取」，未命中返回 null（HUD 先不显示图，拖动不受影响）； null = 本条目没有可用预览。
     */
    var trickplayFrameAt: ((Long) -> Bitmap?)? = null
    private val trickplayRoundedCorners = RoundedCornersTransformation(10f)
    private var currentTrickplayBitmap: Bitmap? = null

    private var currentNumberOfPointers: Int = 0

    // ---------- W4-T5：方向锁（VIDEO-26） ----------

    /** 本次手势已锁定的主轴；[GestureAxis.NONE] = 未达 10vp，不锁定也不反馈。 */
    private var gestureAxis = GestureAxis.NONE
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var gesturePointerCount = 0

    /** 方向锁阈值：10vp 按当前视图密度换算（K60 density 420 = 2.625 px/dp ⇒ 26.25px）。 */
    private val directionLockThresholdPx: Float
        get() = GESTURE_DIRECTION_LOCK_DP * playerView.resources.displayMetrics.density

    // ---------- W4-T5：双击连击递增（VIDEO-20） ----------

    private var tapCombo = TapComboState()

    // ---------- W4-T5：双指连续缩放 + 平移（VIDEO-24） ----------

    private var zoomScale = ZOOM_MIN_SCALE
    private var zoomTranslationX = 0f
    private var zoomTranslationY = 0f
    private var panLastX = 0f
    private var panLastY = 0f
    private var twoFingerDownAtMs = 0L
    private var twoFingerDownX = 0f
    private var twoFingerDownY = 0f
    private var twoFingerDownScale = ZOOM_MIN_SCALE
    private var lastTwoFingerTapAtMs = 0L

    private val twoFingerTapSlopPx: Float by lazy {
        ViewConfiguration.get(playerView.context).scaledTouchSlop * 2f
    }

    // ---------- W4-T5：「越久越快」长按倍速（VIDEO-23） ----------

    private var speedRampBaseSpeed = 0f
    private var speedRampStartAtMs = 0L

    private val speedRampTick =
        object : Runnable {
            override fun run() {
                val player = playerView.player ?: return
                val speed =
                    rampedPlaybackSpeed(
                        speedRampBaseSpeed,
                        SystemClock.elapsedRealtime() - speedRampStartAtMs,
                    )
                if (abs(player.playbackParameters.speed - speed) > 0.001f) {
                    player.setPlaybackSpeed(speed)
                    Timber.d("长按倍速：升档到 %s", PlaybackSpeedOptions.label(speed))
                }
                activity.binding.gestureSpeedText.text = PlaybackSpeedOptions.label(speed)
                playerView.postDelayed(this, SPEED_RAMP_TICK_MS)
            }
        }

    private val hideSeekComboHudAction = Runnable {
        activity.binding.gestureSeekHudLayout.visibility = View.GONE
        // 窗口结束：连击串清零，下一次双击重新从基准步长起步
        tapCombo = TapComboState()
    }

    private val tapGestureDetector =
        GestureDetector(
            playerView.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    onSingleTap()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    // Disables long press gesture if view is locked
                    if (isControlsLocked) return

                    // Stop long press gesture when more than 1 pointer
                    if (currentNumberOfPointers > 1) return

                    // 章节手势开启时优先跳章节，其余情况（中间区域、片源没有章节）回退到长按倍速
                    val chapterSkipEnabled =
                        appPreferences.getValue(appPreferences.playerGesturesChapterSkip)
                    if (!chapterSkipEnabled || !handleChapterSkip(e)) {
                        enableSpeedIncrease()
                    }
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // Disables double tap gestures if view is locked
                    if (isControlsLocked) return false

                    when (tapGestureZone(e.x, currentViewWidth())) {
                        TapGestureZone.REWIND -> rewind()
                        TapGestureZone.TOGGLE -> togglePlayback()
                        TapGestureZone.FORWARD -> fastForward()
                        TapGestureZone.EDGE_OUTSIDE -> Unit
                    }
                    return true
                }
            },
        )

    /**
     * 手势总开关关闭时的兜底检测器：**只保留「单击显隐控制层」**。
     *
     * 控制层收起后如果连单击都不响应，用户就再也唤不出控件了（真机走查发现的死角）； 双击快进、长按倍速、滑动 seek、双指缩放这些「会改变播放状态」的手势仍然全部屏蔽。
     */
    private val singleTapDetector =
        GestureDetector(
            playerView.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    onSingleTap()
                    return true
                }
            },
        )

    @SuppressLint("SetTextI18n")
    private fun enableSpeedIncrease() {
        // W4-T8d：入组期间倍速归组（漂移校正自己会写 0.95x / 1.05x），长按倍速不写本地引擎，避免与校正打架。
        if (syncPlayGate?.ownsTransport?.value == true) {
            Timber.d("SyncPlay 入组中：长按倍速跳过（倍速由组同步接管）")
            return
        }
        playerView.player?.let {
            if (it.isPlaying) {
                val base = playbackSpeedIncrease
                lastPlaybackSpeed = it.playbackParameters.speed
                val ramp = appPreferences.getValue(PlayerExtraPreferences.gesturesSpeedRamp)
                val applied = if (ramp) rampedPlaybackSpeed(base, 0L) else base
                it.setPlaybackSpeed(applied)
                activity.binding.gestureSpeedText.text = PlaybackSpeedOptions.label(applied)
                activity.binding.gestureSpeedLayout.visibility = View.VISIBLE
                Timber.d(
                    "长按倍速：起始档=%s 越久越快=%s",
                    PlaybackSpeedOptions.label(applied),
                    ramp,
                )
                // W4-T5（VIDEO-23）：「越久越快」开启后每 2s 升一档，松手在 releaseAction 恢复
                playerView.removeCallbacks(speedRampTick)
                if (ramp) {
                    speedRampBaseSpeed = base
                    speedRampStartAtMs = SystemClock.elapsedRealtime()
                    playerView.postDelayed(speedRampTick, SPEED_RAMP_TICK_MS)
                }
            }
        }
    }

    /** @return 是否已跳转章节；返回 false 表示当前位置没有可跳的章节，可回退到长按倍速 */
    private fun handleChapterSkip(e: MotionEvent): Boolean {
        if (isControlsLocked) {
            return false
        }

        return when (tapGestureZone(e.x, currentViewWidth())) {
            TapGestureZone.REWIND ->
                activity.viewModel.seekToPreviousChapter()?.let { chapter ->
                    displayChapter(chapter)
                    true
                } ?: false

            TapGestureZone.FORWARD -> {
                val player = playerView.player
                if (activity.viewModel.isLastChapter()) {
                    if (player != null && player.hasNextMediaItem()) {
                        activity.viewModel.submitSeek(
                            PlayerViewModel.SeekIntent.AdjacentMediaItem(forward = true),
                            "chapter-next-item",
                        )
                        true
                    } else {
                        false
                    }
                } else {
                    activity.viewModel.seekToNextChapter()?.let { chapter ->
                        displayChapter(chapter)
                        true
                    } ?: false
                }
            }

            TapGestureZone.TOGGLE,
            TapGestureZone.EDGE_OUTSIDE -> false
        }
    }

    private fun displayChapter(chapter: PlayerChapter) {
        activity.binding.progressScrubberTrickplay.visibility = View.GONE
        activity.binding.progressScrubberLayout.visibility = View.VISIBLE
        activity.binding.progressScrubberText.text = chapter.name ?: ""
        activity.binding.progressScrubberTarget.visibility = View.GONE
    }

    private fun fastForward() {
        doubleTapSeek(
            direction = 1,
            baseStepMs = appPreferences.getValue(appPreferences.playerSeekForwardInc),
        )
    }

    private fun rewind() {
        doubleTapSeek(
            direction = -1,
            baseStepMs = appPreferences.getValue(appPreferences.playerSeekBackInc),
        )
    }

    /**
     * W4-T5（VIDEO-20）：双击连击递增。
     *
     * 基准步长取设置值；800ms 窗口内第 2 次 ×2、第 3 次起 ×3 封顶。HUD 显示**累计值**，窗口超时清零。
     */
    private fun doubleTapSeek(direction: Int, baseStepMs: Long) {
        val step =
            nextTapCombo(
                state = tapCombo,
                baseStepMs = baseStepMs,
                direction = direction,
                nowMs = SystemClock.elapsedRealtime(),
            )
        tapCombo = step.state
        requestGestureSeek(step.deltaMs)
        showSeekComboHud(step.accumulatedMs)
        animateRipple(
            if (direction > 0) activity.binding.imageFfwdAnimationRipple
            else activity.binding.imageRewindAnimationRipple
        )
        Timber.d(
            "双击连击：第 %d 次 Δ=%d ms 累计=%d ms",
            step.state.count,
            step.deltaMs,
            step.accumulatedMs,
        )
    }

    /** 连击 HUD：累计值形如「快退 −30s」，窗口结束自动隐藏并清零。 */
    private fun showSeekComboHud(accumulatedMs: Long) {
        val seconds = abs(accumulatedMs) / 1000
        val formatted = (if (accumulatedMs >= 0) "+" else "−") + seconds + "s"
        val label =
            if (accumulatedMs >= 0) PlayerR.string.player_gesture_seek_forward
            else PlayerR.string.player_gesture_seek_back
        val hud = activity.binding.gestureSeekHudLayout
        val text = playerView.context.getString(label, formatted)
        activity.binding.gestureSeekHudText.text = text
        hud.visibility = View.VISIBLE
        hud.removeCallbacks(hideSeekComboHudAction)
        hud.postDelayed(hideSeekComboHudAction, TAP_COMBO_WINDOW_MS)
        Timber.d("连击 HUD：%s", text)
    }

    private fun togglePlayback() {
        // W4-T8d：入组期间双击播放/暂停只发组命令，由状态机随命令回写本地引擎。
        val gate = syncPlayGate
        if (gate?.ownsTransport?.value == true) {
            gate.togglePlayback()
        } else {
            playerView.player?.let { player -> player.playWhenReady = !player.playWhenReady }
        }
        animateRipple(activity.binding.imagePlaybackAnimationRipple)
    }

    /**
     * W67c：手势 seek 的统一入口（相对增量）。
     *
     * - 播放器已就绪：提交相对意图，由 reducer 统一 clamp 并等待确认；
     * - 未就绪 / 连续点击：一律由 ViewModel reducer 排队并保留最后目标，手势层不再维护第二份状态。
     *
     * @return 供反馈显示的目标位置（未就绪时按当前已知位置计算的近似值）
     */
    private fun requestGestureSeek(deltaMs: Long): Long {
        val player = playerView.player
        val basePositionMs = activity.viewModel.seekDisplayPositionMs()
        val targetMs = gestureSeekTarget(basePositionMs, deltaMs, player?.duration ?: 0L)
        // W4-T8d：入组期间定位只发组 SEEK 命令（相对增量口径，不直驱本地引擎）。
        val gate = syncPlayGate
        if (gate?.ownsTransport?.value == true) {
            gate.requestSeekRelative(deltaMs)
            Timber.d("手势 seek（SyncPlay 组命令）：Δ=%d → %d ms", deltaMs, targetMs)
            return targetMs
        }
        activity.viewModel.submitSeek(
            PlayerViewModel.SeekIntent.Relative(deltaMs),
            "gesture",
        )
        Timber.d(
            "手势 seek：Δ=%d → %d ms（state=%d, isPlaying=%s, duration=%d）",
            deltaMs,
            targetMs,
            player?.playbackState ?: -1,
            player?.isPlaying ?: false,
            player?.duration ?: -1L,
        )
        return targetMs
    }

    private fun animateRipple(image: ImageView) {
        image.animateSeekingRippleStart().withEndAction { resetRippleImage(image) }.start()
    }

    private fun ImageView.animateSeekingRippleStart(): ViewPropertyAnimator {
        val rippleImageHeight = this.height
        val playerViewHeight = playerView.height.toFloat()
        val playerViewWidth = playerView.width.toFloat()
        val scaleDifference = playerViewHeight / rippleImageHeight
        val playerViewAspectRatio = playerViewWidth / playerViewHeight
        val scaleValue = scaleDifference * playerViewAspectRatio
        return animate()
            .alpha(1f)
            .scaleX(scaleValue)
            .scaleY(scaleValue)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
    }

    private fun resetRippleImage(image: ImageView) {
        image
            .animateSeekingRippleEnd()
            .withEndAction {
                image.scaleX = 1f
                image.scaleY = 1f
            }
            .start()
    }

    private fun ImageView.animateSeekingRippleEnd() =
        animate().alpha(0f).setDuration(150).setInterpolator(AccelerateInterpolator())

    private val seekGestureDetector =
        GestureDetector(
            playerView.context,
            object : GestureDetector.SimpleOnGestureListener() {
                @SuppressLint("SetTextI18n")
                override fun onScroll(
                    firstEvent: MotionEvent?,
                    currentEvent: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (firstEvent == null) return false
                    // Excludes area where app gestures conflicting with system gestures
                    if (inExclusionArea(firstEvent)) return false
                    // Disables seek gestures if view is locked
                    if (isControlsLocked) return false
                    // W4-T5（VIDEO-26）：方向锁——未锁定到横向之前不反馈；锁定后本次手势不换向
                    if (gestureAxis != GestureAxis.HORIZONTAL) return false
                    // W4-T5（VIDEO-24）：>1.05x 时单指拖动是平移，seek 让位
                    if (isZoomPanActive()) return false

                    if (
                        !swipeGestureBrightnessOpen &&
                            !swipeGestureVolumeOpen &&
                            (SystemClock.elapsedRealtime() - lastScaleEvent) > 200
                    ) {
                        val currentPos = playerView.player?.currentPosition ?: 0
                        val vidDuration = (playerView.player?.duration ?: 0).coerceAtLeast(0)

                        // 滑动距离按屏幕宽度归一化，并做渐进加速：
                        // 小幅滑动用于精细调整，大幅滑动快速跨越较长时间
                        val swipeRatio =
                            (currentEvent.x - firstEvent.x) /
                                currentViewWidth().coerceAtLeast(1).toFloat()
                        val acceleratedRatio =
                            swipeRatio.sign *
                                abs(swipeRatio).pow(Constants.SEEK_ACCELERATION_EXPONENT)

                        // 滑满整屏对应的时长随视频长度变化，并限制在合理区间
                        val fullSwipeSpanMs =
                            (vidDuration * Constants.SEEK_FULL_SWIPE_DURATION_RATIO).coerceIn(
                                Constants.SEEK_FULL_SWIPE_MIN_MS,
                                Constants.SEEK_FULL_SWIPE_MAX_MS,
                            ) *
                                Constants.GestureSensitivity.seekMultiplier(
                                    appPreferences.getValue(
                                        appPreferences.playerGesturesSeekSensitivity
                                    )
                                )

                        val difference = (acceleratedRatio * fullSwipeSpanMs).toLong()
                        // W67c：时长未知（加载中）时不再夹到 0——落点改算「相对增量」，
                        // 由 requestGestureSeek 决定立即落点（就绪）或排队（未就绪）
                        val newPos = gestureSeekTarget(currentPos, difference, vidDuration)

                        activity.binding.progressScrubberLayout.visibility = View.VISIBLE
                        activity.binding.progressScrubberText.text = longToTimestamp(difference)
                        activity.binding.progressScrubberTarget.text =
                            "[${longToTimestamp(newPos, true)}]"
                        activity.binding.progressScrubberTarget.visibility = View.VISIBLE
                        swipeGestureValueTrackerProgress = newPos
                        swipeGestureSeekDelta = newPos - currentPos

                        if (appPreferences.getValue(appPreferences.playerGesturesSeekTrickplay)) {
                            if (trickplayFrameAt != null) {
                                activity.binding.progressScrubberTrickplay.visibility = View.VISIBLE
                                updateTrickplayImage(newPos)
                            } else {
                                activity.binding.progressScrubberTrickplay.visibility = View.GONE
                            }
                        }

                        swipeGestureProgressOpen = true
                        playerView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        return true
                    }
                    return false
                }
            },
        )

    private val vbGestureDetector =
        GestureDetector(
            playerView.context,
            object : GestureDetector.SimpleOnGestureListener() {
                @SuppressLint("SetTextI18n")
                override fun onScroll(
                    firstEvent: MotionEvent?,
                    currentEvent: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (firstEvent == null) return false
                    // Excludes area where app gestures conflicting with system gestures
                    if (inExclusionArea(firstEvent)) return false
                    // Disables volume gestures when player is locked
                    if (isControlsLocked) return false

                    // W4-T5（VIDEO-26）：方向锁——未锁定到纵向之前不反馈；锁定后本次手势不换向
                    if (gestureAxis != GestureAxis.VERTICAL) return false
                    // W4-T5（VIDEO-24）：>1.05x 时单指拖动是平移，亮度/音量让位
                    if (isZoomPanActive()) return false

                    if (swipeGestureValueTrackerProgress > -1 || swipeGestureProgressOpen) {
                        return false
                    }

                    // Distance to swipe to go from min to max
                    val distanceFull =
                        currentViewHeight() *
                            Constants.GestureSensitivity.verticalScreenRatio(
                                appPreferences.getValue(
                                    appPreferences.playerGesturesVerticalSensitivity
                                )
                            )
                    val ratioChange = distanceY / distanceFull

                    if (
                        verticalGestureSide(firstEvent.x, currentViewWidth()) ==
                            VerticalGestureSide.VOLUME
                    ) {
                        // Swiping on the right, change volume

                        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        if (swipeGestureValueTrackerVolume == -1f)
                            swipeGestureValueTrackerVolume = currentVolume.toFloat()

                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val change = ratioChange * maxVolume
                        swipeGestureValueTrackerVolume =
                            (swipeGestureValueTrackerVolume + change).coerceIn(
                                0f,
                                maxVolume.toFloat(),
                            )

                        audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            swipeGestureValueTrackerVolume.toInt(),
                            0,
                        )

                        activity.binding.gestureVolumeLayout.visibility = View.VISIBLE
                        activity.binding.gestureVolumeProgressBar.max = maxVolume.times(100)
                        activity.binding.gestureVolumeProgressBar.progress =
                            swipeGestureValueTrackerVolume.times(100).toInt()
                        val process =
                            (swipeGestureValueTrackerVolume / maxVolume.toFloat())
                                .times(100)
                                .toInt()
                        activity.binding.gestureVolumeText.text = "$process%"
                        activity.binding.gestureVolumeImage.setImageLevel(process)

                        swipeGestureVolumeOpen = true
                    } else {
                        // Swiping on the left, change brightness
                        val window = activity.window
                        val brightnessRange = BRIGHTNESS_OVERRIDE_OFF..BRIGHTNESS_OVERRIDE_FULL

                        // Initialize on first swipe
                        if (swipeGestureValueTrackerBrightness == -1f) {
                            val brightness = window.attributes.screenBrightness
                            Timber.d(
                                "Brightness ${Settings.System.getFloat(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)}"
                            )
                            swipeGestureValueTrackerBrightness =
                                when (brightness) {
                                    in brightnessRange -> brightness
                                    else ->
                                        Settings.System.getFloat(
                                            activity.contentResolver,
                                            Settings.System.SCREEN_BRIGHTNESS,
                                        ) / 255
                                }
                        }
                        swipeGestureValueTrackerBrightness =
                            (swipeGestureValueTrackerBrightness + ratioChange).coerceIn(
                                brightnessRange
                            )
                        val lp = window.attributes
                        lp.screenBrightness = swipeGestureValueTrackerBrightness
                        window.attributes = lp

                        activity.binding.gestureBrightnessLayout.visibility = View.VISIBLE
                        activity.binding.gestureBrightnessProgressBar.max =
                            BRIGHTNESS_OVERRIDE_FULL.times(100).toInt()
                        activity.binding.gestureBrightnessProgressBar.progress =
                            lp.screenBrightness.times(100).toInt()
                        val process =
                            (lp.screenBrightness / BRIGHTNESS_OVERRIDE_FULL).times(100).toInt()
                        activity.binding.gestureBrightnessText.text = "$process%"
                        activity.binding.gestureBrightnessImage.setImageLevel(process)

                        swipeGestureBrightnessOpen = true
                    }
                    return true
                }
            },
        )

    private val hideGestureVolumeIndicatorOverlayAction = Runnable {
        activity.binding.gestureVolumeLayout.visibility = View.GONE
    }

    private val hideGestureBrightnessIndicatorOverlayAction = Runnable {
        activity.binding.gestureBrightnessLayout.visibility = View.GONE
        if (appPreferences.getValue(appPreferences.playerGesturesBrightnessRemember)) {
            appPreferences.setValue(
                appPreferences.playerBrightness,
                activity.window.attributes.screenBrightness,
            )
        }
    }

    private val hideGestureProgressOverlayAction = Runnable {
        activity.binding.progressScrubberLayout.visibility = View.GONE
    }

    /**
     * 双指捏合：连续缩放 1.0–3.0x，锚点跟随双指中心（W4-T5 / VIDEO-24，升级取代旧的「铺满/还原」档位切换）。
     *
     * 画面变换只作用在 `player_zoom_container`（PlayerView 外层的视图渲染节点，等价 `graphicsLayer` 的 scale +
     * translation）， 不动渲染管线，也不与 Exo 路径的旋转/镜像/裁剪 `playerView.scaleX` 互相覆盖。
     */
    private val zoomGestureDetector =
        ScaleGestureDetector(
                playerView.context,
                object : ScaleGestureDetector.OnScaleGestureListener {
                    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                        // Disables zoom gesture if view is locked
                        return !isControlsLocked
                    }

                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        // Disables zoom gesture if view is locked
                        if (isControlsLocked) return false
                        lastScaleEvent = SystemClock.elapsedRealtime()
                        applyZoomScale(detector.scaleFactor, detector.focusX, detector.focusY)
                        return true
                    }

                    override fun onScaleEnd(detector: ScaleGestureDetector) {
                        // 捏回 ≤1.05x 后平移必须归零，否则「看不见的位移」会让下一次单指手势错位
                        if (
                            !isZoomPanActive() && (zoomTranslationX != 0f || zoomTranslationY != 0f)
                        ) {
                            zoomTranslationX = 0f
                            zoomTranslationY = 0f
                            applyZoomTransform()
                        }
                        Timber.d(
                            "双指缩放：scale=%.3f tx=%.1f ty=%.1f（平移接管=%s）",
                            zoomScale,
                            zoomTranslationX,
                            zoomTranslationY,
                            isZoomPanActive(),
                        )
                    }
                },
            )
            .apply { isQuickScaleEnabled = false }

    /** 当前缩放是否进入「单指拖动 = 平移」区间（>1.05x）。 */
    private fun isZoomPanActive(): Boolean = zoomPanEnabled(zoomScale)

    /** 按本次捏合倍率缩放，并以双指中心为锚点跟随（焦点下的像素不漂移）。 */
    private fun applyZoomScale(scaleFactor: Float, focusX: Float, focusY: Float) {
        val container = activity.binding.playerZoomContainer
        val previous = zoomScale
        val next = clampZoomScale(previous * scaleFactor)
        if (next == previous) return
        if (container.width > 0 && container.height > 0) {
            // 触摸事件是画面局部坐标，锚点公式用的是「父容器坐标」：先按当前变换换算到画面中心偏移
            val focalX = zoomTranslationX + previous * (focusX - container.width / 2f)
            val focalY = zoomTranslationY + previous * (focusY - container.height / 2f)
            zoomTranslationX =
                clampZoomTranslation(
                    zoomTranslationAfterScale(
                        translationPx = zoomTranslationX,
                        focalOffsetPx = focalX,
                        previousScale = previous,
                        newScale = next,
                    ),
                    next,
                    container.width,
                )
            zoomTranslationY =
                clampZoomTranslation(
                    zoomTranslationAfterScale(
                        translationPx = zoomTranslationY,
                        focalOffsetPx = focalY,
                        previousScale = previous,
                        newScale = next,
                    ),
                    next,
                    container.height,
                )
        }
        zoomScale = next
        applyZoomTransform()
    }

    /** 单指拖动 = 平移（仅在 >1.05x 时由触摸层调用）。 */
    private fun panBy(dx: Float, dy: Float) {
        val container = activity.binding.playerZoomContainer
        if (container.width <= 0 || container.height <= 0) return
        zoomTranslationX = clampZoomTranslation(zoomTranslationX + dx, zoomScale, container.width)
        zoomTranslationY = clampZoomTranslation(zoomTranslationY + dy, zoomScale, container.height)
        applyZoomTransform()
    }

    private fun applyZoomTransform() {
        activity.binding.playerZoomContainer.apply {
            scaleX = zoomScale
            scaleY = zoomScale
            translationX = zoomTranslationX
            translationY = zoomTranslationY
        }
    }

    /** 双指双击 / 进入画中画：还原 1.0x（平移同时归零）。 */
    fun resetZoom() {
        if (zoomScale == ZOOM_MIN_SCALE && zoomTranslationX == 0f && zoomTranslationY == 0f) return
        zoomScale = ZOOM_MIN_SCALE
        zoomTranslationX = 0f
        zoomTranslationY = 0f
        applyZoomTransform()
        Timber.d("双指缩放：还原 1.0x")
    }

    /**
     * 比例档位「铺满 / 还原」（§5.2）：设置里的「进入时铺满画面」与画中画进出用。
     *
     * 两个内核共用同一条「比例档位」通路（ExoPlayer 写 `PlayerView.resizeMode`，mpv 写 `keepaspect` / `panscan`），避免只在
     * mpv 里直接改 panscan、还原时留下把画面放大铺满的残留。 放大 = 裁剪填满；还原 = 回到偏好里的比例档位（默认「适应屏幕」）。
     *
     * W4-T5 起双指捏合改为连续缩放（[applyZoomScale]），不再走这里；本方法仅保留档位键语义。
     */
    fun updateZoomMode(enabled: Boolean) {
        val baseMode = appPreferences.getValue(appPreferences.playerResizeMode)
        val mode = if (enabled) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else baseMode
        when (val player = playerView.player) {
            is MPVPlayer -> player.applyResizeMode(mode)
            else -> playerView.resizeMode = mode
        }
        isZoomEnabled = enabled
        Timber.d("player zoom gesture: enabled=%s resizeMode=%d", enabled, mode)
    }

    private fun releaseAction(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            activity.binding.gestureVolumeLayout.apply {
                if (isVisible) {
                    removeCallbacks(hideGestureVolumeIndicatorOverlayAction)
                    postDelayed(hideGestureVolumeIndicatorOverlayAction, 1000)
                    swipeGestureVolumeOpen = false
                }
            }
            activity.binding.gestureBrightnessLayout.apply {
                if (isVisible) {
                    removeCallbacks(hideGestureBrightnessIndicatorOverlayAction)
                    postDelayed(hideGestureBrightnessIndicatorOverlayAction, 1000)
                    swipeGestureBrightnessOpen = false
                }
            }
            activity.binding.progressScrubberLayout.apply {
                if (isVisible) {
                    if (swipeGestureValueTrackerProgress > -1) {
                        // W67c：滑动结束统一出口——就绪立即落点，未就绪排队补投（不再静默丢弃）
                        requestGestureSeek(swipeGestureSeekDelta)
                    }
                    removeCallbacks(hideGestureProgressOverlayAction)
                    postDelayed(hideGestureProgressOverlayAction, 1000)
                    swipeGestureProgressOpen = false

                    swipeGestureValueTrackerProgress = -1L
                    swipeGestureSeekDelta = 0L
                }
            }
            currentNumberOfPointers = 0
            // W4-T5：本次手势结束，方向锁复位（下次手势重新 10vp 仲裁）
            gestureAxis = GestureAxis.NONE
        }
        if (
            lastPlaybackSpeed > 0 &&
                (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL)
        ) {
            // W4-T5（VIDEO-23）：松手即停「越久越快」并恢复长按前的倍速
            playerView.removeCallbacks(speedRampTick)
            playerView.player?.setPlaybackSpeed(lastPlaybackSpeed)
            lastPlaybackSpeed = 0f
            activity.binding.gestureSpeedLayout.visibility = View.GONE
        }
    }

    private fun longToTimestamp(duration: Long, noSign: Boolean = false): String {
        val sign = if (noSign) "" else if (duration < 0) "-" else "+"
        val seconds = abs(duration).div(1000)

        return String.format(
            "%s%02d:%02d:%02d",
            sign,
            seconds / 3600,
            (seconds / 60) % 60,
            seconds % 60,
        )
    }

    /** Check if [firstEvent] is in the gesture exclusion area */
    private fun inExclusionArea(firstEvent: MotionEvent): Boolean {
        val viewWidth = currentViewWidth()
        val viewHeight = currentViewHeight()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets =
                playerView.rootWindowInsets?.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemGestures() or
                        WindowInsets.Type.systemBars() or
                        WindowInsets.Type.displayCutout()
                )
            return gestureStartExcluded(
                x = firstEvent.x,
                y = firstEvent.y,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                insetLeft = insets?.left ?: 0,
                insetTop = insets?.top ?: 0,
                insetRight = insets?.right ?: 0,
                insetBottom = insets?.bottom ?: 0,
            )
        }
        val edge = playerView.resources.dip(Constants.GESTURE_EXCLUSION_AREA_HORIZONTAL)
        val vertical = playerView.resources.dip(Constants.GESTURE_EXCLUSION_AREA_VERTICAL)
        return gestureStartExcluded(
            x = firstEvent.x,
            y = firstEvent.y,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            insetLeft = edge,
            insetTop = vertical,
            insetRight = edge,
            insetBottom = vertical,
        )
    }

    private fun currentViewWidth(): Int =
        playerView.width.takeIf { it > 0 } ?: playerView.measuredWidth

    private fun currentViewHeight(): Int =
        playerView.height.takeIf { it > 0 } ?: playerView.measuredHeight

    fun updateTrickplayImage(position: Long) {
        try {
            val frameAt = trickplayFrameAt ?: return
            val bitmap =
                frameAt(position)
                    ?: run {
                        // 按需加载还没到位 / 服务器没有图：隐藏预览，不让异常打断手势
                        activity.binding.progressScrubberTrickplay.visibility = View.GONE
                        return
                    }

            if (currentTrickplayBitmap != bitmap) {
                activity.binding.progressScrubberTrickplay.load(bitmap) {
                    coroutineContext(Dispatchers.Main.immediate)
                    crossfade(false)
                    transformations(trickplayRoundedCorners)
                }
                currentTrickplayBitmap = bitmap
            }
        } catch (e: Exception) {
            activity.binding.progressScrubberTrickplay.visibility = View.GONE
            Timber.d(e)
        }
    }

    init {
        if (
            appPreferences.getValue(appPreferences.playerGesturesVB) &&
                appPreferences.getValue(appPreferences.playerGesturesBrightnessRemember)
        ) {
            activity.window.attributes =
                activity.window.attributes.apply {
                    screenBrightness = appPreferences.getValue(appPreferences.playerBrightness)
                }
        }

        updateZoomMode(appPreferences.getValue(appPreferences.playerGesturesStartMaximized))

        @Suppress("ClickableViewAccessibility")
        playerView.setOnTouchListener { _, event ->
            // 手势总开关（§1.9 设置面板）：现读偏好，关掉后只有「单击显隐控制层」还保留
            val gesturesEnabled = appPreferences.getValue(appPreferences.playerGestures)
            if (!isSuspended) {
                // 指针数变化（如双指 → 单指）：重置手势基准，避免用旧的按下点算位移（方向锁 / 平移不跳变）
                if (event.pointerCount != gesturePointerCount) {
                    gesturePointerCount = event.pointerCount
                    gestureAxis = GestureAxis.NONE
                    gestureDownX = event.x
                    gestureDownY = event.y
                    panLastX = event.x
                    panLastY = event.y
                }
                currentNumberOfPointers = event.pointerCount
                if (!gesturesEnabled) {
                    if (event.pointerCount == 1) singleTapDetector.onTouchEvent(event)
                } else {
                    when (event.pointerCount) {
                        1 -> {
                            trackSingleFingerGesture(event)
                            tapGestureDetector.onTouchEvent(event)
                            if (appPreferences.getValue(appPreferences.playerGesturesVB))
                                vbGestureDetector.onTouchEvent(event)
                            if (appPreferences.getValue(appPreferences.playerGesturesSeek))
                                seekGestureDetector.onTouchEvent(event)
                        }
                        2 -> {
                            trackTwoFingerTap(event)
                            if (appPreferences.getValue(appPreferences.playerGesturesZoom))
                                zoomGestureDetector.onTouchEvent(event)
                        }
                    }
                }
            }
            releaseAction(event)
            true
        }
    }

    /**
     * 单指手势的方向仲裁（W4-T5 / VIDEO-26）与缩放后平移（VIDEO-24）。
     *
     * - 未达 10vp：`GestureAxis.NONE`，seek / 亮度 / 音量检测器都不动（不锁定也不反馈）；
     * - 达锁：按主轴定一次，本次手势不再换向（斜滑也只走主轴）；
     * - 缩放 >1.05x：单指拖动改为平移，seek / 亮度 / 音量禁用，≤1.05x 恢复。
     */
    private fun trackSingleFingerGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureAxis = GestureAxis.NONE
                gestureDownX = event.x
                gestureDownY = event.y
                panLastX = event.x
                panLastY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                if (isZoomPanActive()) {
                    // 平移量作用在父容器坐标：手指位移是画面局部坐标，需按当前缩放换算回屏幕距离
                    panBy(
                        (event.x - panLastX) * zoomScale,
                        (event.y - panLastY) * zoomScale,
                    )
                    panLastX = event.x
                    panLastY = event.y
                    gestureAxis = GestureAxis.NONE
                } else {
                    val resolved =
                        resolveGestureAxis(
                            current = gestureAxis,
                            dxPx = event.x - gestureDownX,
                            dyPx = event.y - gestureDownY,
                            thresholdPx = directionLockThresholdPx,
                        )
                    // 真机走查用：方向锁只在「锁定瞬间」记一行，斜滑不跳变可直接对照
                    if (resolved != gestureAxis) {
                        gestureAxis = resolved
                        Timber.d(
                            "方向锁：锁定 %s（dx=%.1f dy=%.1f 阈值=%.1fpx）",
                            resolved,
                            event.x - gestureDownX,
                            event.y - gestureDownY,
                            directionLockThresholdPx,
                        )
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> gestureAxis = GestureAxis.NONE
        }
    }

    /**
     * 双指双击 = 还原 1.0x（W4-T5 / VIDEO-24）。
     *
     * `GestureDetector` 的双击判定只认单指序列，这里按「双指按下 → 300ms 内抬起且未移动、未缩放」= 一次双指点， 两次相隔 ≤300ms 即还原。
     */
    private fun trackTwoFingerTap(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                twoFingerDownAtMs = SystemClock.elapsedRealtime()
                twoFingerDownX = event.x
                twoFingerDownY = event.y
                twoFingerDownScale = zoomScale
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val now = SystemClock.elapsedRealtime()
                val moved = hypot(event.x - twoFingerDownX, event.y - twoFingerDownY)
                val isTap =
                    now - twoFingerDownAtMs <= TWO_FINGER_TAP_MAX_MS &&
                        moved <= twoFingerTapSlopPx &&
                        abs(zoomScale - twoFingerDownScale) <= TWO_FINGER_TAP_SCALE_TOLERANCE
                if (!isTap) return
                if (now - lastTwoFingerTapAtMs <= TWO_FINGER_DOUBLE_TAP_WINDOW_MS) {
                    lastTwoFingerTapAtMs = 0L
                    resetZoom()
                } else {
                    lastTwoFingerTapAtMs = now
                }
            }
        }
    }
}
