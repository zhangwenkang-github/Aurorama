package com.zhangwenkang.cinefin.book.presentation.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import timber.log.Timber

/** 阅读页进度回传去抖：与 ARCHITECTURE §3.6 的 2 秒约定一致。 */
private const val PROGRESS_DEBOUNCE_MS = 2_000L

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    data class Ready(
        val itemId: UUID,
        val publication: Publication,
        val initialLocator: Locator?,
        val initialProgression: Double,
    ) : ReaderUiState

    data class Error(val message: String) : ReaderUiState
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

    private var openedItemId: UUID? = null
    private var openedAsset: Asset? = null
    private var progressJob: Job? = null

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
                    val (asset, publication) = openPublication(file)
                    openedAsset = asset
                    ReaderUiState.Ready(
                        itemId = itemId,
                        publication = publication,
                        initialLocator = progress?.locatorJson?.toLocator(),
                        initialProgression = progress?.progression ?: 0.0,
                    )
                }
            }
                .onSuccess { _state.value = it }
                .onFailure {
                    Timber.w(it, "打开 EPUB 失败")
                    _state.value = ReaderUiState.Error(it.message ?: "打开 EPUB 失败")
                }
        }
    }

    fun retry() {
        val itemId = openedItemId ?: return
        openedItemId = null
        open(itemId)
    }

    fun onLocationChanged(locator: Locator) {
        val itemId = openedItemId ?: return
        // totalProgression 是整本书的进度，progression 只代表当前资源（章节）内进度。
        val progression =
            locator.locations.totalProgression ?: locator.locations.progression ?: return
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(PROGRESS_DEBOUNCE_MS)
            val progress =
                ReadingProgress(
                    itemId = itemId,
                    locatorJson = locator.toJSON().toString(),
                    progression = progression,
                    positionTicks = progressionToTicks(progression),
                    updatedAt = Instant.now(),
                    pendingSync = true,
                )
            runCatching { readerRepository.saveReadingProgress(itemId, progress) }
                .onFailure { Timber.w(it, "保存阅读进度失败") }
        }
    }

    override fun onCleared() {
        progressJob?.cancel()
        openedAsset?.close()
        openedAsset = null
        super.onCleared()
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
