package com.zhangwenkang.cinefin.player.local.presentation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.zhangwenkang.cinefin.models.FindroidSegment
import com.zhangwenkang.cinefin.models.FindroidSegmentType
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_EPISODE_NUMBER
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_SEASON_NUMBER
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerChapter
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.Trickplay
import com.zhangwenkang.cinefin.player.local.R
import com.zhangwenkang.cinefin.player.local.domain.PlaylistManager
import com.zhangwenkang.cinefin.player.local.domain.TrackSelectionEngine
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlin.math.ceil
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

@HiltViewModel
class PlayerViewModel
@Inject
constructor(
    private val application: Application,
    private val playlistManager: PlaylistManager,
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    /** 播放器实例由进程级单例持有：播放页关闭后通知栏 / 后台播放仍要能控制它（阶段 4.1） */
    private val playerHolder: PlayerHolder,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel(), Player.Listener {
    companion object {
        /** 播放核心取值，与 `AppPreferences.playerBackend` 里存的一致 */
        const val PLAYER_BACKEND_EXOPLAYER = "exoplayer"
        const val PLAYER_BACKEND_MPV = "mpv"
    }

    val player: Player
        get() = playerHolder.player

    private val _uiState =
        MutableStateFlow(
            UiState(
                currentItemTitle = "",
                currentSegment = null,
                currentSkipButtonStringRes = R.string.player_controls_skip_intro,
                currentTrickplay = null,
                currentChapters = emptyList(),
                fileLoaded = false,
            )
        )
    val uiState = _uiState.asStateFlow()

    private val eventsChannel = Channel<PlayerEvents>()
    val eventsChannelFlow = eventsChannel.receiveAsFlow()

    data class UiState(
        val currentItemTitle: String,
        /** 当前播放条目的 id，供播放页做海报取色等与条目相关的效果 */
        val currentItemId: UUID? = null,
        val currentSegment: FindroidSegment?,
        val currentSkipButtonStringRes: Int,
        val currentTrickplay: Trickplay?,
        val currentChapters: List<PlayerChapter>,
        val fileLoaded: Boolean,
        /** 播放失败时的错误信息；非空时控制层显示错误卡片 */
        val playerError: PlayerErrorInfo? = null,
    )

    /** 播放错误：给控制层的错误卡片用（阶段 1.3） */
    data class PlayerErrorInfo(
        /** 后端给出的说明，可能较长，UI 侧截断显示 */
        val message: String,
        /** 错误码名，例如 ERROR_CODE_DECODING_FAILED */
        val codeName: String,
        /** 出错时使用的内核：exoplayer / mpv */
        val backend: String,
    )

    private var items: MutableList<PlayerItem> = mutableListOf()

    /** 字幕/音轨的智能选择引擎：按语言优先级自动选轨，并记住用户的手动选择 */
    private val trackSelectionEngine = TrackSelectionEngine(appPreferences)

    /** 用户在当前媒体里手动选过轨后，不再自动干预 */
    private var manualTrackSelectionMediaId: String? = null

    var playWhenReady = true
    private var currentMediaItemIndex = savedStateHandle["mediaItemIndex"] ?: 0
    private var playbackPosition: Long = savedStateHandle["position"] ?: 0
    private var currentMediaItemSegments: List<FindroidSegment> = emptyList()

    // Segments preferences
    var segmentsSkipButton: Boolean = false
    private var segmentsSkipButtonTypes: Set<String> = emptySet()
    var segmentsSkipButtonDuration: Long = 0L
    var segmentsAutoSkip: Boolean = false
    private var segmentsAutoSkipTypes: Set<String> = emptySet()
    private var segmentsAutoSkipMode: String = "always"

    var playbackSpeed: Float = 1f

    var isInPictureInPictureMode: Boolean = false

    /** 当前播放核心：`exoplayer` 或 `mpv`。换内核要重建 player，由 Activity 重启播放页生效 */
    val playerBackend: String
        get() = playerHolder.backend

    init {
        segmentsSkipButton = appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButton)
        segmentsSkipButtonTypes =
            appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButtonType)
        segmentsSkipButtonDuration =
            appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButtonDuration)
        segmentsAutoSkip = appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkip)
        segmentsAutoSkipTypes =
            appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkipType)
        segmentsAutoSkipMode =
            appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkipMode)
    }

    /**
     * 初始化并开始播放。
     *
     * @param startPositionMs 精确续播位置（毫秒）。换解码内核重启播放页时由 Activity 带入， 避免只依赖服务端 5 秒一次的上报而丢掉几秒进度；0
     *   表示按服务端记录续播。
     */
    fun initializePlayer(
        itemId: UUID,
        itemKind: String,
        startFromBeginning: Boolean,
        startPositionMs: Long = 0L,
    ) {
        player.addListener(this)

        if (startPositionMs > 0L) {
            playbackPosition = startPositionMs
        }

        viewModelScope.launch {
            val startItem =
                try {
                    playlistManager.getInitialItem(
                        itemId = itemId,
                        itemKind = BaseItemKind.fromName(itemKind),
                        mediaSourceIndex = null,
                        startFromBeginning = startFromBeginning,
                    )
                } catch (e: Exception) {
                    Timber.e(e)
                    Toast.makeText(application, e.localizedMessage, Toast.LENGTH_LONG).show()
                    null
                }

            if (startItem == null) {
                Timber.e("No start item, stopping player initialization")
                return@launch
            }

            items = listOfNotNull(startItem).toMutableList()
            currentMediaItemIndex = items.indexOf(startItem)

            val mediaItems = mutableListOf<MediaItem>()
            try {
                for (item in items) {
                    mediaItems.add(item.toMediaItem())
                }
            } catch (e: Exception) {
                Timber.e(e)
            }

            val startPosition =
                if (playbackPosition == 0L) {
                    items.getOrNull(currentMediaItemIndex)?.playbackPosition ?: C.TIME_UNSET
                } else {
                    playbackPosition
                }

            player.setMediaItems(mediaItems, 0, startPosition)
            player.prepare()
            player.play()
        }
    }

    /**
     * 接管一个已经在播放的会话（从通知 / 锁屏回到播放页，阶段 4）。
     *
     * 播放器实例、播放列表与进度都还在服务里跑，这里**不能**重新 `setMediaItems`（会跳回开头）。
     * 只需要重新挂监听，再把 uiState 里缺的标题补上；选集栏直接读播放器里的媒体项，不受影响。
     */
    fun attachToExistingSession() {
        player.addListener(this)
        refreshUiStateFromPlayer()
    }

    /** 用播放器当前条目刷新 uiState（标题 / 条目 id），不改动任何播放状态 */
    private fun refreshUiStateFromPlayer() {
        val mediaItem = player.currentMediaItem ?: return
        val itemId = runCatching { UUID.fromString(mediaItem.mediaId) }.getOrNull()
        val extras = mediaItem.mediaMetadata.extras
        val season = extras?.getInt(PLAYER_EXTRA_SEASON_NUMBER, -1) ?: -1
        val episode = extras?.getInt(PLAYER_EXTRA_EPISODE_NUMBER, -1) ?: -1
        val name = mediaItem.mediaMetadata.title?.toString().orEmpty()
        val title = if (season >= 0 && episode >= 0) "S$season:E$episode - $name" else name
        _uiState.update {
            it.copy(currentItemTitle = title, currentItemId = itemId ?: it.currentItemId)
        }
    }

    private fun PlayerItem.toMediaItem(): MediaItem {
        val streamUrl = mediaSourceUri
        val mediaSubtitles = externalSubtitles.map { externalSubtitle ->
            MediaItem.SubtitleConfiguration.Builder(externalSubtitle.uri)
                .setLabel(
                    externalSubtitle.title.ifBlank { application.getString(R.string.external) }
                )
                .setMimeType(externalSubtitle.mimeType)
                .setLanguage(externalSubtitle.language)
                .build()
        }

        Timber.d("Stream url: $streamUrl")
        val mediaItem =
            MediaItem.Builder()
                .setMediaId(itemId.toString())
                .setUri(streamUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(name)
                        // 队列面板需要按季分组：把季号/集号随媒体项一起带过去
                        .setExtras(
                            Bundle().apply {
                                putInt(PLAYER_EXTRA_SEASON_NUMBER, parentIndexNumber ?: -1)
                                putInt(PLAYER_EXTRA_EPISODE_NUMBER, indexNumber ?: -1)
                            }
                        )
                        .build()
                )
                .setSubtitleConfigurations(mediaSubtitles)
                .build()

        return mediaItem
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun releasePlayer() {
        val mediaId = player.currentMediaItem?.mediaId
        val position = player.currentPosition
        val duration = player.duration
        GlobalScope.launch {
            delay(200L)
            try {
                if (mediaId != null && duration != C.TIME_UNSET) {
                    Timber.d("Sending playback stop")
                    repository.postPlaybackStop(
                        UUID.fromString(mediaId),
                        position.times(10000),
                        position.div(duration.toFloat()).times(100).toInt(),
                    )
                }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }

        _uiState.update { it.copy(currentTrickplay = null) }
        playWhenReady = false
        playbackPosition = 0L
        currentMediaItemIndex = 0
        player.removeListener(this)
        if (appPreferences.getValue(appPreferences.playerBackgroundAudio)) {
            /*
             * 后台播放开启：播放页关闭后实例继续由前台服务与通知栏控制，
             * 这里只把最后位置写回，不能释放实例。
             */
            savedStateHandle["position"] = player.currentPosition
        } else {
            playerHolder.release()
        }
    }

    fun updatePlaybackProgress() {
        Timber.d("Updating playback progress")
        viewModelScope.launch(Dispatchers.Main) {
            savedStateHandle["position"] = player.currentPosition
            if (player.currentMediaItem != null && player.currentMediaItem!!.mediaId.isNotEmpty()) {
                val itemId = UUID.fromString(player.currentMediaItem!!.mediaId)
                try {
                    repository.postPlaybackProgress(
                        itemId,
                        player.currentPosition.times(10000),
                        !player.isPlaying,
                    )
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
        }
    }

    fun updateCurrentSegment() {
        Timber.d("Updating current segment")
        viewModelScope.launch(Dispatchers.Main) {
            if (currentMediaItemSegments.isEmpty()) {
                return@launch
            }

            val milliSeconds = player.currentPosition

            // Get current segment, - 100 milliseconds to avoid showing button after segment ends
            val currentSegment = currentMediaItemSegments.find { segment ->
                milliSeconds in segment.startTicks..<(segment.endTicks - 100L)
            }

            if (currentSegment == null) {
                // Remove button if not pressed and there is no current segment
                if (_uiState.value.currentSegment != null) {
                    _uiState.update { it.copy(currentSegment = null) }
                }
                return@launch
            }

            Timber.tag("SegmentInfo").d("currentSegment: %s", currentSegment)

            if (
                segmentsAutoSkip &&
                    segmentsAutoSkipTypes.contains(currentSegment.type.toString()) &&
                    (segmentsAutoSkipMode == Constants.PlayerMediaSegmentsAutoSkip.ALWAYS ||
                        (segmentsAutoSkipMode == Constants.PlayerMediaSegmentsAutoSkip.PIP &&
                            isInPictureInPictureMode))
            ) {
                // Auto Skip segment
                skipSegment(currentSegment)
            } else if (segmentsSkipButtonTypes.contains(currentSegment.type.toString())) {
                // Skip Button segment
                _uiState.update {
                    it.copy(
                        currentSegment = currentSegment,
                        currentSkipButtonStringRes = getSkipButtonTextStringId(currentSegment),
                    )
                }
            } else {
                _uiState.update { it.copy(currentSegment = null) }
            }
        }
    }

    /**
     * 轨道信息就绪后按语言优先级自动选轨。
     *
     * 同一媒体可能多次回调（例如外挂字幕稍后才挂载），因此每次都重新计算； 但用户在当前媒体里手动选过轨时不再干预。
     */
    override fun onTracksChanged(tracks: Tracks) {
        if (player !is ExoPlayer) return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (manualTrackSelectionMediaId == mediaId) return

        runCatching {
            val parameters =
                trackSelectionEngine.parameters(player.trackSelectionParameters, tracks)
            player.trackSelectionParameters = parameters
        }
            .onFailure { Timber.w(it, "自动选择字幕/音轨失败") }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        Timber.d("Playing MediaItem: ${mediaItem?.mediaId}")
        savedStateHandle["mediaItemIndex"] = player.currentMediaItemIndex
        viewModelScope.launch {
            try {
                val item =
                    items.firstOrNull { it.itemId.toString() == player.currentMediaItem?.mediaId }
                if (item == null) {
                    /*
                     * 播放页是从通知回到前台、或后台自动切集后新开的页面：items 还没建。
                     * 用播放器里的元数据兜底刷新标题，播放本身不受影响。
                     */
                    refreshUiStateFromPlayer()
                    return@launch
                }
                item
                    .let { item ->
                        val itemTitle =
                            if (item.parentIndexNumber != null && item.indexNumber != null) {
                                if (item.indexNumberEnd == null) {
                                    "S${item.parentIndexNumber}:E${item.indexNumber} - ${item.name}"
                                } else {
                                    "S${item.parentIndexNumber}:E${item.indexNumber}-${item.indexNumberEnd} - ${item.name}"
                                }
                            } else {
                                item.name
                            }
                        _uiState.update {
                            it.copy(
                                currentItemTitle = itemTitle,
                                currentItemId = item.itemId,
                                currentSegment = null,
                                currentChapters = item.chapters,
                                fileLoaded = false,
                            )
                        }

                        repository.postPlaybackStart(item.itemId)

                        if (segmentsSkipButton || segmentsAutoSkip) {
                            getSegments(item.itemId)
                        }

                        if (appPreferences.getValue(appPreferences.playerTrickplay)) {
                            getTrickplay(item)
                        }

                        playlistManager.setCurrentMediaItemIndex(item.itemId)

                        val previousItem = playlistManager.getPreviousPlayerItem()
                        if (previousItem != null) {
                            items.add(player.currentMediaItemIndex, previousItem)
                            player.addMediaItem(
                                player.currentMediaItemIndex,
                                previousItem.toMediaItem(),
                            )
                        }

                        val nextItem = playlistManager.getNextPlayerItem()
                        if (nextItem != null) {
                            items.add(player.currentMediaItemIndex + 1, nextItem)
                            player.addMediaItem(
                                player.currentMediaItemIndex + 1,
                                nextItem.toMediaItem(),
                            )
                        }

                        Timber.tag("PlayerItems").d(items.map { it.indexNumber }.toString())
                    }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        // Report playback stopped for current item and transition to the next one
        if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM || playWhenReady) {
            return
        }
        /*
         * 注意：这里**不能**再要求 `playbackState == STATE_READY`。
         * 播放器设了 pauseAtEndOfMediaItems，一集播完会在「渲染结束的那一刻」先把 playWhenReady 置 false，
         * 那个瞬间状态可能还是 STATE_BUFFERING（比如手动拖到片尾后），一旦把这种情形挡掉，
         * 这一集就会永远卡在片尾。
         */
        Timber.d(
            "end of media item: state=${player.playbackState} repeat=${player.repeatMode} " +
                "shuffle=${player.shuffleModeEnabled} index=${player.currentMediaItemIndex}/" +
                "${player.mediaItemCount} hasNext=${player.hasNextMediaItem()}"
        )
        viewModelScope.launch {
            val mediaId = player.currentMediaItem?.mediaId
            val position = player.currentPosition
            val duration = player.duration
            try {
                repository.postPlaybackStop(
                    UUID.fromString(mediaId),
                    position.times(10000),
                    position.div(duration.toFloat()).times(100).toInt(),
                )
            } catch (e: Exception) {
                Timber.e(e)
            }
            advanceAfterItemEnd()
        }
    }

    /**
     * 一集播完后的走向，由循环模式（控制层底栏的循环面板）决定：
     * - 单集循环：回到本集开头继续播；
     * - 顺序播放：往下一集走，走到队列末尾就停在片尾（由播放器发 STATE_ENDED 收尾）；
     * - 列表循环：往下一集走，队列末尾会绕回第一集；
     * - 随机播放：往「打乱后的下一集」走，队列末尾同样绕回。
     *
     * 播放器本身设了 `pauseAtEndOfMediaItems`，一集结束会先停住，由这里显式决定去向， 所以列表循环与随机也不会出现「跳两集」。
     */
    private fun advanceAfterItemEnd() {
        val from = player.currentMediaItemIndex
        val repeatMode = player.repeatMode
        if (repeatMode == Player.REPEAT_MODE_ONE) {
            player.seekTo(from, 0L)
        } else if (player.hasNextMediaItem()) {
            // hasNextMediaItem() 已经把列表循环与随机算进去了：循环/随机在队列末尾会绕回第一集
            player.seekToNextMediaItem()
        }
        Timber.d(
            "advance after item end: from=$from repeat=$repeatMode " +
                "shuffle=${player.shuffleModeEnabled} -> index=${player.currentMediaItemIndex}"
        )
        player.play()
    }

    /** 队列走到尽头时的兜底：单集循环回到本集开头，列表循环/随机回第一集 */
    private fun restartAtQueueEnd() {
        val index =
            if (player.repeatMode == Player.REPEAT_MODE_ONE) {
                player.currentMediaItemIndex
            } else {
                0
            }
        player.seekTo(index, 0L)
        player.play()
    }

    override fun onPlaybackStateChanged(state: Int) {
        var stateString = "UNKNOWN_STATE             -"
        when (state) {
            ExoPlayer.STATE_IDLE -> {
                stateString = "ExoPlayer.STATE_IDLE      -"
            }
            ExoPlayer.STATE_BUFFERING -> {
                stateString = "ExoPlayer.STATE_BUFFERING -"
            }
            ExoPlayer.STATE_READY -> {
                stateString = "ExoPlayer.STATE_READY     -"
                // 能进 READY 就说明上一次失败已经恢复，顺手收起错误卡片
                _uiState.update { it.copy(fileLoaded = true, playerError = null) }
            }
            ExoPlayer.STATE_ENDED -> {
                stateString = "ExoPlayer.STATE_ENDED     -"
                /*
                 * 顺序播放走到最后一集就收尾（关闭播放页，与既有行为一致）；
                 * 列表循环 / 随机播放则绕回队列开头继续，单集循环回到本集开头，
                 * 这是「片尾那一帧没能触发到下一集」时的兜底。
                 */
                if (player.repeatMode == Player.REPEAT_MODE_OFF) {
                    eventsChannel.trySend(PlayerEvents.NavigateBack)
                } else {
                    restartAtQueueEnd()
                }
            }
        }
        Timber.d("Changed player state to $stateString")
    }

    /**
     * 播放失败：记下错误让控制层显示错误卡片。
     *
     * note：mpv 内核目前不会走这里（`MPVPlayer.getPlayerError()` 恒为 null、也没上报事件）， 所以错误卡片只在 ExoPlayer 内核生效；mpv
     * 的错误上报见 docs/PLAYER_PLAN.md 待办。
     */
    override fun onPlayerError(error: PlaybackException) {
        Timber.e(error, "Player error on backend=$playerBackend: ${error.errorCodeName}")
        _uiState.update {
            it.copy(
                playerError =
                    PlayerErrorInfo(
                        message =
                            error.message?.takeIf { message -> message.isNotBlank() }
                                ?: error.errorCodeName,
                        codeName = error.errorCodeName,
                        backend = playerBackend,
                    )
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("Clearing Player ViewModel")
        releasePlayer()
    }

    fun switchToTrack(trackType: @C.TrackType Int, index: Int) {
        // 用户手动选择后，本次播放不再自动改轨；同时把语言记为首选，供后续视频沿用
        manualTrackSelectionMediaId = player.currentMediaItem?.mediaId

        // Index -1 equals disable track
        if (index == -1) {
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(trackType)
                    .setTrackTypeDisabled(trackType, true)
                    .build()
        } else {
            val group =
                player.currentTracks.groups.filter { it.type == trackType && it.isSupported }[index]
            trackSelectionEngine.rememberSelectedLanguage(
                trackType,
                group.mediaTrackGroup.getFormat(0),
            )
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                    .setTrackTypeDisabled(trackType, false)
                    .build()
        }
    }

    fun selectSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        playbackSpeed = speed
    }

    /**
     * 从错误中重试：清掉错误卡片，回到失败前的位置重新 prepare。
     *
     * ExoPlayer 出错后停在 STATE_IDLE，重新 prepare 即重新拉流； mpv 的 prepare 会重新 loadfile 并回到片头，所以这里统一把位置挪回去。
     */
    fun retryPlayback() {
        val position = player.currentPosition.coerceAtLeast(0L)
        Timber.d("Retrying playback on backend=$playerBackend from position=$position")
        _uiState.update { it.copy(playerError = null) }
        player.prepare()
        if (position > 0L) {
            player.seekTo(position)
        }
        player.play()
    }

    /**
     * 一键切换解码内核：写入偏好并返回新内核名，由调用方重启播放页生效。
     *
     * 不能就地换：player 与 MediaSession 都在构造时绑定，换实例要连带重建会话。
     */
    fun switchBackend(): String {
        val next =
            if (playerBackend == PLAYER_BACKEND_MPV) PLAYER_BACKEND_EXOPLAYER
            else PLAYER_BACKEND_MPV
        appPreferences.setValue(appPreferences.playerBackend, next)
        Timber.d("Player backend switched: $playerBackend -> $next")
        return next
    }

    /** 手动收起错误卡片（不改播放状态） */
    fun dismissPlayerError() {
        _uiState.update { it.copy(playerError = null) }
    }

    private suspend fun getSegments(itemId: UUID) {
        try {
            currentMediaItemSegments = repository.getSegments(itemId)
        } catch (e: Exception) {
            currentMediaItemSegments = emptyList()
            Timber.e(e)
        }
    }

    private suspend fun getTrickplay(item: PlayerItem) {
        val trickplayInfo = item.trickplayInfo ?: return
        Timber.d("Trickplay Resolution: ${trickplayInfo.width}")

        withContext(Dispatchers.Default) {
            val maxIndex =
                ceil(
                        trickplayInfo.thumbnailCount
                            .toDouble()
                            .div(trickplayInfo.tileWidth * trickplayInfo.tileHeight)
                    )
                    .toInt()
            val bitmaps = mutableListOf<Bitmap>()

            for (i in 0..maxIndex) {
                repository.getTrickplayData(item.itemId, trickplayInfo.width, i)?.let { byteArray ->
                    val fullBitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                    for (offsetY in
                        0..<trickplayInfo.height * trickplayInfo.tileHeight step
                            trickplayInfo.height) {
                        for (offsetX in
                            0..<trickplayInfo.width * trickplayInfo.tileWidth step
                                trickplayInfo.width) {
                            val bitmap =
                                Bitmap.createBitmap(
                                    fullBitmap,
                                    offsetX,
                                    offsetY,
                                    trickplayInfo.width,
                                    trickplayInfo.height,
                                )
                            bitmaps.add(bitmap)
                        }
                    }
                }
            }
            _uiState.update {
                it.copy(currentTrickplay = Trickplay(trickplayInfo.interval, bitmaps))
            }
        }
    }

    fun skipSegment(segment: FindroidSegment) {
        if (shouldSkipToNextEpisode(segment)) {
            player.seekToNextMediaItem()
        } else {
            player.seekTo(segment.endTicks)
        }
        _uiState.update { it.copy(currentSegment = null) }
    }

    // Check if the outro segment's end time is within n milliseconds of the player's total duration
    private fun shouldSkipToNextEpisode(segment: FindroidSegment): Boolean {
        return if (segment.type == FindroidSegmentType.OUTRO && player.hasNextMediaItem()) {
            val segmentEndTimeMillis = segment.endTicks
            val playerDurationMillis = player.duration
            val thresholdMillis =
                playerDurationMillis -
                    appPreferences.getValue(appPreferences.playerMediaSegmentsNextEpisodeThreshold)

            segmentEndTimeMillis > thresholdMillis
        } else {
            false
        }
    }

    private fun getSkipButtonTextStringId(segment: FindroidSegment): Int {
        return when (shouldSkipToNextEpisode(segment)) {
            true -> R.string.player_controls_next_episode
            false ->
                when (segment.type) {
                    FindroidSegmentType.INTRO -> R.string.player_controls_skip_intro
                    FindroidSegmentType.OUTRO -> R.string.player_controls_skip_outro
                    FindroidSegmentType.RECAP -> R.string.player_controls_skip_recap
                    FindroidSegmentType.COMMERCIAL -> R.string.player_controls_skip_commercial
                    FindroidSegmentType.PREVIEW -> R.string.player_controls_skip_preview
                    else -> R.string.player_controls_skip_unknown
                }
        }
    }

    /**
     * Get chapters of current item
     *
     * @return list of [PlayerChapter]
     */
    private fun getChapters(): List<PlayerChapter> {
        return uiState.value.currentChapters
    }

    /**
     * Get the index of the current chapter
     *
     * @return the index of the current chapter
     */
    private fun getCurrentChapterIndex(): Int? {
        val chapters = getChapters()

        for (i in chapters.indices.reversed()) {
            if (chapters[i].startPosition < player.currentPosition) {
                return i
            }
        }

        return null
    }

    /**
     * Get the index of the next chapter
     *
     * @return the index of the next chapter
     */
    private fun getNextChapterIndex(): Int? {
        val chapters = getChapters()
        val currentChapterIndex = getCurrentChapterIndex() ?: return null

        return minOf(chapters.size - 1, currentChapterIndex + 1)
    }

    /**
     * Get the index of the previous chapter. Only use this for seeking as it will return the
     * current chapter when player position is more than 5 seconds past the start of the chapter
     *
     * @return the index of the previous chapter
     */
    private fun getPreviousChapterIndex(): Int? {
        val chapters = getChapters()
        val currentChapterIndex = getCurrentChapterIndex() ?: return null

        // Return current chapter when more than 5 seconds past chapter start
        if (player.currentPosition > chapters[currentChapterIndex].startPosition + 5000L) {
            return currentChapterIndex
        }

        return maxOf(0, currentChapterIndex - 1)
    }

    fun isLastChapter(): Boolean =
        getChapters().let { chapters -> getCurrentChapterIndex() == chapters.size - 1 }

    /**
     * Seek to chapter
     *
     * @param [chapterIndex] the index of the chapter to seek to
     * @return the [PlayerChapter] which has been sought to
     */
    private fun seekToChapter(chapterIndex: Int): PlayerChapter? {
        return getChapters().getOrNull(chapterIndex)?.also { chapter ->
            player.seekTo(chapter.startPosition)
        }
    }

    /**
     * Seek to the next chapter
     *
     * @return the [PlayerChapter] which has been sought to
     */
    fun seekToNextChapter(): PlayerChapter? {
        return getNextChapterIndex()?.let { seekToChapter(it) }
    }

    /**
     * Seek to the previous chapter Will seek to start of current chapter if player position is more
     * than 5 seconds past start of chapter
     *
     * @return the [PlayerChapter] which has been sought to
     */
    fun seekToPreviousChapter(): PlayerChapter? {
        return getPreviousChapterIndex()?.let { seekToChapter(it) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        super.onIsPlayingChanged(isPlaying)
        eventsChannel.trySend(PlayerEvents.IsPlayingChanged(isPlaying))
    }
}

sealed interface PlayerEvents {
    data object NavigateBack : PlayerEvents

    data class IsPlayingChanged(val isPlaying: Boolean) : PlayerEvents
}
