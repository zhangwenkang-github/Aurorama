package com.zhangwenkang.cinefin.utils

import android.annotation.SuppressLint
import android.content.res.Resources
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
import com.zhangwenkang.cinefin.player.core.domain.models.Trickplay
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
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

    private var swipeGestureVolumeOpen = false
    private var swipeGestureBrightnessOpen = false
    private var swipeGestureProgressOpen = false

    private var lastScaleEvent: Long = 0

    private var playbackSpeedIncrease: Float =
        appPreferences.getValue(appPreferences.playerGesturesSpeedMultiplier).toFloatOrNull() ?: 2f
    private var lastPlaybackSpeed: Float = 0f

    private val screenWidth = Resources.getSystem().displayMetrics.widthPixels
    private val screenHeight = Resources.getSystem().displayMetrics.heightPixels

    var currentTrickplay: Trickplay? = null
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

                    val viewWidth = playerView.measuredWidth
                    val areaWidth = viewWidth / 5 // Divide the view into 5 parts: 2:1:2

                    // Define the areas and their boundaries
                    val leftmostAreaStart = 0
                    val middleAreaStart = areaWidth * 2
                    val rightmostAreaStart = middleAreaStart + areaWidth

                    when (e.x.toInt()) {
                        in leftmostAreaStart until middleAreaStart -> {
                            // Tapped on the leftmost area (seek backward)
                            rewind()
                        }
                        in middleAreaStart until rightmostAreaStart -> {
                            // Tapped on the middle area (toggle pause/unpause)
                            togglePlayback()
                        }
                        in rightmostAreaStart until viewWidth -> {
                            // Tapped on the rightmost area (seek forward)
                            fastForward()
                        }
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
                activity.binding.gestureSpeedText.text = formatSpeedLabel(playbackSpeedIncrease)
                activity.binding.gestureSpeedLayout.visibility = View.VISIBLE
            }
        }
    }

    /** @return 是否已跳转章节；返回 false 表示当前位置没有可跳的章节，可回退到长按倍速 */
    private fun handleChapterSkip(e: MotionEvent): Boolean {
        if (isControlsLocked) {
            return false
        }

        val viewWidth = playerView.measuredWidth
        val areaWidth = viewWidth / 5 // Divide the view into 5 parts: 2:1:2

        // Define the areas and their boundaries
        val leftmostAreaStart = 0
        val middleAreaStart = areaWidth * 2
        val rightmostAreaStart = middleAreaStart + areaWidth

        return when (e.x.toInt()) {
            in leftmostAreaStart until middleAreaStart ->
                activity.viewModel.seekToPreviousChapter()?.let { chapter ->
                    displayChapter(chapter)
                    true
                } ?: false

            in rightmostAreaStart until viewWidth -> {
                val player = playerView.player
                if (activity.viewModel.isLastChapter()) {
                    if (player != null && player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
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

            else -> false
        }
    }

    private fun displayChapter(chapter: PlayerChapter) {
        activity.binding.progressScrubberTrickplay.visibility = View.GONE
        activity.binding.progressScrubberLayout.visibility = View.VISIBLE
        activity.binding.progressScrubberText.text = chapter.name ?: ""
        activity.binding.progressScrubberTarget.visibility = View.GONE
    }

    /** 倍速显示：整数倍显示为 2×，半档显示为 1.5× */
    private fun formatSpeedLabel(speed: Float): String =
        if (speed % 1f == 0f) "${speed.toInt()}×" else "$speed×"

    private fun fastForward() {
        val currentPosition = playerView.player?.currentPosition ?: 0
        val fastForwardPosition =
            currentPosition + appPreferences.getValue(appPreferences.playerSeekForwardInc)
        seekTo(fastForwardPosition)
        animateRipple(activity.binding.imageFfwdAnimationRipple)
    }

    private fun rewind() {
        val currentPosition = playerView.player?.currentPosition ?: 0
        val rewindPosition =
            currentPosition - appPreferences.getValue(appPreferences.playerSeekBackInc)
        seekTo(rewindPosition.coerceAtLeast(0))
        animateRipple(activity.binding.imageRewindAnimationRipple)
    }

    private fun togglePlayback() {
        playerView.player?.playWhenReady = !playerView.player?.playWhenReady!!
        animateRipple(activity.binding.imagePlaybackAnimationRipple)
    }

    private fun seekTo(position: Long) {
        playerView.player?.seekTo(position)
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
                    if (abs(distanceY / distanceX) < 2) {
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
                                    playerView.measuredWidth.coerceAtLeast(1).toFloat()
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
                            val newPos = (currentPos + difference).coerceIn(0, vidDuration)

                            activity.binding.progressScrubberLayout.visibility = View.VISIBLE
                            activity.binding.progressScrubberText.text = longToTimestamp(difference)
                            activity.binding.progressScrubberTarget.text =
                                "[${longToTimestamp(newPos, true)}]"
                            activity.binding.progressScrubberTarget.visibility = View.VISIBLE
                            swipeGestureValueTrackerProgress = newPos

                            if (
                                appPreferences.getValue(appPreferences.playerGesturesSeekTrickplay)
                            ) {
                                if (currentTrickplay != null) {
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

                    if (abs(distanceY / distanceX) < 2) return false

                    if (swipeGestureValueTrackerProgress > -1 || swipeGestureProgressOpen) {
                        return false
                    }

                    val viewCenterX = playerView.measuredWidth / 2

                    // Distance to swipe to go from min to max
                    val distanceFull =
                        playerView.measuredHeight *
                            Constants.GestureSensitivity.verticalScreenRatio(
                                appPreferences.getValue(
                                    appPreferences.playerGesturesVerticalSensitivity
                                )
                            )
                    val ratioChange = distanceY / distanceFull

                    if (firstEvent.x.toInt() > viewCenterX) {
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
                        playerView.player?.seekTo(swipeGestureValueTrackerProgress)
                    }
                    removeCallbacks(hideGestureProgressOverlayAction)
                    postDelayed(hideGestureProgressOverlayAction, 1000)
                    swipeGestureProgressOpen = false

                    swipeGestureValueTrackerProgress = -1L
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets =
                playerView.rootWindowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemGestures()
                )

            if (
                (firstEvent.x < insets.left) ||
                    (firstEvent.x > (screenWidth - insets.right)) ||
                    (firstEvent.y < insets.top) ||
                    (firstEvent.y > (screenHeight - insets.bottom))
            ) {
                return true
            }
        } else if (
            firstEvent.y < playerView.resources.dip(Constants.GESTURE_EXCLUSION_AREA_VERTICAL) ||
                firstEvent.y >
                    screenHeight -
                        playerView.resources.dip(Constants.GESTURE_EXCLUSION_AREA_VERTICAL) ||
                firstEvent.x <
                    playerView.resources.dip(Constants.GESTURE_EXCLUSION_AREA_HORIZONTAL) ||
                firstEvent.x >
                    screenWidth -
                        playerView.resources.dip(Constants.GESTURE_EXCLUSION_AREA_HORIZONTAL)
        ) {
            return true
        }
        return false
    }

    fun updateTrickplayImage(position: Long) {
        try {
            val trickplay = currentTrickplay ?: return
            val bitmap = trickplay.images[position.div(trickplay.interval).toInt()]

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
