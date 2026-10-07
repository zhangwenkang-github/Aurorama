package com.zhangwenkang.cinefin.book.presentation.reader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.local.localItemIdFor
import com.zhangwenkang.cinefin.repository.ReaderBookmark
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.repository.ReadingProgress
import com.zhangwenkang.cinefin.repository.RemoteBookSource
import com.zhangwenkang.cinefin.repository.progressionToTicks
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.RemoteComicArchive
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.asset.DefaultArchiveOpener
import org.readium.r2.shared.util.asset.DefaultFormatSniffer
import org.readium.r2.shared.util.asset.DefaultResourceFactory
import org.readium.r2.shared.util.format.Specification
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import timber.log.Timber

/** 阅读页进度回传去抖：与 ARCHITECTURE §3.6 的 2 秒约定一致。 */
private const val PROGRESS_DEBOUNCE_MS = 2_000L

/** 阅读中定时上报间隔（ARCHITECTURE §3.6：每 30 秒）。 */
private const val PROGRESS_PERIODIC_MS = 30_000L

/** 搜索输入去抖：输入停顿后才真正开始扫描（打字时立即取消上一条扫描，保证输入跟手）。 */
private const val SEARCH_DEBOUNCE_MS = 400L

/**
 * W77 阅读流式：远程首开的等待上限。
 *
 * 取值依据实测（2026-10-07，K60 + 生产服务器）：单次 Range 请求 **1.4–20 s**（TLS 握手 2.5–7 s，热连接约 1.4 s， 服务端偶发 20 s
 * 级抖动），而打开一本 229 MB EPUB 只需「首块（含长度探测）+ 尾部/中央目录 + 首章」个位数请求： 实测远端就绪 **46.3 s**（同书整本下载 ≈2 min）。90 s
 * 是「仍明显优于整本下载」且不会无限卡 Loading 的折中。 超时会取消在途 HTTP（OkHttp call.cancel）并回退既有整本下载路径。
 */
private const val REMOTE_OPEN_TIMEOUT_MS = 90_000L

/**
 * W77-2：远端首块嗅探读取的字节数。
 *
 * 1 KiB 足够判定 `%PDF-` / ZIP 魔数 / ZIP 第一条目名（EPUB 的 `mimetype` 固定在文件最前），一次有界 Range 请求即可分流
 * EPUB（Readium）与 CBZ（远端页源），不必先读一遍中央目录。
 */
private const val REMOTE_SNIFF_BYTES = 1024

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    data class Ready(
        val itemId: UUID,
        val document: ReaderDocument,
        val initialProgression: Double,
    ) : ReaderUiState

    data class Error(val message: String) : ReaderUiState
}

/** 离线阅读（EB-11）：整本书在本地的状态。 */
sealed interface BookDownloadState {
    data object NotDownloaded : BookDownloadState

    data class Downloading(val progress: Float) : BookDownloadState

    data class Downloaded(val sizeBytes: Long) : BookDownloadState

    data class Failed(val message: String) : BookDownloadState
}

/**
 * PDF 搜索 UI 状态（W29-READER）：结果流式追加，进度按 [PDF_SEARCH_PROGRESS_STEP] 页节流刷新； 列表侧用 LazyColumn 懒加载，3649
 * 页文档也不会把整表塞进一帧。
 */
data class PdfSearchUiState(
    val query: String = "",
    val running: Boolean = false,
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    val scannedPages: Int = 0,
    val pageCount: Int = 0,
    val hits: List<PdfSearchHit> = emptyList(),
    val truncated: Boolean = false,
    val hasTextLayer: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class ReaderViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val readerRepository: ReaderRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    private val _downloadState =
        MutableStateFlow<BookDownloadState>(BookDownloadState.NotDownloaded)
    val downloadState: StateFlow<BookDownloadState> = _downloadState.asStateFlow()

    /**
     * W77-3：当前阅读来源态（顶栏三态文案的数据源之一，与 [downloadState] 一起决定渲染分支）。
     *
     * 本地 / 整本下载路径 = [ReaderStreamState.Local]；远端流式在嗅探与就绪期间 = [ReaderStreamState.StreamConnecting]，
     * 远端文档真正打开后 = [ReaderStreamState.Streaming]；手动下载完成热切换本地后回到 [ReaderStreamState.Local]。
     */
    private val _streamState = MutableStateFlow(ReaderStreamState.Local)
    val streamState: StateFlow<ReaderStreamState> = _streamState.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<ReaderBookmark>>(emptyList())
    val bookmarks: StateFlow<List<ReaderBookmark>> = _bookmarks.asStateFlow()

    /** 待同步进度条数：>0 时阅读页提示"离线暂存"，联网后自动回传。 */
    private val _pendingSyncCount = MutableStateFlow(0)
    val pendingSyncCount: StateFlow<Int> = _pendingSyncCount.asStateFlow()

    private val _jumpTarget = MutableStateFlow<Locator?>(null)
    val jumpTarget: StateFlow<Locator?> = _jumpTarget.asStateFlow()

    /** 本地高亮批注（W29，EB-8）：按 itemId 落文件，不写服务器。 */
    private val _annotations = MutableStateFlow<List<ReaderAnnotation>>(emptyList())
    val annotations: StateFlow<List<ReaderAnnotation>> = _annotations.asStateFlow()

    /** 页码跳转目标（搜索命中 / 批注跳转），由页序列视图消费后 [consumePageJumpTarget]。 */
    private val _pageJumpTarget = MutableStateFlow<Int?>(null)
    val pageJumpTarget: StateFlow<Int?> = _pageJumpTarget.asStateFlow()

    private val _searchState = MutableStateFlow(PdfSearchUiState())
    val searchState: StateFlow<PdfSearchUiState> = _searchState.asStateFlow()

    private val annotationStore =
        ReaderAnnotationStore(File(File(context.filesDir, "reader"), "annotations"))

    private var openedItemId: UUID? = null
    private var openedAsset: Asset? = null
    /** W77：当前打开的文档是否来自远程流式资产（后台整本下载完成后据此热切换本地）。 */
    private var openedFromRemote = false
    /** W77：远程阅读的 OkHttp 客户端实例（离开阅读页时 `close()` 兜底取消在途请求）。 */
    private var remoteHttpClient: ReadiumRemoteHttpClient? = null
    /** W77：远程读取的在途请求登记表（`onCleared` 时确定性取消；协程取消回调在真机上不总能中断读体）。 */
    private var remoteInFlight: InFlightRequestRegistry? = null
    /**
     * W77-4C：旧页源 / 资产的后台回收作用域——**故意不挂在 `viewModelScope` 上**。
     *
     * 热切换时旧远端源的 `close()` 会与在途 Range 读争用 `HttpByteSource` 的分块锁（真机实测阻塞主线程 ≈2.5 s， 触发 MIUI
     * `APP_SCOUT_WARNING`），所以回收丢到 IO 线程且不等待；即使紧接着离开阅读页也要让它跑完（否则丢句柄）。
     */
    private val recycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * W77：后台整本下载已完成、但阅读页当时还没就绪（Loading）时暂存的待热切换目标 （itemId →
     * 本地文件字节数）。阅读页就绪后立即补做热切换，避免「小书下载比远程首开还快」时漏切。
     */
    private var pendingHotSwap: Pair<UUID, Long>? = null
    private var openedPageSource: PageSource? = null
    private var openedBookFile: File? = null
    /** W37：本地媒体库书籍（SAF `content://`）；非空时源文件在用户文件夹里（索引只读，不拷贝）。 */
    private var localDocumentUri: Uri? = null
    private var openedSimpleFormat: SimpleBookFormat? = null
    private var openedSimplePageCount: Int = 0
    private var pdfSearchSource: PdfBoxPageTextSource? = null
    private var searchJob: Job? = null
    private var progressJob: Job? = null
    private var simpleProgressJob: Job? = null
    private var periodicJob: Job? = null
    private var downloadJob: Job? = null
    private var currentLocator: Locator? = null
    /** PDF / CBZ 的当前位置（页索引 + 总页数），与 [currentLocator] 二者只会有一个生效。 */
    private var currentSimplePage: Int? = null
    private var currentSimplePageCount: Int = 0
    private var lastPersistedLocatorJson: String? = null
    private var lastPersistedSimpleProgression: Double? = null

    /** 排版 / 主题设置：内存即时生效（提交给导航器），同时写回 `pref_reader_*`。 */
    fun updateSettings(settings: ReaderSettings) {
        val sanitized = settings.sanitized()
        if (sanitized == _settings.value) return
        _settings.value = sanitized
        persistSettings(sanitized)
    }

    fun open(itemId: UUID) {
        if (_state.value is ReaderUiState.Ready || openedItemId == itemId) return
        openedItemId = itemId
        localDocumentUri = null
        pendingHotSwap = null
        _streamState.value = ReaderStreamState.Local
        _state.value = ReaderUiState.Loading
        resetToolState()
        // 上一本若还挂着远程资产 / 客户端，先放掉（含超时回退留下的悬挂资产）。
        releaseRemoteClient()

        viewModelScope.launch {
            try {
                val ready = withContext(Dispatchers.IO) { openFromBestSource(itemId) }
                _state.value = ready
                refreshLocalState(itemId)
                // 后台下载可能比远程首开更快（小书）：阅读页就绪后补做热切换。
                hotSwapToLocal(itemId)
                startPeriodicProgressReporter()
            } catch (cancellation: CancellationException) {
                // 返回 / Activity 销毁：不再写状态、不弹错误，交给 onCleared 释放资源。
                throw cancellation
            } catch (error: Exception) {
                Timber.w(error, "打开书籍失败")
                closeDocuments()
                releaseRemoteClient()
                // W77-3：整本下载失败（含离线回退）时顶栏不要停在「下载中 0%」，转成可重试的下载失败态。
                if (_downloadState.value is BookDownloadState.Downloading) {
                    _downloadState.value = BookDownloadState.Failed(error.message ?: "下载失败，请检查网络")
                }
                _state.value = ReaderUiState.Error(error.message ?: "打开书籍失败")
            }
        }
    }

    /**
     * W77：打开入口的**来源决策**。
     *
     * 1. 本地已有整本文件 → 既有「本地打开」路径（W64 的 2 ms 级基线，零变化）；
     * 2. 未下载 + 联网 + 有会话 → **先试**远程流式（本卡只覆盖 EPUB），并行起后台整本下载；
     * 3. 远程不是 EPUB / 超时 / 失败 / 离线 → 回退既有「整本下载后打开」路径（不新增死路）。
     */
    private suspend fun openFromBestSource(itemId: UUID): ReaderUiState.Ready {
        val startedAt = SystemClock.elapsedRealtime()
        val hasLocalFile = runCatching { readerRepository.localFile(itemId) }.getOrNull() != null
        val online = hasActiveNetwork()
        val remote =
            if (!hasLocalFile && online) {
                runCatching { readerRepository.remoteBookSource(itemId) }.getOrNull()
            } else {
                null
            }

        if (
            decideReaderOpenSource(hasLocalFile, online, remote != null) ==
                ReaderOpenSource.RemoteCandidate && remote != null
        ) {
            val progress = readerRepository.getReadingProgress(itemId)
            // W77-3：远端流式载入中 —— 顶栏显示「流式载入中…」，不再误显「下载中 xx%」（W77-1 未覆盖②）。
            _streamState.value = ReaderStreamState.StreamConnecting
            openRemote(itemId, remote, progress, startedAt)?.let {
                _streamState.value = ReaderStreamState.Streaming
                return it
            }
            // 远端不可用（PDF / 未知格式 / 超时 / 失败）：回到本地口径，回退既有整本下载（顶栏随即显示「下载中 xx%」）。
            _streamState.value = ReaderStreamState.Local
            releaseRemoteClient()
            return openDownloaded(itemId, startedAt, progress)
        }
        return openDownloaded(itemId, startedAt, null)
    }

    /**
     * 既有路径：整本下载到应用私有目录后本地打开（W64 取消语义不变）。
     *
     * W77-3：下载进度写进顶栏 —— 未下载 PDF（以及未知格式 / 离线回退）在 Loading 期间显示「下载中 xx%」，
     * 不再是一个无信息量的转圈（`ensureLocalFile` 的 `onProgress` 原先被丢弃）。
     */
    private suspend fun openDownloaded(
        itemId: UUID,
        startedAt: Long,
        knownProgress: ReadingProgress?,
    ): ReaderUiState.Ready {
        _streamState.value = ReaderStreamState.Local
        // W64：未下载的书要等整本下载完成；返回 / 清理时协程取消，数据层会中止在途 HTTP。
        val reportProgress = downloadProgressReporter()
        val file =
            readerRepository.ensureLocalFile(itemId) { progress ->
                // 本地已有整本文件时只有一次 1f（立即返回）：不因此闪「下载中 100%」。
                if (progress < 1f) reportProgress(progress)
            }
        // 打开路径上文件已就绪（刚下完 / 本地命中）：顶栏转「离线可读」。
        _downloadState.value = BookDownloadState.Downloaded(file.length())
        val fileAt = SystemClock.elapsedRealtime()
        val progress = knownProgress ?: readerRepository.getReadingProgress(itemId)
        val progressAt = SystemClock.elapsedRealtime()
        val document = openDocument(file, progress)
        val documentAt = SystemClock.elapsedRealtime()
        openedFromRemote = false
        Timber.i(
            "打开书籍耗时：文件 %d ms / 进度 %d ms / 文档 %d ms / 合计 %d ms（%s）",
            fileAt - startedAt,
            progressAt - fileAt,
            documentAt - progressAt,
            documentAt - startedAt,
            itemId,
        )
        return ReaderUiState.Ready(
            itemId = itemId,
            document = document,
            initialProgression = progress?.progression ?: 0.0,
        )
    }

    /**
     * W77：远端流式首开总入口（EPUB 走 Readium，CBZ 走远端页源）。
     *
     * 90 s 远程等待上限覆盖「首块嗅探 + 目标格式就绪」；返回 null 表示远端不可用（离线 / PDF / 未知 / 超时 /
     * 异常），调用方回退既有整本下载路径；本方法**不抛业务错误**。
     *
     * 在途请求登记表在此创建并登记 [remoteInFlight]，EPUB 与 CBZ 两条路径共用同一份取消面。
     */
    private suspend fun openRemote(
        itemId: UUID,
        remote: RemoteBookSource,
        progress: ReadingProgress?,
        startedAt: Long,
    ): ReaderUiState.Ready? =
        withTimeoutOrNull(REMOTE_OPEN_TIMEOUT_MS) {
            val inFlight = InFlightRequestRegistry()
            remoteInFlight = inFlight
            try {
                when (sniffRemoteStreamKind(remote, inFlight)) {
                    // W77-3（D-F14 口径统一）：EPUB 打开**不再**自动后台整本下载，只按需取远端资产；
                    // 「下载整本」按钮语义保留，用户主动点才下载，完成后仍复用 hotSwap 热切换本地。
                    RemoteStreamKind.Epub ->
                        openRemoteEpub(itemId, remote, progress, startedAt, inFlight)

                    // CBZ：只按需取页（当前页 + 预取窗口），**不自动整本下载**（用户 2026-10-07 口径锁定）。
                    RemoteStreamKind.ComicArchive ->
                        openRemoteComic(itemId, remote, progress, startedAt, inFlight)

                    RemoteStreamKind.Pdf -> {
                        Timber.i("阅读流式：远端是 PDF（当前引擎不流式），回退整本下载（%s）", itemId)
                        null
                    }

                    RemoteStreamKind.Unknown,
                    null -> {
                        Timber.i("阅读流式：远端格式未识别，回退整本下载（%s）", itemId)
                        null
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                Timber.w(error, "阅读流式：远端首开失败，回退整本下载（%s）", itemId)
                null
            }
        }

    /**
     * W77-2：远端首块嗅探（1 个有界 Range 请求）。
     *
     * 取文件头 [REMOTE_SNIFF_BYTES] 字节判定 PDF / EPUB / CBZ：EPUB 与 CBZ 都是 ZIP，必须先分流才能选对渲染路径 （EPUB →
     * Readium；CBZ → 远端页源）。返回 null 表示不可用（非 206 / 异常），调用方回退整本下载。
     */
    private suspend fun sniffRemoteStreamKind(
        remote: RemoteBookSource,
        inFlight: InFlightRequestRegistry,
    ): RemoteStreamKind? =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url(remote.url)
                    .header(ACCESS_TOKEN_HEADER, remote.token)
                    .header("Range", "bytes=0-${REMOTE_SNIFF_BYTES - 1}")
                    .build()
            val call = readerRepository.readerHttpClient().newCall(request)
            inFlight.register(call)
            try {
                call.execute().use { response ->
                    // 必须确认 206：服务器忽略 Range 时（200）body 是整本，读它会退化成整本下载。
                    if (response.code != 206) {
                        Timber.w(
                            "阅读流式：远端嗅探未获 206（HTTP %d），回退整本下载",
                            response.code,
                        )
                        return@withContext null
                    }
                    val kind = classifyRemoteHeader(response.body.bytes())
                    Timber.i("阅读流式：远端首块嗅探 → %s（%s）", kind, remote.url)
                    kind
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // 返回阅读页（ViewModel 清理）时阻塞请求以 IOException 形式返回；此处还原取消语义，避免误判为「嗅探失败」。
                currentCoroutineContext().ensureActive()
                Timber.w(error, "阅读流式：远端首块嗅探失败，回退整本下载")
                null
            } finally {
                inFlight.unregister(call)
            }
        }

    /**
     * W77-2：远端 CBZ 页源首开（未下载 CBZ 先出页）。
     *
     * 只读「尾部 + 中央目录 + 目标条目」，不整本下载；读中央目录后再用 [remoteZipIsEpub] 复核 （首块启发式可能漏判非规范 EPUB）。返回 null
     * 表示失败，调用方回退整本下载；本方法不抛业务错误。
     */
    private suspend fun openRemoteComic(
        itemId: UUID,
        remote: RemoteBookSource,
        progress: ReadingProgress?,
        startedAt: Long,
        inFlight: InFlightRequestRegistry,
    ): ReaderUiState.Ready? =
        withContext(Dispatchers.IO) {
            val archive =
                RemoteComicArchive.openRemote(
                    client = readerRepository.readerHttpClient(),
                    url = remote.url,
                    headers = mapOf(ACCESS_TOKEN_HEADER to remote.token),
                    callRegistry = inFlight,
                )
            var handedOff = false
            try {
                // 复核：首条目不是 mimetype 的非规范 EPUB 会被首块启发式误判成 CBZ，读中央目录兜底。
                if (remoteZipIsEpub(archive.entries.map { it.name })) {
                    Timber.i("阅读流式：远端 ZIP 实为 EPUB，改用 Readium 路径（%s）", itemId)
                    archive.close()
                    return@withContext openRemoteEpub(
                        itemId,
                        remote,
                        progress,
                        startedAt,
                        inFlight,
                    )
                }
                val pages = remoteComicPageNames(archive)
                if (pages.isEmpty()) {
                    Timber.w("阅读流式：远端 CBZ 没有可读页面，回退整本下载（%s）", itemId)
                    return@withContext null
                }
                // 预取协程挂在阅读页作用域：离开阅读页随 ViewModel 一起取消（含在途预取）。
                val source = RemoteComicPageSource(archive, pages, prefetchScope = viewModelScope)
                openedFromRemote = true
                // openSimple 接管页源生命周期（openedPageSource / 页数），并把进度换算成起始页。
                val document = openSimple(source, SimpleBookFormat.ComicArchive, progress)
                handedOff = true
                Timber.i(
                    "阅读流式首开：远端 CBZ 就绪 %d ms（%d 页，%s）",
                    SystemClock.elapsedRealtime() - startedAt,
                    source.pageCount,
                    itemId,
                )
                ReaderUiState.Ready(
                    itemId = itemId,
                    document = document,
                    initialProgression = progress?.progression ?: 0.0,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                Timber.w(error, "阅读流式：远端 CBZ 打开失败，回退整本下载（%s）", itemId)
                null
            } finally {
                if (!handedOff) archive.close()
            }
        }

    /**
     * W77-1：远端 EPUB 流式首开（Readium 远程资产）。
     *
     * 返回 null 表示远端不可用（不是 EPUB / 嗅探失败 / 异常），调用方回退整本下载；本方法**不抛业务错误**。
     */
    private suspend fun openRemoteEpub(
        itemId: UUID,
        remote: RemoteBookSource,
        progress: ReadingProgress?,
        startedAt: Long,
        inFlight: InFlightRequestRegistry,
    ): ReaderUiState.Ready? =
        try {
            val url =
                Url(remote.url) as? AbsoluteUrl
                    ?: throw IllegalStateException("远程书籍地址非法：${remote.url}")
            val httpClient =
                ReadiumRemoteHttpClient(
                        readerRepository.readerHttpClient(),
                        remote.token,
                        inFlight,
                    )
                    .also { remoteHttpClient = it }
            // 根资源走「精确 Range」实现（Readium 的 HttpResource 会把有界读退化成开放式 Range）。
            val resourceFactory =
                RemoteFirstResourceFactory(
                    delegate = DefaultResourceFactory(context.contentResolver, httpClient),
                    client = readerRepository.readerHttpClient(),
                    token = remote.token,
                    inFlight = inFlight,
                )
            val assetRetriever =
                AssetRetriever(
                    resourceFactory,
                    DefaultArchiveOpener(),
                    DefaultFormatSniffer(),
                )
            val assetResult = assetRetriever.retrieve(url)
            val asset = assetResult.getOrNull()
            if (asset == null) {
                Timber.w("阅读流式：远程资产嗅探失败 %s", assetResult.failureOrNull())
                return null
            }
            if (!shouldStreamRemoteAsset(asset.format.conformsTo(Specification.Epub))) {
                Timber.i(
                    "阅读流式：远程资产不是 EPUB（%s），回退整本下载（%s）",
                    asset.format.mediaType,
                    itemId,
                )
                asset.close()
                return null
            }

            val publication = openPublication(assetRetriever, httpClient, asset)
            openedAsset = asset
            openedFromRemote = true
            // 服务端进度较新时本地没有对应 locator，用整书 progression 定位（EB-9 多设备冲突）。
            val initialLocator =
                progress?.locatorJson?.toLocator()
                    ?: progress
                        ?.takeIf { it.progression > 0.0 }
                        ?.let { locateByProgression(publication, it.progression) }
            val readyAt = SystemClock.elapsedRealtime()
            Timber.i(
                "阅读流式首开：远端就绪 %d ms（%s）",
                readyAt - startedAt,
                itemId,
            )
            ReaderUiState.Ready(
                itemId = itemId,
                document = ReaderDocument.Rich(publication, initialLocator),
                initialProgression = progress?.progression ?: 0.0,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Timber.w(error, "阅读流式：远程 EPUB 首开失败，回退整本下载（%s）", itemId)
            null
        }

    /**
     * W37 本地媒体库：直接打开用户文件夹里的书籍（`content://`，不拷贝源文件）。
     *
     * [itemId] 由文档 URI 推出（`localItemIdFor`）：进度 / 书签 / 批注复用同一套本机存储； 本地书籍的进度只落本机（不回传服务器）。
     */
    fun openLocal(uri: Uri, title: String?) {
        val itemId = localItemIdFor(uri.toString())
        if (_state.value is ReaderUiState.Ready && openedItemId == itemId) return
        openedItemId = itemId
        localDocumentUri = uri
        pendingHotSwap = null
        _streamState.value = ReaderStreamState.Local
        _state.value = ReaderUiState.Loading
        resetToolState()
        releaseRemoteClient()
        viewModelScope.launch {
            try {
                val ready =
                    withContext(Dispatchers.IO) {
                        val startedAt = SystemClock.elapsedRealtime()
                        val progress = readerRepository.getReadingProgress(itemId)
                        val progressAt = SystemClock.elapsedRealtime()
                        val document = openLocalDocument(uri, progress)
                        val documentAt = SystemClock.elapsedRealtime()
                        Timber.i(
                            "打开本地书籍耗时：进度 %d ms / 文档 %d ms / 合计 %d ms（%s）",
                            progressAt - startedAt,
                            documentAt - progressAt,
                            documentAt - startedAt,
                            itemId,
                        )
                        ReaderUiState.Ready(
                            itemId = itemId,
                            document = document,
                            initialProgression = progress?.progression ?: 0.0,
                        )
                    }
                _state.value = ready
                refreshLocalState(itemId)
                startPeriodicProgressReporter()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.w(error, "打开本地书籍失败")
                closeDocuments()
                _state.value = ReaderUiState.Error(error.message ?: "打开本地书籍失败")
            }
        }
        // title 只用于日志 / 后续扩展：阅读页标题由 Activity 侧的 intent extra 渲染。
        Timber.d("打开本地书籍 %s（%s）", title, uri)
    }

    fun retry() {
        val itemId = openedItemId ?: return
        val localUri = localDocumentUri
        closeDocuments()
        releaseRemoteClient()
        resetToolState()
        openedItemId = null
        if (localUri != null) openLocal(localUri, null) else open(itemId)
    }

    /** 打开新书 / 重试时清掉上一本的搜索、批注与跳转状态（进度与设置不动）。 */
    private fun resetToolState() {
        searchJob?.cancel()
        searchJob = null
        pdfSearchSource?.close()
        pdfSearchSource = null
        openedBookFile = null
        openedSimpleFormat = null
        openedSimplePageCount = 0
        _searchState.value = PdfSearchUiState()
        _annotations.value = emptyList()
        _pageJumpTarget.value = null
    }

    fun onLocationChanged(locator: Locator) {
        val itemId = openedItemId ?: return
        currentLocator = locator
        // totalProgression 是整本书的进度，progression 只代表当前资源（章节）内进度。
        val progression =
            locator.locations.totalProgression ?: locator.locations.progression ?: return
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(PROGRESS_DEBOUNCE_MS)
            persistProgress(itemId, locator, progression)
        }
    }

    /**
     * PDF / CBZ 翻页（EB-3 / EB-4）：整书进度 = 页索引 / 总页数，退回 EPUB 的 progression 语义， 服务端 ticks
     * 换算与多设备恢复（[pageIndexForProgression]）都复用同一条链路。
     */
    fun onSimplePageChanged(pageIndex: Int, pageCount: Int) {
        val itemId = openedItemId ?: return
        if (pageCount <= 0) return
        currentSimplePage = pageIndex.coerceIn(0, pageCount - 1)
        currentSimplePageCount = pageCount
        val progression = progressionForPage(currentSimplePage ?: 0, pageCount)
        if (progression == lastPersistedSimpleProgression) return
        simpleProgressJob?.cancel()
        simpleProgressJob = viewModelScope.launch {
            delay(PROGRESS_DEBOUNCE_MS)
            persistProgression(itemId, progression)
        }
    }

    /** 导航器就绪时的当前位置：只用于书签定位，不触发进度写入。 */
    fun onNavigatorLocator(locator: Locator) {
        if (currentLocator == null) currentLocator = locator
    }

    /** 退到后台 / 离开阅读页：立即落盘并尝试回传（ARCHITECTURE §3.6 上报时机）。 */
    fun onStopReading() {
        val itemId = openedItemId ?: return
        // 后台不继续扫 PDF 文本层（结果保留，回到阅读页可重新输入关键词继续）。
        cancelSearch()
        progressJob?.cancel()
        simpleProgressJob?.cancel()
        val locator = currentLocator
        val simpleProgression =
            currentSimplePage
                ?.takeIf { currentSimplePageCount > 0 }
                ?.let { progressionForPage(it, currentSimplePageCount) }
        if (locator == null && simpleProgression == null) return
        viewModelScope.launch {
            if (locator != null) {
                val progression =
                    locator.locations.totalProgression ?: locator.locations.progression
                if (progression != null) persistProgress(itemId, locator, progression)
            } else if (simpleProgression != null) {
                persistProgression(itemId, simpleProgression)
            }
            flushPendingProgress()
        }
    }

    /** 显式下载整本书（EB-11）：离线前先下好，飞行模式下也能打开。语义与 W3–W64 一致。 */
    fun downloadBook() {
        val itemId = openedItemId ?: return
        startDownload(itemId)
    }

    /**
     * W77：整本下载（「下载整本」按钮与远程首开的后台补下共用）。
     *
     * - 进度写入 [downloadState]（顶栏「下载中 xx%」）；
     * - 完成后若当前打开的是**远程流式**文档，则热切换到本地文件（页码 / locator 不回退）；
     * - 取消语义沿用 W64：离开阅读页 `onCleared` 取消 Job → 数据层 `call.cancel()` 中止在途 HTTP。
     */
    private fun startDownload(itemId: UUID) {
        if (_downloadState.value is BookDownloadState.Downloading) return
        downloadJob?.cancel()
        _downloadState.value = BookDownloadState.Downloading(0f)
        downloadJob = viewModelScope.launch {
            try {
                val file = readerRepository.downloadLocalFile(itemId, downloadProgressReporter())
                _downloadState.value = BookDownloadState.Downloaded(file.length())
                pendingHotSwap = itemId to file.length()
                hotSwapToLocal(itemId)
            } catch (cancellation: CancellationException) {
                // 离开阅读页时 onCleared 会取消下载并中止在途 HTTP；这是预期路径，不展示错误。
                throw cancellation
            } catch (error: Exception) {
                Timber.w(error, "下载书籍失败")
                // 远程流式已打开时下载失败不影响阅读，只在顶栏提示可重试。
                _downloadState.value = BookDownloadState.Failed(error.message ?: "下载失败，请检查网络")
            }
        }
    }

    /**
     * W77-3：下载进度 → 顶栏状态（按**整数百分比**节流）。
     *
     * 数据层每 64 KB 回调一次（100 MB 的书 ≈1600 次），直连 `StateFlow` 会让整页 Compose 每块都重组；这里只在百分比
     * 变化时写状态。`ensureLocalFile` 与 `downloadLocalFile` 共用。
     */
    private fun downloadProgressReporter(): (Float) -> Unit {
        var lastPercent = -1
        return { progress ->
            val percent = downloadPercent(progress)
            if (percent != lastPercent) {
                lastPercent = percent
                _downloadState.value = BookDownloadState.Downloading(progress)
            }
        }
    }

    /**
     * W77：后台整本下载完成后**热切换**到本地文件。
     *
     * EPUB 重建 `ReaderDocument.Rich`（新 publication 指向本地文件）并以当前 locator 复位；CBZ 换成本地页源并保持当前页索引
     * ——允许一帧重绘，但页码 / locator 不回退。任一步失败都保持远程文档可用（不清状态、不抛错）。
     */
    private suspend fun hotSwapToLocal(itemId: UUID) {
        val pending = pendingHotSwap ?: return
        if (pending.first != itemId) return
        val downloadedBytes = pending.second
        // 阅读页还没就绪（远程首开仍在进行）：保留 pending，等 `open()` 写状态后再补做。
        val current = _state.value as? ReaderUiState.Ready ?: return
        if (!shouldHotSwapToLocal(openedFromRemote, openedItemId == itemId, downloadedBytes)) {
            // 当前不是远程文档（本地打开 / 已经切过）：无需热切换。
            pendingHotSwap = null
            return
        }
        when (current.document) {
            is ReaderDocument.Rich -> hotSwapRichToLocal(itemId, current)
            is ReaderDocument.Simple -> hotSwapSimpleToLocal(itemId, current)
        }
    }

    /** W77-1：EPUB 远程 → 本地 publication 热切换（保持 locator）。 */
    private suspend fun hotSwapRichToLocal(itemId: UUID, current: ReaderUiState.Ready) {
        val startedAt = SystemClock.elapsedRealtime()
        // 当前位置：优先当前 locator；退化到 progression（多设备 / 刚打开还未回调 locator）。
        val locator = currentLocator
        val progression =
            locator?.locations?.totalProgression
                ?: locator?.locations?.progression
                ?: current.initialProgression
        val progress =
            ReadingProgress(
                itemId = itemId,
                locatorJson = locator?.toJSON()?.toString().orEmpty(),
                progression = progression,
                positionTicks = 0L,
                updatedAt = Instant.now(),
            )

        // 先摘下远程资产引用：openDocument 会覆写 openedAsset，失败时要能回滚。
        val previousAsset = openedAsset
        openedAsset = null
        val document =
            try {
                openDocument(readerRepository.ensureLocalFile(itemId), progress)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                openedAsset = previousAsset
                Timber.w(error, "阅读流式：热切换本地失败，保持远程阅读（%s）", itemId)
                pendingHotSwap = null
                return
            }
        // W77-4C：旧远端资产后台回收——`close()` 会等在途 Range 读让出分块锁，绝不能在主线程等。
        previousAsset?.let { asset -> recycleAsync(asset::close) }
        openedFromRemote = false
        _streamState.value = ReaderStreamState.Local
        pendingHotSwap = null
        releaseRemoteClient()
        _state.value = ReaderUiState.Ready(itemId, document, progression)
        Timber.i(
            "阅读流式：热切换本地完成 %d ms（%s）",
            SystemClock.elapsedRealtime() - startedAt,
            itemId,
        )
    }

    /**
     * W77-2：CBZ 远程页源 → 本地页源热切换（保持当前页索引）。
     *
     * 远端页源与本地页源都是 [ReaderDocument.Simple]，重建只换 `PageSource`（`SimpleBookView` 会重建位图缓存）；页数不变，
     * 位置按「当前页索引 / 总页数」换算成 progression 交给 [openSimple] 定位，页码不回退。
     */
    private suspend fun hotSwapSimpleToLocal(itemId: UUID, current: ReaderUiState.Ready) {
        val startedAt = SystemClock.elapsedRealtime()
        val pageCount = openedSimplePageCount
        val page =
            (currentSimplePage ?: (current.document as ReaderDocument.Simple).initialPage).coerceIn(
                0,
                maxOf(0, pageCount - 1),
            )
        val progression = progressionForPage(page, pageCount)
        val progress =
            ReadingProgress(
                itemId = itemId,
                locatorJson = "",
                progression = progression,
                positionTicks = 0L,
                updatedAt = Instant.now(),
            )

        // 先摘下远端页源引用：openDocument 会覆写 openedPageSource，失败时要能回滚。
        val previousSource = openedPageSource
        openedPageSource = null
        val document =
            try {
                openDocument(readerRepository.ensureLocalFile(itemId), progress)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                openedPageSource = previousSource
                Timber.w(error, "阅读流式：CBZ 热切换本地失败，保持远端阅读（%s）", itemId)
                pendingHotSwap = null
                return
            }
        if (document !is ReaderDocument.Simple) {
            // 理论上不会：本地文件仍是同一本 CBZ。保险起见保持远端阅读。
            runCatching { openedPageSource?.close() }
            runCatching { openedAsset?.close() }
            openedAsset = null
            openedPageSource = previousSource
            pendingHotSwap = null
            Timber.w("阅读流式：CBZ 热切换得到非页序列文档，保持远端阅读（%s）", itemId)
            return
        }
        // W77-4C：旧远端页源后台回收（同上）——切到本地页源后 UI 不再等待旧源关闭。
        previousSource?.let { source -> recycleAsync(source::close) }
        openedFromRemote = false
        _streamState.value = ReaderStreamState.Local
        pendingHotSwap = null
        releaseRemoteClient()
        _state.value = ReaderUiState.Ready(itemId, document, progression)
        Timber.i(
            "阅读流式：CBZ 热切换本地完成 %d ms（第 %d 页 / %d，%s）",
            SystemClock.elapsedRealtime() - startedAt,
            page + 1,
            pageCount,
            itemId,
        )
    }

    fun addBookmark() {
        val itemId = openedItemId ?: return
        val locator = currentLocator ?: return
        val progression =
            locator.locations.totalProgression ?: locator.locations.progression ?: return
        viewModelScope.launch {
            val bookmark =
                ReaderBookmark(
                    id = UUID.randomUUID().toString(),
                    itemId = itemId,
                    locatorJson = locator.toJSON().toString(),
                    progression = progression,
                    label = bookmarkLabel(locator.title, progression),
                    createdAt = Instant.now(),
                )
            runCatching { readerRepository.saveBookmark(bookmark) }
                .onSuccess { refreshBookmarks() }
                .onFailure { Timber.w(it, "保存书签失败") }
        }
    }

    fun removeBookmark(bookmarkId: String) {
        val itemId = openedItemId ?: return
        viewModelScope.launch {
            runCatching { readerRepository.deleteBookmark(itemId, bookmarkId) }
                .onSuccess { refreshBookmarks() }
                .onFailure { Timber.w(it, "删除书签失败") }
        }
    }

    /** 跳转到书签 / 位置（导航器消费后调用 [consumeJumpTarget]）。 */
    fun jumpTo(bookmark: ReaderBookmark) {
        _jumpTarget.value = bookmark.locatorJson.toLocator() ?: return
    }

    fun consumeJumpTarget() {
        _jumpTarget.value = null
    }

    /** 跳转到页序列文档的某一页（0-based）：搜索命中 / 批注列表共用。 */
    fun jumpToPage(pageIndex: Int) {
        if (openedSimpleFormat == null) return
        val pageCount = openedSimplePageCount
        if (pageCount <= 0) return
        _pageJumpTarget.value = pageIndex.coerceIn(0, pageCount - 1)
    }

    fun consumePageJumpTarget() {
        _pageJumpTarget.value = null
    }

    /** 关键词变化（搜索面板输入框每次改动都调用）： 空查询直接清空；非空则取消上一条扫描、去抖 [SEARCH_DEBOUNCE_MS] 后重新流式扫描。 */
    fun updateSearchQuery(raw: String) {
        val query = normalizeSearchQuery(raw)
        searchJob?.cancel()
        searchJob = null
        if (query.isEmpty()) {
            _searchState.value = PdfSearchUiState()
            return
        }
        if (openedSimpleFormat != SimpleBookFormat.Pdf) return
        // 页码 / 本地文件还没就绪（打开中）：不要留一个永远「扫描中」的状态。
        if (openedSimplePageCount <= 0 || openedBookFile == null) {
            _searchState.value = PdfSearchUiState(query = query)
            return
        }
        _searchState.value =
            PdfSearchUiState(query = query, running = true, pageCount = openedSimplePageCount)
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runSearch(query)
        }
    }

    /** 取消当前扫描（关面板 / 离开阅读页）：结果保留，状态改为未运行。 */
    fun cancelSearch() {
        val wasRunning = _searchState.value.running
        searchJob?.cancel()
        searchJob = null
        _searchState.update { it.copy(running = false, cancelled = it.cancelled || wasRunning) }
    }

    private suspend fun runSearch(query: String) {
        val file = openedBookFile ?: return
        val pageCount = openedSimplePageCount
        if (pageCount <= 0) {
            _searchState.update { it.copy(running = false) }
            return
        }
        val source =
            pdfSearchSource ?: PdfBoxPageTextSource(context, file).also { pdfSearchSource = it }
        val engine = PdfSearchEngine(source = source, pageCount = pageCount)
        runCatching { engine.search(query) { event -> applySearchEvent(event) } }
            .onFailure { error ->
                if (error is CancellationException) throw error
                Timber.w(error, "PDF 搜索失败")
                _searchState.update { it.copy(running = false, error = error.message ?: "搜索失败") }
            }
    }

    private fun applySearchEvent(event: PdfSearchEvent) {
        when (event) {
            is PdfSearchEvent.Progress ->
                _searchState.update {
                    it.copy(scannedPages = event.scannedPages, pageCount = event.pageCount)
                }

            is PdfSearchEvent.HitFound ->
                _searchState.update { it.copy(hits = it.hits + event.hit) }

            is PdfSearchEvent.Finished ->
                _searchState.update {
                    it.copy(
                        running = false,
                        finished = true,
                        scannedPages = event.scannedPages,
                        pageCount = event.pageCount,
                        truncated = event.truncated,
                        hasTextLayer = event.hasTextLayer,
                    )
                }
        }
    }

    /** 新增矩形批注（页面归一化矩形 + 备注）：只落本地文件（W29，D23）。 */
    fun addAnnotation(pageIndex: Int, rect: PageRect, note: String) {
        val itemId = openedItemId ?: return
        viewModelScope.launch {
            val annotation = newReaderAnnotation(itemId.toString(), pageIndex, rect, note)
            runCatching { annotationStore.save(annotation) }
                .onSuccess { _annotations.value = it }
                .onFailure { Timber.w(it, "保存批注失败") }
        }
    }

    fun updateAnnotationNote(annotationId: String, note: String) {
        val itemId = openedItemId ?: return
        viewModelScope.launch {
            runCatching { annotationStore.updateNote(itemId.toString(), annotationId, note) }
                .onSuccess { _annotations.value = it }
                .onFailure { Timber.w(it, "更新批注失败") }
        }
    }

    fun removeAnnotation(annotationId: String) {
        val itemId = openedItemId ?: return
        viewModelScope.launch {
            runCatching { annotationStore.delete(itemId.toString(), annotationId) }
                .onSuccess { _annotations.value = it }
                .onFailure { Timber.w(it, "删除批注失败") }
        }
    }

    private suspend fun persistProgress(itemId: UUID, locator: Locator, progression: Double) {
        persistProgression(itemId, progression, locatorJson = locator.toJSON().toString())
    }

    /**
     * 统一落盘 + 回传入口。
     *
     * [locatorJson] 为空表示 PDF / CBZ：恢复位置只依赖 progression（页索引 / 总页数）。
     */
    private suspend fun persistProgression(
        itemId: UUID,
        progression: Double,
        locatorJson: String = "",
    ) {
        val progress =
            ReadingProgress(
                itemId = itemId,
                locatorJson = locatorJson,
                progression = progression,
                positionTicks = progressionToTicks(progression),
                updatedAt = Instant.now(),
                // W37：本地媒体库书籍的进度只落本机（服务器没有这个 itemId，回传必然失败）。
                pendingSync = localDocumentUri == null,
            )
        runCatching { readerRepository.saveReadingProgress(itemId, progress) }
            .onSuccess {
                lastPersistedLocatorJson = progress.locatorJson
                lastPersistedSimpleProgression = progression
                refreshPendingSyncCount()
            }
            .onFailure { Timber.w(it, "保存阅读进度失败") }
    }

    /**
     * 30 秒定时上报（ARCHITECTURE §3.6）。
     *
     * 位置有变化就落盘 + 回传；位置没变但仍有待同步记录（断网时写入的）则重试回传， 于是关掉飞行模式后不需要重开阅读页也能补传。
     */
    private fun startPeriodicProgressReporter() {
        periodicJob?.cancel()
        periodicJob = viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_PERIODIC_MS)
                val itemId = openedItemId ?: continue
                val locator = currentLocator
                val locatorJson = locator?.toJSON()?.toString()
                val progression =
                    locator?.locations?.totalProgression ?: locator?.locations?.progression
                val simpleProgression =
                    currentSimplePage
                        ?.takeIf { currentSimplePageCount > 0 }
                        ?.let { progressionForPage(it, currentSimplePageCount) }
                when {
                    locator != null &&
                        progression != null &&
                        locatorJson != lastPersistedLocatorJson ->
                        persistProgress(itemId, locator, progression)

                    simpleProgression != null &&
                        simpleProgression != lastPersistedSimpleProgression ->
                        persistProgression(itemId, simpleProgression)

                    _pendingSyncCount.value > 0 -> flushPendingProgress()
                }
            }
        }
    }

    private suspend fun flushPendingProgress() {
        runCatching { readerRepository.flushPendingProgress() }
            .onSuccess { refreshPendingSyncCount() }
            .onFailure { Timber.w(it, "回传待同步阅读进度失败") }
    }

    private suspend fun refreshPendingSyncCount() {
        _pendingSyncCount.value =
            runCatching { readerRepository.pendingProgressCount() }.getOrDefault(0)
    }

    private suspend fun refreshBookmarks() {
        val itemId = openedItemId ?: return
        _bookmarks.value =
            runCatching { readerRepository.getBookmarks(itemId) }.getOrDefault(emptyList())
    }

    /** 读取当前书的本地批注（W29）：文件缺失 / 脏数据都回退为空，不阻塞阅读页。 */
    private suspend fun refreshAnnotations(itemId: UUID) {
        _annotations.value =
            runCatching { annotationStore.list(itemId.toString()) }.getOrDefault(emptyList())
    }

    private suspend fun refreshLocalState(itemId: UUID) {
        // W77-3：整本下载在跑（手动下载 / PDF 等回退下载）时不能被「本地还没有文件」覆盖成 NotDownloaded。
        if (_downloadState.value is BookDownloadState.Downloading) {
            refreshBookmarks()
            refreshAnnotations(itemId)
            refreshPendingSyncCount()
            return
        }
        // 本地媒体库书籍：文件在用户文件夹（SAF），用文档大小显示「离线可读」。
        _downloadState.value =
            if (localDocumentUri != null) {
                BookDownloadState.Downloaded(contentSize(localDocumentUri!!) ?: 0L)
            } else {
                val localFile = runCatching { readerRepository.localFile(itemId) }.getOrNull()
                if (localFile == null) {
                    BookDownloadState.NotDownloaded
                } else {
                    BookDownloadState.Downloaded(localFile.sizeBytes)
                }
            }
        refreshBookmarks()
        refreshAnnotations(itemId)
        refreshPendingSyncCount()
    }

    /** SAF 文档大小（`OpenableColumns.SIZE`），取不到返回 null。 */
    private fun contentSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it >= 0 } else null
        }
    }
        .getOrNull()

    override fun onCleared() {
        Timber.i("阅读页 onCleared：取消在途请求与后台下载（%s）", openedItemId)
        progressJob?.cancel()
        simpleProgressJob?.cancel()
        periodicJob?.cancel()
        downloadJob?.cancel()
        resetToolState()
        closeDocuments()
        releaseRemoteClient()
        super.onCleared()
    }

    /** 释放 Readium Asset 与 PDF / CBZ 页源；重复调用安全。 */
    private fun closeDocuments() {
        openedAsset?.close()
        openedAsset = null
        openedPageSource?.close()
        openedPageSource = null
        openedFromRemote = false
        _streamState.value = ReaderStreamState.Local
        pendingHotSwap = null
    }

    /**
     * W77-4C：把热切换淘汰下来的旧页源 / 资产丢给 [recycleScope] 后台回收，调用方**不等待**。
     *
     * 旧远端源的 `close()` 要等在途 Range 读让出 `HttpByteSource` 的分块锁（真机实测主线程阻塞 ≈2.5 s → MIUI
     * `APP_SCOUT_WARNING`）；取消语义已由 [InFlightRequestRegistry] 的**粘性取消**兜底，`close()` 在后台自然返回， 所以 UI
     * 与页源切换不必等它。失败只记日志（旧源已不再是当前文档，回收失败不影响阅读）。
     */
    private fun recycleAsync(close: () -> Unit) {
        recycleScope.launch { runCatching { close() }.onFailure { Timber.w(it, "阅读流式：旧源后台回收失败") } }
    }

    /**
     * W77：释放远程阅读客户端（离开阅读页时取消在途 HTTP，含 Readium 内部发起的章节 / 图片读取）。
     *
     * 远程文档仍在渲染时不能摘掉 `openedAsset`，因此只在「远程 doc 已不再使用」的路径调用 （打开新书 / 热切换完成 / 远端失败清理 /
     * onCleared）。重复调用安全。
     */
    private fun releaseRemoteClient() {
        remoteInFlight?.cancelAll()
        remoteInFlight = null
        remoteHttpClient?.close()
        remoteHttpClient = null
        if (openedFromRemote) {
            // 远程资产还挂着但已不再作为当前文档（超时 / 回退路径）：一并关闭。
            openedAsset?.close()
            openedAsset = null
            openedFromRemote = false
        }
    }

    /** 当前是否有活跃网络（离线 → 直接走整本下载路径，不发起远程流式）。 */
    private fun hasActiveNetwork(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return manager.activeNetwork != null
    }

    private suspend fun locateByProgression(
        publication: Publication,
        progression: Double,
    ): Locator? = runCatching { publication.locateProgression(progression) }.getOrNull()

    /**
     * 按内容分派：EPUB → Readium；PDF / CBZ → 页窗口自研视图。
     *
     * ZIP 里一张位图都没有时不算漫画包，交回 Readium（它能给出更准确的格式错误，也兼容结构异常的 EPUB）。
     */
    private suspend fun openDocument(file: File, progress: ReadingProgress?): ReaderDocument =
        when (sniffBookFormat(file)) {
            BookFormat.Pdf -> {
                openedBookFile = file
                val source = PdfPageSource(file)
                if (source.pageCount <= 0) {
                    source.close()
                    throw IllegalStateException("PDF 中没有可阅读的页面")
                }
                openSimple(source, SimpleBookFormat.Pdf, progress)
            }

            BookFormat.ComicArchive -> {
                val source = ComicPageSource(file)
                if (source.pageCount > 0) {
                    openSimple(source, SimpleBookFormat.ComicArchive, progress)
                } else {
                    source.close()
                    openReadium(file, progress)
                }
            }

            BookFormat.Epub,
            BookFormat.Unknown -> openReadium(file, progress)
        }

    /**
     * W37 本地媒体库书籍（`content://`）：PDF 走 `ParcelFileDescriptor` + PdfRenderer（版式元数据由
     * [PdfLayoutSource.forDescriptor] 用 dup fd 做 PdfBox 随机读，W48）、CBZ 走顺序流页源、 其余（EPUB / 未知） 交给
     * Readium 的 ContentResolver 资源链。
     */
    private suspend fun openLocalDocument(uri: Uri, progress: ReadingProgress?): ReaderDocument =
        when (sniffBookFormat(context.contentResolver, uri)) {
            BookFormat.Pdf -> {
                val descriptor =
                    context.contentResolver.openFileDescriptor(uri, "r")
                        ?: throw IllegalStateException("无法打开本地 PDF 文件")
                val source = PdfPageSource(descriptor)
                if (source.pageCount <= 0) {
                    source.close()
                    throw IllegalStateException("PDF 中没有可阅读的页面")
                }
                openSimple(source, SimpleBookFormat.Pdf, progress)
            }

            BookFormat.ComicArchive -> {
                val source = ContentUriComicPageSource(context.contentResolver, uri)
                if (source.pageCount > 0) {
                    openSimple(source, SimpleBookFormat.ComicArchive, progress)
                } else {
                    source.close()
                    openReadium(uri, progress)
                }
            }

            BookFormat.Epub,
            BookFormat.Unknown -> openReadium(uri, progress)
        }

    /** 打开好的页序列文档：接管 [PageSource] 生命周期（[closeDocuments] 统一释放）。 */
    private fun openSimple(
        source: PageSource,
        format: SimpleBookFormat,
        progress: ReadingProgress?,
    ): ReaderDocument.Simple {
        openedPageSource = source
        openedSimpleFormat = format
        openedSimplePageCount = source.pageCount
        return ReaderDocument.Simple(
            format = format,
            pageSource = source,
            initialPage = pageIndexForProgression(progress?.progression ?: 0.0, source.pageCount),
        )
    }

    private suspend fun openReadium(file: File, progress: ReadingProgress?): ReaderDocument.Rich {
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
        val assetResult = assetRetriever.retrieve(file)
        val asset =
            assetResult.getOrNull()
                ?: throw IllegalStateException("无法识别书籍格式：${assetResult.failureOrNull()}")
        val publication = openPublication(assetRetriever, httpClient, asset)
        openedAsset = asset
        openedFromRemote = false
        // 服务端进度较新时本地没有对应 locator，用整书 progression 定位（EB-9 多设备冲突）。
        val initialLocator =
            progress?.locatorJson?.toLocator()
                ?: progress
                    ?.takeIf { it.progression > 0.0 }
                    ?.let { locateByProgression(publication, it.progression) }
        return ReaderDocument.Rich(publication = publication, initialLocator = initialLocator)
    }

    /** W37：SAF `content://` 书籍（Readium 走 ContentResolver 资源）。 */
    private suspend fun openReadium(uri: Uri, progress: ReadingProgress?): ReaderDocument.Rich {
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
        val url =
            Url(uri.toString()) as? AbsoluteUrl ?: throw IllegalStateException("无法识别本地书籍地址：$uri")
        val assetResult = assetRetriever.retrieve(url)
        val asset =
            assetResult.getOrNull()
                ?: throw IllegalStateException("无法识别书籍格式：${assetResult.failureOrNull()}")
        val publication = openPublication(assetRetriever, httpClient, asset)
        openedAsset = asset
        openedFromRemote = false
        // 服务端进度较新时本地没有对应 locator，用整书 progression 定位（EB-9 多设备冲突）。
        val initialLocator =
            progress?.locatorJson?.toLocator()
                ?: progress
                    ?.takeIf { it.progression > 0.0 }
                    ?.let { locateByProgression(publication, it.progression) }
        return ReaderDocument.Rich(publication = publication, initialLocator = initialLocator)
    }

    /**
     * 解析出版物（本地 / 远程共用）。
     *
     * 失败时关闭 [asset]（远程资产会顺带释放 OkHttp 流），成功时把生命周期交给调用方 （`openedAsset` → `closeDocuments()`）。
     */
    private suspend fun openPublication(
        assetRetriever: AssetRetriever,
        httpClient: HttpClient,
        asset: Asset,
    ): Publication =
        try {
            val parser =
                DefaultPublicationParser(
                    context = context,
                    httpClient = httpClient,
                    assetRetriever = assetRetriever,
                    pdfFactory = null,
                )
            val opener = PublicationOpener(parser)
            val publicationResult = opener.open(asset, allowUserInteraction = false)
            val publication =
                publicationResult.getOrNull()
                    ?: throw IllegalStateException("解析书籍失败：${publicationResult.failureOrNull()}")
            publication
        } catch (error: Throwable) {
            asset.close()
            throw error
        }

    private fun readSettings(): ReaderSettings =
        ReaderSettings(
                mode = ReaderMode.fromStorage(appPreferences.getValue(appPreferences.readerMode)),
                fontSize = appPreferences.getValue(appPreferences.readerFontSize),
                lineHeight = appPreferences.getValue(appPreferences.readerLineHeight),
                pageMargins = appPreferences.getValue(appPreferences.readerPageMargins),
                font =
                    ReaderFont.fromStorage(
                        appPreferences.getValue(appPreferences.readerFontFamily)
                    ),
                theme =
                    ReaderTheme.fromStorage(appPreferences.getValue(appPreferences.readerTheme)),
                textAlign =
                    ReaderTextAlign.fromStorage(
                        appPreferences.getValue(appPreferences.readerTextAlign)
                    ),
                rtl = appPreferences.getValue(appPreferences.readerRtl),
            )
            .sanitized()

    private fun persistSettings(settings: ReaderSettings) {
        appPreferences.setValue(appPreferences.readerMode, settings.mode.storageValue)
        appPreferences.setValue(appPreferences.readerFontSize, settings.fontSize)
        appPreferences.setValue(appPreferences.readerLineHeight, settings.lineHeight)
        appPreferences.setValue(appPreferences.readerPageMargins, settings.pageMargins)
        appPreferences.setValue(appPreferences.readerFontFamily, settings.font.storageValue)
        appPreferences.setValue(appPreferences.readerTheme, settings.theme.storageValue)
        appPreferences.setValue(appPreferences.readerTextAlign, settings.textAlign.storageValue)
        appPreferences.setValue(appPreferences.readerRtl, settings.rtl)
    }
}

private fun String.toLocator(): Locator? {
    if (isBlank()) return null
    return runCatching { Locator.fromJSON(JSONObject(this)) }.getOrNull()
}
