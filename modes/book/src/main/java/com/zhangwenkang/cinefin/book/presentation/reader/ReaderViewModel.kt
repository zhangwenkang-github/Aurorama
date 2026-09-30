package com.zhangwenkang.cinefin.book.presentation.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.repository.ReaderBookmark
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.repository.ReadingProgress
import com.zhangwenkang.cinefin.repository.progressionToTicks
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import timber.log.Timber

/** 阅读页进度回传去抖：与 ARCHITECTURE §3.6 的 2 秒约定一致。 */
private const val PROGRESS_DEBOUNCE_MS = 2_000L

/** 阅读中定时上报间隔（ARCHITECTURE §3.6：每 30 秒）。 */
private const val PROGRESS_PERIODIC_MS = 30_000L

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

    private val _bookmarks = MutableStateFlow<List<ReaderBookmark>>(emptyList())
    val bookmarks: StateFlow<List<ReaderBookmark>> = _bookmarks.asStateFlow()

    /** 待同步进度条数：>0 时阅读页提示"离线暂存"，联网后自动回传。 */
    private val _pendingSyncCount = MutableStateFlow(0)
    val pendingSyncCount: StateFlow<Int> = _pendingSyncCount.asStateFlow()

    private val _jumpTarget = MutableStateFlow<Locator?>(null)
    val jumpTarget: StateFlow<Locator?> = _jumpTarget.asStateFlow()

    private var openedItemId: UUID? = null
    private var openedAsset: Asset? = null
    private var openedPageSource: PageSource? = null
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
        _state.value = ReaderUiState.Loading

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val file = readerRepository.ensureLocalFile(itemId)
                    val progress = readerRepository.getReadingProgress(itemId)
                    ReaderUiState.Ready(
                        itemId = itemId,
                        document = openDocument(file, progress),
                        initialProgression = progress?.progression ?: 0.0,
                    )
                }
            }
                .onSuccess {
                    _state.value = it
                    refreshLocalState(itemId)
                    startPeriodicProgressReporter()
                }
                .onFailure {
                    Timber.w(it, "打开书籍失败")
                    closeDocuments()
                    _state.value = ReaderUiState.Error(it.message ?: "打开书籍失败")
                }
        }
    }

    fun retry() {
        val itemId = openedItemId ?: return
        closeDocuments()
        openedItemId = null
        open(itemId)
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

    /** 显式下载整本书（EB-11）：离线前先下好，飞行模式下也能打开。 */
    fun downloadBook() {
        val itemId = openedItemId ?: return
        if (_downloadState.value is BookDownloadState.Downloading) return
        downloadJob?.cancel()
        _downloadState.value = BookDownloadState.Downloading(0f)
        downloadJob = viewModelScope.launch {
            var lastPercent = -1
            runCatching {
                readerRepository.downloadLocalFile(itemId) { progress ->
                    val percent = (progress * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _downloadState.value = BookDownloadState.Downloading(progress)
                    }
                }
            }
                .onSuccess { file ->
                    _downloadState.value = BookDownloadState.Downloaded(file.length())
                }
                .onFailure {
                    Timber.w(it, "下载书籍失败")
                    _downloadState.value = BookDownloadState.Failed(it.message ?: "下载失败，请检查网络")
                }
        }
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
                pendingSync = true,
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

    private suspend fun refreshLocalState(itemId: UUID) {
        val localFile = runCatching { readerRepository.localFile(itemId) }.getOrNull()
        _downloadState.value =
            if (localFile == null) {
                BookDownloadState.NotDownloaded
            } else {
                BookDownloadState.Downloaded(localFile.sizeBytes)
            }
        refreshBookmarks()
        refreshPendingSyncCount()
    }

    override fun onCleared() {
        progressJob?.cancel()
        simpleProgressJob?.cancel()
        periodicJob?.cancel()
        downloadJob?.cancel()
        closeDocuments()
        super.onCleared()
    }

    /** 释放 Readium Asset 与 PDF / CBZ 页源；重复调用安全。 */
    private fun closeDocuments() {
        openedAsset?.close()
        openedAsset = null
        openedPageSource?.close()
        openedPageSource = null
    }

    private suspend fun locateByProgression(
        publication: Publication,
        progression: Double,
    ): Locator? = runCatching { publication.locateProgression(progression) }.getOrNull()

    /** 按内容分派：EPUB → Readium；PDF / CBZ → 页窗口自研视图。 */
    private suspend fun openDocument(file: File, progress: ReadingProgress?): ReaderDocument =
        when (sniffBookFormat(file)) {
            BookFormat.Pdf -> openSimple(file, SimpleBookFormat.Pdf, progress)
            BookFormat.ComicArchive -> openSimple(file, SimpleBookFormat.ComicArchive, progress)
            BookFormat.Epub,
            BookFormat.Unknown -> openReadium(file, progress)
        }

    private fun openSimple(
        file: File,
        format: SimpleBookFormat,
        progress: ReadingProgress?,
    ): ReaderDocument.Simple {
        val source =
            when (format) {
                SimpleBookFormat.Pdf -> PdfPageSource(file)
                SimpleBookFormat.ComicArchive -> ComicPageSource(file)
            }
        if (source.pageCount <= 0) {
            source.close()
            throw IllegalStateException("${format.label} 中没有可阅读的页面")
        }
        openedPageSource = source
        return ReaderDocument.Simple(
            format = format,
            pageSource = source,
            initialPage = pageIndexForProgression(progress?.progression ?: 0.0, source.pageCount),
        )
    }

    private suspend fun openReadium(file: File, progress: ReadingProgress?): ReaderDocument.Rich {
        val (asset, publication) = openPublication(file)
        openedAsset = asset
        // 服务端进度较新时本地没有对应 locator，用整书 progression 定位（EB-9 多设备冲突）。
        val initialLocator =
            progress?.locatorJson?.toLocator()
                ?: progress
                    ?.takeIf { it.progression > 0.0 }
                    ?.let { locateByProgression(publication, it.progression) }
        return ReaderDocument.Rich(publication = publication, initialLocator = initialLocator)
    }

    private suspend fun openPublication(file: File): Pair<Asset, Publication> {
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
        val assetResult = assetRetriever.retrieve(file)
        val asset =
            assetResult.getOrNull()
                ?: throw IllegalStateException("无法识别书籍格式：${assetResult.failureOrNull()}")

        return try {
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
            asset to publication
        } catch (error: Throwable) {
            asset.close()
            throw error
        }
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
    }
}

private fun String.toLocator(): Locator? {
    if (isBlank()) return null
    return runCatching { Locator.fromJSON(JSONObject(this)) }.getOrNull()
}
