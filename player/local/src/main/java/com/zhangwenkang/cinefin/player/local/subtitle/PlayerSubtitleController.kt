package com.zhangwenkang.cinefin.player.local.subtitle

import com.zhangwenkang.cinefin.language.LanguageMatcher
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerSubtitleSource
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleAppearance
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleLayout
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.player.local.domain.PlayerExtraPreferences
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.models.Preference
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** 字幕面板与自研渲染层共用的状态 */
data class SubtitleOverlayState(
    /** 当前媒体的全部字幕源（面板的轨道列表） */
    val sources: List<PlayerSubtitleSource> = emptyList(),
    /** 主字幕源序号；null = 未选中（关闭，或没有可自管的字幕） */
    val primaryIndex: Int? = null,
    /** 次字幕源序号；null = 关闭 */
    val secondaryIndex: Int? = null,
    /** 主字幕的完整 cue 列表（渲染层按播放位置自己取当前句） */
    val primaryCues: List<SubtitleCue> = emptyList(),
    /**
     * 主字幕的原始 ASS/SSA 脚本（libass 渲染用；W16）。
     *
     * 非空 = 这条字幕是 ASS/SSA 源，特效（定位 / 字体 / 动画）交给 libass 还原； null = SRT / WebVTT 等纯文本源，由渲染层用 cue 现场生成
     * ASS（同样走 libass）， 生成失败或 libass 不可用时再退回 [primaryCues] 文本渲染。
     */
    val primaryAssScript: String? = null,
    /** 次字幕的完整 cue 列表 */
    val secondaryCues: List<SubtitleCue> = emptyList(),
    val delayMs: Long = 0L,
    /** 主字幕外观（libass / Compose / 内核原生单字幕统一使用） */
    val primaryStyle: SubtitleStyle = SubtitleStyle(),
    /** 次字幕外观（Compose 文本层使用） */
    val secondaryStyle: SubtitleStyle =
        SubtitleStyle(sizePercent = SubtitleStyle.SECONDARY_DEFAULT_SIZE_PERCENT),
    /** 主/次共享布局：间距与底部位置 */
    val layout: SubtitleLayout = SubtitleLayout(),
    /** 主字幕是否由自研渲染接管；false = 交给播放内核原生渲染（或没有字幕） */
    val primaryManaged: Boolean = false,
    /**
     * 主字幕源是否有可交给 App 侧 libass 的文本内容。
     *
     * mpv 双字幕据此决定「接管主字幕」还是「保留原生 + 百分比兜底」：图形字幕没有文本，不能隐藏原生轨。
     */
    val primaryTextRenderable: Boolean = false,
    /** 文本主字幕已尝试加载但拿不到可用内容：mpv 双字幕不能再隐藏原生轨，否则会整条字幕消失。 */
    val primaryRenderFailed: Boolean = false,
    /**
     * mpv 当前是否选中了原生字幕轨（包括映射不到 Jellyfin 源的情况）。
     *
     * 与 [primaryIndex] 是两件事：映射失败时 [primaryIndex] 为 null，但原生主字幕仍会渲染，双字幕必须为它预留空间； `sid=no`
     * 时才是真的没有主字幕。
     */
    val nativePrimaryActive: Boolean = false,
    /** 次字幕是否存在（次字幕只走自研渲染） */
    val secondaryManaged: Boolean = false,
    /** 正在下载 / 解析字幕 */
    val loading: Boolean = false,
    /** 主字幕正在加载：路由层据此在「加载中」就禁用内核文字轨，避免闪一下原生字幕 */
    val primaryLoading: Boolean = false,
    /** 用户在当前媒体里显式关掉了字幕 */
    val disabledByUser: Boolean = false,
)

/**
 * 选择 mpv 主字幕时的安全路由（纯函数，便于单测）。
 *
 * - [selectedTrackId] 为 null：用户关闭主字幕，不抑制次字幕；
 * - 新主字幕能映射到 Jellyfin 源（[mappedSourceIndex] 非 null）：走 App 侧接管，不抑制；
 * - 新主字幕已选中但映射不到源：无法判断主 / 次是否同源，必须**立即**抑制次字幕， 不能等 `track-list` 异步回调（那段时间新原生主字幕已经可见、旧 Compose
 *   次字幕还在绘制）。
 */
fun shouldSuppressSecondaryForMpvPrimarySelection(
    selectedTrackId: Int?,
    mappedSourceIndex: Int?,
): Boolean = selectedTrackId != null && mappedSourceIndex == null

/**
 * 自研字幕管线：下载字幕文件 → 解析成 cue → 交给 Compose 覆盖层渲染。
 *
 * 为什么要有它（而不是直接用 Media3 的 SubtitleView）：
 * 1. Media3 没有字幕时间偏移 API，「延迟 ±0.1s 且立即生效」做不到；
 * 2. 一个 Player 同一时刻只能选一条文字轨，「双语次字幕」做不到；
 * 3. 字号 / 颜色 / 背景 / 描边 / 位置需要统一的面板入口，自绘最直接。
 *
 * 只处理文本字幕；图形字幕（PGS 等）与拿不到文件地址的字幕继续走播放内核原生渲染， 由 ViewModel 决定切换（见
 * `PlayerViewModel.applySubtitleRouting`）。
 */
class PlayerSubtitleController(
    private val scope: CoroutineScope,
    private val appPreferences: AppPreferences,
) {
    companion object {
        /** 延迟步长 0.1s，可调范围 ±10s */
        const val DELAY_STEP_MS = 100L
        const val DELAY_LIMIT_MS = 10_000L
    }

    private val _overlayState = MutableStateFlow(SubtitleOverlayState())
    val overlayState: StateFlow<SubtitleOverlayState> = _overlayState.asStateFlow()

    private var sources: List<PlayerSubtitleSource> = emptyList()
    private var primaryIndex: Int? = null
    private var secondaryIndex: Int? = null
    private var primaryCues: List<SubtitleCue> = emptyList()
    private var primaryAssScript: String? = null
    private var secondaryCues: List<SubtitleCue> = emptyList()
    private var primaryManaged = false
    private var primaryRenderFailed = false
    private var nativePrimaryActive = false
    /** 已为哪个主字幕序号发起过加载：在 publish() 唤醒 collector 前占位，避免同一源并发两次 loadSubtitle。 */
    private var primaryLoadStartedForIndex: Int? = null
    private var secondaryManaged = false
    private var primaryLoading = false
    private var secondaryLoading = false
    private val loading: Boolean
        get() = primaryLoading || secondaryLoading

    private var disabledByUser = false

    private var delayMs = appPreferences.getValue(appPreferences.playerSubtitleDelayMs)
    private var appearance = SubtitleAppearancePreferences.read(appPreferences)

    /** 已下载 / 解析的字幕缓存：`媒体 id:源序号` → 内容（按媒体隔离，避免跨集串字幕） */
    private val subtitleCache = mutableMapOf<String, LoadedSubtitle>()

    /** 一次下载的产物：cue 列表（文本 / 双语） + ASS 原文（libass） */
    private data class LoadedSubtitle(val cues: List<SubtitleCue>, val assScript: String?)

    private var mediaId: String = ""

    private var primaryLoadJob: Job? = null
    private var secondaryLoadJob: Job? = null

    init {
        publish()
    }

    val currentPrimarySource: PlayerSubtitleSource?
        get() = sources.firstOrNull { it.index == primaryIndex }

    /**
     * 换集 / 换片时调用：替换源清单并按语言偏好重新自动选择。
     *
     * [mediaId] 用于隔离解析缓存——不同集的字幕源序号可能重复（每集都有「字幕 3」）， 只按序号缓存会把上一集的字幕串到下一集。
     */
    fun reset(
        mediaId: String,
        sources: List<PlayerSubtitleSource>,
        /** mpv 继续由原生 `sid` 自动选主字幕；次字幕只在用户选择 / 手动记忆时加载。 */
        autoSecondary: Boolean = true,
    ) {
        primaryLoadJob?.cancel()
        secondaryLoadJob?.cancel()
        subtitleCache.clear()
        this.mediaId = mediaId
        this.sources = sources
        primaryIndex = null
        secondaryIndex = null
        primaryCues = emptyList()
        primaryAssScript = null
        secondaryCues = emptyList()
        primaryManaged = false
        primaryRenderFailed = false
        nativePrimaryActive = false
        primaryLoadStartedForIndex = null
        secondaryManaged = false
        primaryLoading = false
        secondaryLoading = false
        disabledByUser = false
        /* W74 #14②：切码率 / 换内核会重启播放页并重建这份清单——用户手动选过的字幕先按记忆恢复，
         * 没记忆（或记忆里的序号已不存在）才回到自动选择。 */
        val restored =
            PlayerSubtitleSelection.restore(
                memory = readManualSelection(),
                mediaId = mediaId,
                sources = sources,
            )
        if (restored == null) {
            autoSelect(autoSecondary = autoSecondary)
        } else {
            disabledByUser = restored.disabledByUser
            applyPrimary(restored.primaryIndex, persist = false)
            applySecondary(restored.secondaryIndex, persist = false)
            publish()
        }
    }

    /** 手动选主字幕；null = 关闭 */
    fun selectPrimary(index: Int?) {
        if (index != null) {
            val source = sources.firstOrNull { it.index == index } ?: return
            if (source.isTextBased) {
                assignPrimary(index)
                return
            }
            // 图形字幕没有可交给自研层的文本：保留在源序号上，由路由交给内核原生渲染。
            disabledByUser = false
            if (secondaryIndex == index) {
                applySecondary(null, persist = false)
            }
            applyPrimary(index, persist = false)
            rememberManualSelection()
            publish()
            return
        }
        disabledByUser = sources.isNotEmpty()
        applyPrimary(null, persist = false)
        rememberManualSelection()
        publish()
    }

    /** 手动选次字幕；null = 关闭 */
    fun selectSecondary(index: Int?) {
        if (index != null) {
            assignSecondary(index)
            return
        }
        applySecondary(index, persist = true)
        rememberManualSelection()
        publish()
    }

    /**
     * 把一条文本轨分配到主路。
     *
     * 若它当前在次路，只清空次路，不经过「用户显式关闭」语义，避免把 [disabledByUser] 写脏。
     */
    fun assignPrimary(index: Int) {
        val source = sources.firstOrNull { it.index == index && it.isTextBased } ?: return
        val next =
            SubtitleTrackAssignment.assignPrimary(
                SubtitleTrackPair(primaryIndex, secondaryIndex),
                source.index,
            )
        disabledByUser = false
        if (next.secondaryIndex == null && secondaryIndex != null) {
            applySecondary(null, persist = false)
        }
        applyPrimary(next.primaryIndex, persist = true)
        rememberManualSelection()
        publish()
    }

    /**
     * 把一条文本轨分配到次路。
     *
     * 若它当前在主路，清空主路并保持「非用户关闭」状态；次路渲染会由路由层抑制内核文字轨。
     */
    fun assignSecondary(index: Int) {
        val source = sources.firstOrNull { it.index == index && it.isTextBased } ?: return
        val next =
            SubtitleTrackAssignment.assignSecondary(
                SubtitleTrackPair(primaryIndex, secondaryIndex),
                source.index,
            )
        disabledByUser = false
        if (next.primaryIndex == null && primaryIndex != null) {
            applyPrimary(null, persist = false)
        }
        applySecondary(next.secondaryIndex, persist = true)
        rememberManualSelection()
        publish()
    }

    /** 主 / 次互换：调用方先确认两边都是不同文本轨。 */
    fun swapPrimarySecondary() {
        val next =
            SubtitleTrackAssignment.swap(SubtitleTrackPair(primaryIndex, secondaryIndex)) ?: return
        val primarySource = sources.firstOrNull { it.index == next.primaryIndex }
        val secondarySource = sources.firstOrNull { it.index == next.secondaryIndex }
        if (primarySource?.isTextBased != true || secondarySource?.isTextBased != true) return
        disabledByUser = false
        applyPrimary(next.primaryIndex, persist = true)
        applySecondary(next.secondaryIndex, persist = true)
        rememberManualSelection()
        publish()
    }

    /** 「自动选择」入口：丢掉本媒体的手动记忆，按当前模式 / 语言优先级重新挑选。 */
    fun autoSelectAndForgetManual(autoSecondary: Boolean = true) {
        disabledByUser = false
        appPreferences.setValue(PlayerExtraPreferences.subtitleManualSelection, "")
        autoSelect(autoSecondary = autoSecondary)
    }

    /** 只丢掉手动记忆与关闭态，让内核自己重选（mpv `sid=auto` 用，避免预加载主字幕文件）。 */
    fun forgetManualSelectionForAuto() {
        disabledByUser = false
        appPreferences.setValue(PlayerExtraPreferences.subtitleManualSelection, "")
        publish()
    }

    /** 用户手动把一条 mpv 源选为主字幕：像 Exo 一样记住主语言，供下一集自动选轨。 */
    fun rememberPrimaryLanguage(index: Int) {
        val source = sources.firstOrNull { it.index == index && !it.isGraphic } ?: return
        rememberLanguage(appPreferences.preferredSubtitleLanguages, source, isSecondary = false)
    }

    /**
     * 主字幕要占用当前次字幕轨时，清掉次路但不写手动记忆 / 不标记用户关闭。
     *
     * 供 mpv 主字幕切 `sid` 前使用；写入记忆由调用方在同步主字幕源序号后统一完成。
     */
    fun clearSecondaryForReassignment() {
        if (secondaryIndex == null && !secondaryManaged && !secondaryLoading) return
        applySecondary(null, persist = false)
        publish()
    }

    /**
     * 仅因渲染安全临时关闭次字幕（mpv 主字幕无法映射到源，无法判断是否同源）。
     *
     * 不写手动选择记忆：问题解除后用户仍能按原记忆恢复次字幕。
     */
    fun suppressSecondaryForSafety() {
        if (secondaryIndex == null && !secondaryManaged && !secondaryLoading) return
        applySecondary(null, persist = false)
        publish()
    }

    /**
     * mpv 主字幕仍由原生 `sid` 选择；把对应源序号同步进来。
     *
     * 序号变化时丢掉上一条主字幕的内容：渲染层可能在双字幕期间用这份内容接管主字幕，留着旧源会画出与 `sid` 不一致的另一句。真正需要内容时由
     * [ensurePrimaryContentAvailable] 按需加载，单字幕原生路径不额外下载。
     */
    fun syncPrimaryIndex(
        index: Int?,
        rememberManual: Boolean = false,
    ) {
        if (primaryIndex == index) {
            if (rememberManual) rememberManualSelection()
            return
        }
        primaryIndex = index
        primaryLoadJob?.cancel()
        primaryLoadJob = null
        primaryCues = emptyList()
        primaryAssScript = null
        primaryManaged = false
        primaryRenderFailed = false
        primaryLoading = false
        primaryLoadStartedForIndex = null
        // 立刻让覆盖层丢掉上一条主字幕：切轨期间宁可短暂空一帧，也不能继续画旧轨的内容。
        if (rememberManual) rememberManualSelection()
        publish()
    }

    /**
     * 同步「mpv 是否选中了原生主字幕」。
     *
     * 由 ViewModel 从实际轨道状态得出，与 [syncPrimaryIndex] 的映射结果相互独立：映射失败时原生主字幕仍然存在，
     * 渲染层需要据此走保守预留，而不是把它当成「没有主字幕」。
     */
    fun setNativePrimaryActive(active: Boolean) {
        if (nativePrimaryActive == active) return
        nativePrimaryActive = active
        publish()
    }

    /**
     * mpv 双字幕：主字幕改由 App 侧 libass 渲染时，按当前主字幕序号补加载文本内容。
     *
     * 只写渲染状态（cue / ASS / [SubtitleOverlayState.primaryManaged]），不改变选择、不写语言记忆、不触发自动选择；
     * 单字幕仍由内核原生渲染时不会走到这里。图形字幕没有可交给 libass 的文本，保持原生兜底。
     */
    fun ensurePrimaryContentAvailable() {
        // 同一主字幕源加载失败后不再重试：渲染层会恢复 mpv 原生轨，避免「缓存空结果 → 同步回调 → publish」
        // 在每个 overlay 状态上循环空转。换轨时 syncPrimaryIndex() 会重置失败态，重试只发生在换源之后。
        if (primaryManaged || primaryRenderFailed || primaryLoadJob?.isActive == true) return
        val source = sources.firstOrNull { it.index == primaryIndex } ?: return
        if (!source.isTextBased) return
        // publish() 会唤醒同一个 collector；先按序号占位，避免它在 primaryLoadJob 赋值前再次进入并重复下载。
        if (primaryLoadStartedForIndex == source.index) return
        primaryLoadStartedForIndex = source.index
        // 先发布加载态：面板与路由需要知道主字幕仍在准备；接管与否由 primaryTextRenderable 决定，不依赖加载时机。
        primaryLoading = true
        publish()
        primaryLoadJob =
            loadSubtitle(source, isPrimary = true) { loaded ->
                primaryLoading = false
                primaryLoadStartedForIndex = null
                if (primaryIndex == source.index) {
                    primaryCues = loaded.cues
                    primaryAssScript = loaded.assScript
                    primaryManaged = loaded.assScript != null || loaded.cues.isNotEmpty()
                    primaryRenderFailed = !primaryManaged
                }
                publish()
            }
    }

    /** 从偏好重新读延迟 / 外观（设置页改动后调用） */
    fun refreshFromPreferences() {
        delayMs = appPreferences.getValue(appPreferences.playerSubtitleDelayMs)
        appearance = SubtitleAppearancePreferences.read(appPreferences)
        publish()
    }

    fun adjustDelay(deltaMs: Long) {
        setDelay(this.delayMs + deltaMs)
    }

    fun setDelay(delayMs: Long) {
        this.delayMs = delayMs.coerceIn(-DELAY_LIMIT_MS, DELAY_LIMIT_MS)
        appPreferences.setValue(appPreferences.playerSubtitleDelayMs, this.delayMs)
        publish()
    }

    /** 主 / 次 / 布局一次性更新并自动保存（面板每次改动即时调用） */
    fun updateAppearance(appearance: SubtitleAppearance) {
        this.appearance = appearance
        SubtitleAppearancePreferences.write(appPreferences, appearance)
        publish()
    }

    // ---------- 内部：选择与加载 ----------

    private fun autoSelect(autoSecondary: Boolean = true) {
        val primary =
            PlayerSubtitleSelection.pickPrimary(
                mode = appPreferences.getValue(appPreferences.subtitleMode),
                priority =
                    LanguageMatcher.parsePriority(
                        appPreferences.getValue(appPreferences.preferredSubtitleLanguages),
                        LanguageMatcher.DEFAULT_SUBTITLE_PRIORITY,
                    ),
                sources = sources,
            )
        applyPrimary(primary?.index, persist = false)
        val secondary =
            if (autoSecondary) {
                pickSecondary(excludeIndex = primary?.index)
            } else {
                null
            }
        applySecondary(secondary?.index, persist = false)
        publish()
    }

    /** 次字幕：只在用户设置过次字幕语言时自动选；永远不与主字幕同源 */
    private fun pickSecondary(excludeIndex: Int?): PlayerSubtitleSource? {
        val raw = appPreferences.getValue(appPreferences.secondarySubtitleLanguages)
        val priority = LanguageMatcher.parsePriority(raw, emptyList())
        return PlayerSubtitleSelection.pickSecondary(
            priority = priority,
            sources = sources,
            excludeIndex = excludeIndex,
        )
    }

    /** 写入手动选择记忆（切码率 / 换内核重启后由 [reset] 恢复） */
    private fun rememberManualSelection() {
        if (mediaId.isBlank()) return
        val selection =
            SubtitleManualSelection(
                mediaId = mediaId,
                primaryIndex = primaryIndex ?: SubtitleManualSelection.NO_INDEX,
                secondaryIndex = secondaryIndex ?: SubtitleManualSelection.NO_INDEX,
            )
        appPreferences.setValue(PlayerExtraPreferences.subtitleManualSelection, selection.encode())
    }

    private fun readManualSelection(): SubtitleManualSelection? =
        SubtitleManualSelection.decode(
            appPreferences.getValue(PlayerExtraPreferences.subtitleManualSelection)
        )

    private fun applyPrimary(
        index: Int?,
        persist: Boolean,
    ) {
        primaryIndex = index
        primaryLoadJob?.cancel()
        primaryLoadJob = null
        primaryLoadStartedForIndex = null

        val source = sources.firstOrNull { it.index == index }
        if (index == null || source == null || !source.isTextBased) {
            primaryCues = emptyList()
            primaryAssScript = null
            primaryManaged = false
            primaryRenderFailed = false
            primaryLoading = false
            return
        }
        if (persist) {
            rememberLanguage(appPreferences.preferredSubtitleLanguages, source, isSecondary = false)
        }
        // 换源时先把旧 cue 清掉，避免短暂显示上一条字幕的内容
        primaryCues = emptyList()
        primaryAssScript = null
        primaryManaged = false
        primaryRenderFailed = false
        primaryLoadJob =
            loadSubtitle(source, isPrimary = true) { loaded ->
                if (primaryIndex == source.index) {
                    primaryCues = loaded.cues
                    // ASS/SSA 原文直接给 libass；SRT 等由渲染层现场生成 ASS（同样走 libass）
                    primaryAssScript = loaded.assScript
                    primaryManaged = loaded.assScript != null || loaded.cues.isNotEmpty()
                    primaryRenderFailed = !primaryManaged
                    publish()
                }
            }
    }

    private fun applySecondary(
        index: Int?,
        persist: Boolean,
    ) {
        secondaryIndex = index
        secondaryLoadJob?.cancel()
        secondaryLoadJob = null

        val source = sources.firstOrNull { it.index == index }
        if (index == null || source == null || !source.isTextBased) {
            secondaryCues = emptyList()
            secondaryManaged = false
            secondaryLoading = false
            return
        }
        if (persist) {
            rememberLanguage(appPreferences.secondarySubtitleLanguages, source, isSecondary = true)
        }
        secondaryCues = emptyList()
        secondaryManaged = false
        secondaryLoadJob =
            loadSubtitle(source, isPrimary = false) { loaded ->
                if (secondaryIndex == source.index) {
                    secondaryCues = loaded.cues
                    secondaryManaged = loaded.cues.isNotEmpty()
                    publish()
                }
            }
    }

    /** 把选中的语言提到优先级列表最前，下一集 / 下一个视频自动沿用 */
    private fun rememberLanguage(
        preference: Preference<String>,
        source: PlayerSubtitleSource,
        isSecondary: Boolean,
    ) {
        if (!appPreferences.getValue(appPreferences.rememberTrackSelection)) return
        val updated =
            PlayerSubtitleSelection.promotedLanguagePriority(
                current = appPreferences.getValue(preference),
                language = source.language,
                isSecondary = isSecondary,
            ) ?: return
        appPreferences.setValue(preference, updated)
    }

    /** 下载 + 解析（cue 列表 + ASS 原文）；命中缓存直接回调，返回 null 表示没有异步任务 */
    private fun loadSubtitle(
        source: PlayerSubtitleSource,
        isPrimary: Boolean,
        onReady: (LoadedSubtitle) -> Unit,
    ): Job? {
        val cacheKey = "$mediaId:${source.index}"
        val cached = subtitleCache[cacheKey]
        if (cached != null) {
            onReady(cached)
            return null
        }
        if (isPrimary) primaryLoading = true else secondaryLoading = true
        return scope.launch {
            val loaded =
                withContext(Dispatchers.IO) {
                    runCatching {
                            // 下载与解析一起兜底：解析器异常（含 Error）绝不能带崩播放页
                            // W74 #21：下载失败先短退避重试一次，避免瞬时抖动静默变成空字幕
                            val content =
                                retryOnce(
                                    delayMs = SUBTITLE_DOWNLOAD_RETRY_DELAY_MS,
                                    label = "字幕下载（index=${source.index}）",
                                ) {
                                    download(source.uri)
                                }
                            LoadedSubtitle(
                                cues = SubtitleParser.parse(content, source.codec),
                                assScript = assScriptOf(content, source.codec),
                            )
                        }
                        // 不打印完整地址：Jellyfin 的 DeliveryUrl 里带 ApiKey
                        .onFailure { Timber.w(it, "字幕加载失败（index=%d）", source.index) }
                        .getOrDefault(LoadedSubtitle(emptyList(), null))
                }
            subtitleCache[cacheKey] = loaded
            if (isPrimary) primaryLoading = false else secondaryLoading = false
            Timber.d(
                "字幕解析完成: index=%d, cues=%d, ass=%b",
                source.index,
                loaded.cues.size,
                loaded.assScript != null,
            )
            onReady(loaded)
        }
    }

    /**
     * ASS / SSA 源直接透传原始脚本（特效交给 libass）；SRT / WebVTT 返回 null， 由渲染层按当前画面尺寸生成 ASS（见
     * `PlayerSubtitleOverlay` / [AssSubtitleScript]）。
     */
    private fun assScriptOf(
        content: String,
        codec: String,
    ): String? =
        when (codec.lowercase()) {
            "ass",
            "ssa" -> AssSubtitleScript.normalize(content)
            else -> null
        }

    private fun publish() {
        _overlayState.value =
            SubtitleOverlayState(
                sources = sources,
                primaryIndex = primaryIndex,
                secondaryIndex = secondaryIndex,
                primaryCues = primaryCues,
                primaryAssScript = primaryAssScript,
                secondaryCues = secondaryCues,
                delayMs = delayMs,
                primaryStyle = appearance.primary,
                secondaryStyle = appearance.secondary,
                layout = appearance.layout,
                primaryManaged = primaryManaged,
                primaryTextRenderable =
                    sources.firstOrNull { it.index == primaryIndex }?.isTextBased == true,
                primaryRenderFailed = primaryRenderFailed,
                nativePrimaryActive = nativePrimaryActive,
                secondaryManaged = secondaryManaged,
                loading = loading,
                primaryLoading = primaryLoading,
                disabledByUser = disabledByUser,
            )
    }

    // ---------- 文件下载与解码 ----------

    /**
     * 读取字幕内容。
     *
     * W27：除 Jellyfin 的 HTTP 地址外，本机侧载字幕用 `file://` 地址（导入时已复制到 App 私有目录）， 不能强转
     * HttpURLConnection，需要单独走文件读取。
     */
    private fun download(uri: String): String {
        if (uri.startsWith("file:")) {
            return decodeText(File(URI(uri)).readBytes())
        }
        val connection =
            (URL(uri).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
            }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            return decodeText(connection.inputStream.use { it.readBytes() })
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 字幕文件编码识别：先看 BOM，再严格试 UTF-8，失败退回 GB18030。
     *
     * 老中文字幕常用 GBK/GB18030，直接按 UTF-8 读会整段乱码；这里先严格解码， 一旦有非法字节就交给 GB18030（GBK 的超集，能覆盖绝大多数中文老字幕）。
     */
    private fun decodeText(bytes: ByteArray): String {
        if (
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("GB18030"))
        }
    }
}
