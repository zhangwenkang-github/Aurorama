package com.zhangwenkang.cinefin.player.local.subtitle

import com.zhangwenkang.cinefin.language.LanguageMatcher
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerSubtitleSource
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
    val style: SubtitleStyle = SubtitleStyle(),
    /** 主字幕是否由自研渲染接管；false = 交给播放内核原生渲染（或没有字幕） */
    val primaryManaged: Boolean = false,
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
    private var secondaryManaged = false
    private var primaryLoading = false
    private var secondaryLoading = false
    private val loading: Boolean
        get() = primaryLoading || secondaryLoading

    private var disabledByUser = false

    private var delayMs = appPreferences.getValue(appPreferences.playerSubtitleDelayMs)
    private var style = readStyle()

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
            autoSelect()
        } else {
            disabledByUser = restored.disabledByUser
            applyPrimary(restored.primaryIndex, persist = false)
            applySecondary(restored.secondaryIndex, persist = false)
            publish()
        }
    }

    /** 手动选主字幕；null = 关闭 */
    fun selectPrimary(index: Int?) {
        disabledByUser = index == null && sources.isNotEmpty()
        applyPrimary(index, persist = index != null)
        if (secondaryIndex == index) {
            applySecondary(null, persist = false)
        }
        rememberManualSelection()
        publish()
    }

    /** 手动选次字幕；null = 关闭 */
    fun selectSecondary(index: Int?) {
        if (index != null && index == primaryIndex) return
        applySecondary(index, persist = true)
        rememberManualSelection()
        publish()
    }

    /** 从偏好重新读延迟 / 外观（设置页改动后调用） */
    fun refreshFromPreferences() {
        delayMs = appPreferences.getValue(appPreferences.playerSubtitleDelayMs)
        style = readStyle()
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

    fun updateStyle(style: SubtitleStyle) {
        this.style = style
        appPreferences.setValue(appPreferences.playerSubtitleStyleSize, style.sizeIndex)
        appPreferences.setValue(appPreferences.playerSubtitleStyleColor, style.colorIndex)
        appPreferences.setValue(appPreferences.playerSubtitleStyleBackground, style.backgroundIndex)
        appPreferences.setValue(appPreferences.playerSubtitleStyleEdge, style.edgeIndex)
        appPreferences.setValue(appPreferences.playerSubtitleStylePosition, style.positionIndex)
        publish()
    }

    // ---------- 内部：选择与加载 ----------

    private fun autoSelect() {
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
        val secondary = pickSecondary(excludeIndex = primary?.index)
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

        val source = sources.firstOrNull { it.index == index }
        if (index == null || source == null || !source.isTextBased) {
            primaryCues = emptyList()
            primaryAssScript = null
            primaryManaged = false
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
        primaryLoadJob =
            loadSubtitle(source, isPrimary = true) { loaded ->
                if (primaryIndex == source.index) {
                    primaryCues = loaded.cues
                    // ASS/SSA 原文直接给 libass；SRT 等由渲染层现场生成 ASS（同样走 libass）
                    primaryAssScript = loaded.assScript
                    primaryManaged = loaded.assScript != null || loaded.cues.isNotEmpty()
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
        val tag = source.language.takeIf { it.isNotBlank() } ?: return
        val default = if (isSecondary) emptyList() else LanguageMatcher.DEFAULT_SUBTITLE_PRIORITY
        val current = LanguageMatcher.parsePriority(appPreferences.getValue(preference), default)
        val base = LanguageMatcher.baseOf(tag)
        val updated = listOf(tag) + current.filterNot { LanguageMatcher.baseOf(it) == base }
        appPreferences.setValue(preference, LanguageMatcher.priorityToString(updated))
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
                            val content = download(source.uri)
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
                style = style,
                primaryManaged = primaryManaged,
                secondaryManaged = secondaryManaged,
                loading = loading,
                primaryLoading = primaryLoading,
                disabledByUser = disabledByUser,
            )
    }

    private fun readStyle(): SubtitleStyle =
        SubtitleStyle(
            sizeIndex = appPreferences.getValue(appPreferences.playerSubtitleStyleSize),
            colorIndex = appPreferences.getValue(appPreferences.playerSubtitleStyleColor),
            backgroundIndex = appPreferences.getValue(appPreferences.playerSubtitleStyleBackground),
            edgeIndex = appPreferences.getValue(appPreferences.playerSubtitleStyleEdge),
            positionIndex = appPreferences.getValue(appPreferences.playerSubtitleStylePosition),
        )

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
