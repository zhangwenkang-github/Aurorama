package com.zhangwenkang.cinefin.music.data

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsDisplayLanguage
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsDisplayState
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsDocument
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsPresenter
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsRepository
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackController
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 桌面歌词悬浮窗状态（W23-MUSIC · D 组，进程级单例）。
 *
 * 职责分工：
 * - 本控制器：读设置偏好（开关 / 颜色 / 字号 / 语言 / 锁定）、跟随播放进度算"当前句 + 下一句"、 决定悬浮窗该不该在（**播放中 + 开关开 + 有权限**）；
 * - [MusicLyricsOverlayHost] 实现（`app:phone`）：真正启动 / 停止悬浮窗 Service；
 * - 悬浮窗 Service：用 `WindowManager` 画两行歌词，拖动 / 单击面板由它处理。
 *
 * 与前台播放服务共存：悬浮窗 Service 是**普通** Service（不是前台服务），进程由音乐前台服务 / 播放器常驻，因此不需要也不应该再开一个前台通知。
 */
@Singleton
class MusicLyricsOverlayController
@Inject
constructor(
    private val playbackController: MusicPlaybackController,
    private val lyricsRepository: LyricsRepository,
    private val musicRepository: MusicRepository,
    private val appPreferences: AppPreferences,
    private val host: MusicLyricsOverlayHost,
) {

    data class State(
        val enabled: Boolean = false,
        val permissionGranted: Boolean = false,
        /** 是否正在播放音乐（决定悬浮窗该不该在）。 */
        val hasSession: Boolean = false,
        val title: String? = null,
        val artist: String? = null,
        val current: String? = null,
        val next: String? = null,
        val tint: LyricsOverlayTint = LyricsOverlayTint.MOON_WHITE,
        val size: LyricsOverlaySize = LyricsOverlaySize.MEDIUM,
        val language: LyricsDisplayLanguage = LyricsDisplayLanguage.SIMPLIFIED_CHINESE,
        val languages: List<LyricsDisplayLanguage> = emptyList(),
        val locked: Boolean = false,
    ) {
        /** 悬浮窗应在屏幕上。 */
        val visible: Boolean
            get() = enabled && permissionGranted && hasSession
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var started = false
    private var document: LyricsDocument? = null
    private var loadedItemId: UUID? = null
    private var loadJob: Job? = null
    private var positionMs = 0L
    private var serviceRequested = false
    /** 曲目元数据（歌手）：无歌词时回落显示「歌名 / 歌手」，按需拉一次曲库快照并常驻内存。 */
    private var trackMeta: Map<UUID, MusicSong>? = null
    private var trackMetaRequested = false
    private var trackMetaJob: Job? = null
    /** 无歌词回落日志去重（同一曲目 + 同一歌手只打一次）。 */
    private var fallbackLogged: Pair<UUID, String?>? = null
    /** 音乐 ViewModel 喂入的曲目歌手（曲库快照已就绪时比懒加载更快、离线也可用）。 */
    private val trackArtists = mutableMapOf<UUID, String>()

    // 设置页直接写偏好键，控制器用监听器跟随（同一开关在设置页与全屏播放页都能改）
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in OVERLAY_PREFERENCE_KEYS) refreshFromPreferences()
    }

    /** 幂等启动：音乐 ViewModel 首次进入时调用（与队列存档 / 播放历史同一套路）。 */
    fun start() {
        if (started) return
        started = true
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(
            preferenceListener
        )
        scope.launch {
            playbackController.queue
                .map { queue -> queue?.currentItem }
                .distinctUntilChanged { old, new -> old?.itemId == new?.itemId }
                .collect(::onCurrentItemChanged)
        }
        scope.launch { playbackController.positionMs.collect(::onPositionChanged) }
        scope.launch {
            // 权限可能在系统页里被改掉，播放中周期性复核（只重算状态，不做网络请求）
            while (isActive) {
                delay(PERMISSION_RECHECK_MS)
                if (_state.value.enabled) refreshFromPreferences()
            }
        }
        refreshFromPreferences()
    }

    fun setEnabled(enabled: Boolean) {
        Timber.i("桌面歌词开关：%s", if (enabled) "开启" else "关闭")
        appPreferences.setValue(appPreferences.musicLyricsOverlay, enabled)
        refreshFromPreferences()
    }

    fun toggle() = setEnabled(!_state.value.enabled)

    /** 关闭悬浮窗（面板上的「关闭」= 关掉开关，退出播放时由播放状态自动隐藏）。 */
    fun dismiss() = setEnabled(false)

    fun cycleTint() {
        val next = _state.value.tint.next()
        appPreferences.setValue(appPreferences.musicLyricsOverlayTint, next.key)
        Timber.i("桌面歌词颜色：%s", next.label)
        refreshFromPreferences()
    }

    fun cycleSize() {
        val next = _state.value.size.next()
        appPreferences.setValue(appPreferences.musicLyricsOverlaySize, next.key)
        Timber.i("桌面歌词字号：%s", next.label)
        refreshFromPreferences()
    }

    fun cycleLanguage() {
        val languages = _state.value.languages
        if (languages.isEmpty()) return
        val current = languages.indexOf(_state.value.language)
        val next = languages[(current + 1).mod(languages.size)]
        appPreferences.setValue(appPreferences.musicLyricsOverlayLanguage, next.name)
        Timber.i("桌面歌词语言：%s", next.label)
        refreshFromPreferences()
    }

    fun toggleLock() {
        val locked = !_state.value.locked
        appPreferences.setValue(appPreferences.musicLyricsOverlayLocked, locked)
        Timber.i("桌面歌词锁定：%s", if (locked) "已锁定（不可拖动）" else "已解锁")
        refreshFromPreferences()
    }

    /** 权限回来后由 UI 调用（授权页返回时刷新，不必等周期复核）。 */
    fun refreshPermission() = refreshFromPreferences()

    /** 曲库元数据可用时由 `MusicModeViewModel` 喂入歌手名（无歌词回落显示用，纯内存不落盘）。 */
    fun updateTrackArtist(itemId: UUID, artist: String?) {
        if (artist.isNullOrBlank() || trackArtists[itemId] == artist) return
        trackArtists[itemId] = artist
        if (loadedItemId == itemId) refreshLines()
    }

    /** 悬浮窗上次拖动后的位置（未记录 / 越界返回 null，由 Service 回落默认位并夹进屏幕）。 */
    fun storedPosition(): LyricsOverlayPosition? =
        storedOverlayPosition(
            appPreferences.getValue(appPreferences.musicLyricsOverlayX),
            appPreferences.getValue(appPreferences.musicLyricsOverlayY),
        )

    /**
     * 记录悬浮窗位置（W24 · A4）：拖动结束 / 兜底钳制后调用，跨进程与重启保留。
     *
     * 位置键不在 [OVERLAY_PREFERENCE_KEYS] 里，不会触发 [refreshFromPreferences] 的循环刷新。
     */
    fun savePosition(x: Int, y: Int) {
        Timber.i("桌面歌词位置：x=%d, y=%d", x, y)
        appPreferences.setValue(appPreferences.musicLyricsOverlayX, x)
        appPreferences.setValue(appPreferences.musicLyricsOverlayY, y)
    }

    private fun refreshFromPreferences() {
        val enabled = appPreferences.getValue(appPreferences.musicLyricsOverlay)
        val tint =
            LyricsOverlayTint.fromKey(
                appPreferences.getValue(appPreferences.musicLyricsOverlayTint)
            )
        val size =
            LyricsOverlaySize.fromKey(
                appPreferences.getValue(appPreferences.musicLyricsOverlaySize)
            )
        val locked = appPreferences.getValue(appPreferences.musicLyricsOverlayLocked)
        val language =
            runCatching {
                LyricsDisplayLanguage.valueOf(
                    appPreferences.getValue(appPreferences.musicLyricsOverlayLanguage)
                )
            }
                .getOrNull() ?: LyricsDisplayLanguage.SIMPLIFIED_CHINESE
        _state.update { state ->
            state.copy(
                enabled = enabled,
                permissionGranted = host.canDrawOverlays(),
                tint = tint,
                size = size,
                locked = locked,
                language = language,
            )
        }
        syncService()
        if (enabled) ensureLyricsLoaded() else clearLyrics()
        refreshLines()
    }

    private fun onCurrentItemChanged(item: PlayerItem?) {
        _state.update { state -> state.copy(hasSession = item != null, title = item?.name) }
        if (item == null) {
            loadedItemId = null
            document = null
            loadJob?.cancel()
        } else if (loadedItemId != item.itemId) {
            document = null
            loadedItemId = null
            loadJob?.cancel()
        }
        syncService(reEnsure = true)
        if (item != null && _state.value.enabled) ensureLyricsLoaded() else refreshLines()
    }

    private fun onPositionChanged(positionMs: Long) {
        this.positionMs = positionMs
        refreshLines()
    }

    /**
     * 悬浮窗该在就确保 Service 在跑，不该在就停掉（幂等）。
     *
     * [reEnsure] 只用在曲目切换时：Service 万一被系统回收，下次切歌会重新拉起； 周期性的权限复核走默认路径，避免每 3 秒重复 `startService`。
     */
    private fun syncService(reEnsure: Boolean = false) {
        val shouldRun = _state.value.visible
        if (shouldRun) {
            if (!serviceRequested || reEnsure) {
                serviceRequested = true
                host.ensureOverlayService()
            }
            return
        }
        if (serviceRequested) {
            serviceRequested = false
            host.stopOverlayService()
        }
    }

    private fun ensureLyricsLoaded() {
        val queue = playbackController.queue.value ?: return
        val item = queue.currentItem ?: return
        if (document != null && loadedItemId == item.itemId) return
        loadedItemId = item.itemId
        loadJob?.cancel()
        loadJob = scope.launch {
            val loaded = runCatching {
                lyricsRepository.getLyrics(
                    item.itemId,
                    localLyricsMediaPath(item.mediaSourceUri),
                )
            }
                .getOrNull()
            if (loadedItemId != item.itemId) return@launch
            document = loaded
            _state.update { state ->
                state.copy(
                    languages = loaded?.let(LyricsPresenter::displayLanguages) ?: emptyList()
                )
            }
            refreshLines()
        }
    }

    private fun clearLyrics() {
        loadJob?.cancel()
        document = null
        loadedItemId = null
        _state.update { state -> state.copy(current = null, next = null, artist = null) }
    }

    private fun refreshLines() {
        val loaded = document
        val itemId = loadedItemId
        val artist = itemId?.let { trackArtists[it] ?: trackMeta?.get(it)?.artist }
        if (loaded == null && itemId != null && _state.value.enabled) ensureTrackMeta(itemId)
        if (loaded == null) {
            if (itemId != null && fallbackLogged != (itemId to artist)) {
                fallbackLogged = itemId to artist
                Timber.i(
                    "桌面歌词：无歌词，回落歌名 / 歌手（%s / %s）",
                    _state.value.title ?: "—",
                    artist ?: "暂无歌词",
                )
            }
            _state.update { state -> state.copy(current = null, next = null, artist = artist) }
            return
        }
        val language = _state.value.language
        val rows =
            LyricsPresenter.rows(
                loaded,
                LyricsDisplayState(language = language, bilingual = false, follow = true),
            )
        val lines = overlayLyricsLines(rows, LyricsPresenter.activeIndex(rows, positionMs))
        val previousCurrent = _state.value.current
        _state.update { state ->
            state.copy(
                current = lines.current,
                next = lines.next,
                artist = artist,
            )
        }
        if (lines.current != previousCurrent) {
            Timber.i(
                "桌面歌词更新：当前句「%s」· 下一句「%s」",
                lines.current ?: "—",
                lines.next ?: "—",
            )
        }
    }

    /**
     * 懒加载曲库快照以补歌手名（无歌词时的回落显示）。
     *
     * 只在"当前有曲目且没有歌词"时触发一次；失败 / 离线时保留 null，由 UI 显示「暂无歌词」占位， 不阻塞歌词与播放。
     */
    private fun ensureTrackMeta(itemId: UUID) {
        if (trackMeta?.containsKey(itemId) == true) return
        if (trackMetaRequested || trackMetaJob?.isActive == true) return
        trackMetaRequested = true
        trackMetaJob = scope.launch {
            val loaded = runCatching {
                musicRepository.getLibrary().songs.associateBy { it.itemId }
            }
                .getOrNull()
            if (loaded != null) {
                trackMeta = loaded
                Timber.i("桌面歌词：曲库元数据已加载（%d 首），歌手可用于回落显示", loaded.size)
            } else {
                Timber.w("桌面歌词：曲库元数据加载失败，歌手回落为占位")
            }
            refreshLines()
        }
    }

    private companion object {
        val OVERLAY_PREFERENCE_KEYS =
            setOf(
                "pref_music_lyrics_overlay",
                "pref_music_lyrics_overlay_tint",
                "pref_music_lyrics_overlay_size",
                "pref_music_lyrics_overlay_language",
                "pref_music_lyrics_overlay_locked",
            )

        /** 权限复核周期：用户去系统页授权后回到 App 的兜底刷新（也可由 UI 主动调 refreshPermission）。 */
        const val PERMISSION_RECHECK_MS = 3_000L
    }
}

/** 本地音频路径（外挂 LRC 查找用，与歌词仓库同口径）：在线 / 转码地址返回 null。 */
internal fun localLyricsMediaPath(mediaSourceUri: String?): String? =
    when {
        mediaSourceUri == null -> null
        mediaSourceUri.startsWith("file://") -> mediaSourceUri.removePrefix("file://")
        mediaSourceUri.contains("://") -> null
        else -> mediaSourceUri
    }
