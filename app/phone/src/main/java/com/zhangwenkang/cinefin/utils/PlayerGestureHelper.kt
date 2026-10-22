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
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.PlaybackSpeedOptions
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import kotlinx.coroutines.Dispatchers
import timber.log.Timber

class PlayerGestureHelper(
    private val appPreferences: AppPreferences,
    private val activity: PlayerActivity,
    private val playerView: PlayerView,
    private val audioManager: AudioManager,
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
        playerView.player?.let {
            if (it.isPlaying) {
                lastPlaybackSpeed = it.playbackParameters.speed
                it.setPlaybackSpeed(playbackSpeedIncrease)
                activity.binding.gestureSpeedText.text =
                    PlaybackSpeedOptions.label(playbackSpeedIncrease)
                activity.binding.gestureSpeedLayout.visibility = View.VISIBLE
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

    /** 倍速显示：整数倍显示为 2×，半档显示为 1.5× */
    private fun fastForward() {
        requestGestureSeek(appPreferences.getValue(appPreferences.playerSeekForwardInc))
        animateRipple(activity.binding.imageFfwdAnimationRipple)
    }

    private fun rewind() {
        requestGestureSeek(-appPreferences.getValue(appPreferences.playerSeekBackInc))
        animateRipple(activity.binding.imageRewindAnimationRipple)
    }

    private fun togglePlayback() {
        playerView.player?.let { player -> player.playWhenReady = !player.playWhenReady }
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

                    // Check whether swipe was oriented vertically
                    if (isHorizontalScroll(distanceX, distanceY)) {
                        return if (
                            (abs(currentEvent.x - firstEvent.x) > 50 || swipeGestureProgressOpen) &&
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

                            if (
                                appPreferences.getValue(appPreferences.playerGesturesSeekTrickplay)
                            ) {
                                if (trickplayFrameAt != null) {
                                    activity.binding.progressScrubberTrickplay.visibility =
                                        View.VISIBLE
                                    updateTrickplayImage(newPos)
                                } else {
                                    activity.binding.progressScrubberTrickplay.visibility =
                                        View.GONE
                                }
                            }

                            swipeGestureProgressOpen = true
                            playerView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            true
                        } else {
                            false
                        }
                    }
                    return true
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

                    if (!isVerticalScroll(distanceX, distanceY)) return false

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

    /** Handles scale/zoom gesture */
    private val zoomGestureDetector =
        ScaleGestureDetector(
                playerView.context,
                object : ScaleGestureDetector.OnScaleGestureListener {
                    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true

                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        // Disables zoom gesture if view is locked
                        if (isControlsLocked) return false
                        lastScaleEvent = SystemClock.elapsedRealtime()
                        val scaleFactor = detector.scaleFactor
                        if (
                            abs(scaleFactor - Constants.ZOOM_SCALE_BASE) >
                                Constants.ZOOM_SCALE_THRESHOLD
                        ) {
                            val enableZoom = scaleFactor > 1
                            updateZoomMode(enableZoom)
                        }
                        return true
                    }

                    override fun onScaleEnd(detector: ScaleGestureDetector) = Unit
                },
            )
            .apply { isQuickScaleEnabled = false }

    /**
     * 双指缩放：铺满 / 还原（§5.2）。
     *
     * 两个内核共用同一条「比例档位」通路（ExoPlayer 写 `PlayerView.resizeMode`，mpv 写 `keepaspect` / `panscan`），避免只在
     * mpv 里直接改 panscan、还原时留下把画面放大铺满的残留。 放大 = 裁剪填满；还原 = 回到偏好里的比例档位（默认「适应屏幕」）。
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
        }
        if (
            lastPlaybackSpeed > 0 &&
                (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL)
        ) {
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
                currentNumberOfPointers = event.pointerCount
                if (!gesturesEnabled) {
                    if (event.pointerCount == 1) singleTapDetector.onTouchEvent(event)
                } else {
                    when (event.pointerCount) {
                        1 -> {
                            tapGestureDetector.onTouchEvent(event)
                            if (appPreferences.getValue(appPreferences.playerGesturesVB))
                                vbGestureDetector.onTouchEvent(event)
                            if (appPreferences.getValue(appPreferences.playerGesturesSeek))
                                seekGestureDetector.onTouchEvent(event)
                        }
                        2 -> {
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
}
