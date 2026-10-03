package com.zhangwenkang.cinefin.presentation.local

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.local.LocalFolderBrowseMode
import com.zhangwenkang.cinefin.local.LocalLibrary
import com.zhangwenkang.cinefin.local.LocalLibraryBrowse
import com.zhangwenkang.cinefin.local.LocalLibraryEntry
import com.zhangwenkang.cinefin.local.LocalLibraryGrouping
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.local.LocalLibraryType
import com.zhangwenkang.cinefin.local.LocalMediaKind
import com.zhangwenkang.cinefin.music.data.LocalMusicPlayer
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * W37 本地媒体库总览（媒体库页常显入口）：
 * - 建立本地库（名称 + 类型 + SAF 文件夹）；
 * - 库卡列表（含隐藏库的管理视图）；
 * - 首页是否显示本地媒体（`pref_local_library_visible`，默认关）。
 */
@HiltViewModel
class LocalLibraryViewModel
@Inject
constructor(
    private val repository: LocalLibraryRepository,
    private val appPreferences: AppPreferences,
    /** W45：库卡封面（懒生成，卡片可见时调用 [loadCover]）。 */
    private val thumbnails: LocalThumbnailProvider,
) : ViewModel() {

    data class LibraryCard(
        val id: Long,
        val name: String,
        val type: LocalLibraryType,
        val folderCount: Int,
        val itemCount: Int,
        val countsByKind: Map<LocalMediaKind, Int>,
        val visibleInLibrary: Boolean,
    ) {
        /** W39 去重口径：单一类型 = `书籍 · 1 个文件夹`（总数由行尾「N 项」承担）；混合库列各类型计数。 */
        val detail: String
            get() =
                localLibraryCardDetail(
                    typeLabel = type.label,
                    folderCount = folderCount,
                    itemCount = itemCount,
                    countsByKind = countsByKind,
                    visibleInLibrary = visibleInLibrary,
                )
    }

    data class UiState(
        val loading: Boolean = true,
        val cards: List<LibraryCard> = emptyList(),
        /** 管理视图：显示「在媒体库显示」已关闭的库（与离线媒体库的「眼睛」同语义）。 */
        val showHidden: Boolean = false,
        /** 首页是否显示本地媒体（默认关）。 */
        val homeVisible: Boolean = false,
        /**
         * 库卡封面（W45）：`libraryId -> Coil 模型`（本地缩略图绝对路径 / 音乐既有 coverUri）。 键存在但值为 null =
         * 已尝试且无封面（回退类型图标，不再重试）。
         */
        val covers: Map<Long, String?> = emptyMap(),
    ) {
        val visibleCards: List<LibraryCard>
            get() = if (showHidden) cards else cards.filter { it.visibleInLibrary }

        val hasHidden: Boolean
            get() = cards.any { !it.visibleInLibrary }
    }

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    /**
     * 封面请求去重：`libraryId -> 请求时的条目数`。
     *
     * 空库（尚未扫描）不请求——新建库时库卡会先以「0 项」出现，若此刻请求会拿到 null 并永久缓存 （真机拦下：库卡封面要进详情页才出现）；扫描完成、条目数变化后再触发一次。
     */
    private val coverRequests = mutableMapOf<Long, Int>()

    init {
        _state.value =
            _state.value.copy(
                homeVisible = appPreferences.getValue(appPreferences.localLibraryVisible)
            )
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val libraries = runCatching {
                repository.libraries(includeHidden = true)
            }
                .getOrDefault(emptyList())
            _state.value =
                _state.value.copy(
                    loading = false,
                    cards = libraries.map { it.toCard() },
                    homeVisible = appPreferences.getValue(appPreferences.localLibraryVisible),
                )
        }
    }

    fun setShowHidden(show: Boolean) {
        _state.value = _state.value.copy(showHidden = show)
    }

    /**
     * 库卡封面（W45）：卡片 / 库卡可见时按需请求；内部按 `libraryId` 去重，失败也不反复重试。
     *
     * 封面 = 「视频 → 书籍 → 音乐」顺序里第一个有缩略图的条目；全无则保持 null（UI 回退类型图标）。
     */
    fun loadCover(libraryId: Long) {
        val card = _state.value.cards.firstOrNull { it.id == libraryId } ?: return
        if (card.itemCount <= 0) return
        if (coverRequests[libraryId] == card.itemCount) return
        coverRequests[libraryId] = card.itemCount
        viewModelScope.launch {
            val cover = runCatching { thumbnails.libraryCover(libraryId) }.getOrNull()
            _state.value = _state.value.copy(covers = _state.value.covers + (libraryId to cover))
        }
    }

    /** 建库；[onCreated] 回调库 id（UI 随后拉起 SAF 目录选择）。 */
    fun createLibrary(name: String, type: LocalLibraryType, onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = repository.createLibrary(name, type)
            refresh()
            onCreated(id)
        }
    }

    fun addFolder(libraryId: Long, treeUri: Uri) {
        viewModelScope.launch {
            repository.addFolder(libraryId, treeUri)
            refresh()
        }
    }

    fun setLibraryVisible(id: Long, visible: Boolean) {
        viewModelScope.launch {
            repository.updateLibrary(id, visible = visible)
            refresh()
        }
    }

    fun deleteLibrary(id: Long) {
        viewModelScope.launch {
            repository.deleteLibrary(id)
            refresh()
        }
    }

    private fun LocalLibrary.toCard(): LibraryCard =
        LibraryCard(
            id = id,
            name = name,
            type = type,
            folderCount = folders.size,
            itemCount = itemCount,
            countsByKind = countsByKind,
            visibleInLibrary = visibleInLibrary,
        )
}

/**
 * W39 本地库卡副标题（纯函数，便于单测）：
 * - 单一媒体类型（含「混合」库但只有一类文件）：`书籍 · 1 个文件夹`——不再重复「书籍 10」， 总数由行尾「N 项」承担；空库只提示「尚未扫描到媒体」；
 * - 多类型：`混合 · 2 个文件夹 · 视频 1 · 音乐 2 · 书籍 3`；
 * - 库级「在媒体库显示」关闭时追加 ` · 已隐藏`。
 */
internal fun localLibraryCardDetail(
    typeLabel: String,
    folderCount: Int,
    itemCount: Int,
    countsByKind: Map<LocalMediaKind, Int>,
    visibleInLibrary: Boolean,
): String {
    val kinds =
        LocalMediaKind.entries.mapNotNull { kind ->
            countsByKind[kind]?.takeIf { it > 0 }?.let { "${kind.label} $it" }
        }
    return buildString {
        append(typeLabel).append(" · ").append(folderCount).append(" 个文件夹")
        when {
            itemCount <= 0 -> append(" · 尚未扫描到媒体")
            kinds.size > 1 -> append(" · ").append(kinds.joinToString(" · "))
        }
        if (!visibleInLibrary) append(" · 已隐藏")
    }
}

/** 本地库详情：文件夹管理（添加 / 移除 / 层级与平铺）+ 库级设置 + 条目浏览。 */
@HiltViewModel
class LocalLibraryDetailViewModel
@Inject
constructor(
    private val repository: LocalLibraryRepository,
    /** W37：本地曲目起播复用音乐链路（同一 MusicPlaybackController / 队列模型）。 */
    private val localMusicPlayer: LocalMusicPlayer,
    /** W45：列表行 / 头部缩略图（懒生成）。 */
    private val thumbnails: LocalThumbnailProvider,
) : ViewModel() {

    data class ContentGroup(val title: String?, val rows: List<LocalLibraryBrowse.Row>)

    data class FolderContent(
        val folderId: Long,
        val name: String,
        val browseMode: LocalFolderBrowseMode,
        val itemCount: Int,
        val groups: List<ContentGroup>,
    )

    data class UiState(
        val loading: Boolean = true,
        val libraryId: Long = 0L,
        val name: String = "",
        val type: LocalLibraryType = LocalLibraryType.MIXED,
        val visibleInLibrary: Boolean = true,
        val folderCount: Int = 0,
        val itemCount: Int = 0,
        val folders: List<FolderContent> = emptyList(),
        /** W45 详情头部封面（本地缩略图绝对路径 / 音乐 coverUri）；null = 生成中或已失败（回退类型图标）。 */
        val headerCover: String? = null,
        /** W45 列表行缩略图：`itemId -> Coil 模型`；值为 null = 已尝试且无缩略图。 */
        val thumbs: Map<UUID, String?> = emptyMap(),
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    /** W45 请求去重（每个条目 / 头部请求一次）。 */
    private val thumbRequests = mutableSetOf<UUID>()
    /** 头部封面请求时的条目数（空库不请求；新增文件夹扫描完成后再请求一次）。 */
    private var headerRequestedCount = -1

    fun setup(libraryId: Long) {
        if (_state.value.libraryId == libraryId) return
        _state.value = _state.value.copy(libraryId = libraryId)
        refresh()
    }

    fun refresh() {
        val libraryId = _state.value.libraryId
        if (libraryId == 0L) return
        viewModelScope.launch {
            val library = runCatching { repository.library(libraryId) }.getOrNull()
            val entries = runCatching { repository.entries(libraryId) }.getOrDefault(emptyList())
            if (library == null) {
                _state.value = _state.value.copy(loading = false, folders = emptyList())
                return@launch
            }
            _state.value =
                UiState(
                    loading = false,
                    libraryId = library.id,
                    name = library.name,
                    type = library.type,
                    visibleInLibrary = library.visibleInLibrary,
                    folderCount = library.folders.size,
                    itemCount = entries.size,
                    folders = library.folders.map { folder -> folder.toContent(library, entries) },
                    headerCover = _state.value.headerCover,
                    thumbs = _state.value.thumbs,
                )
            loadHeaderCover()
        }
    }

    /** W45：详情头部封面——库内「视频 → 书籍 → 音乐」第一个有缩略图的条目；全无回退类型图标。 */
    fun loadHeaderCover() {
        val state = _state.value
        if (state.libraryId == 0L || state.itemCount <= 0) return
        if (headerRequestedCount == state.itemCount) return
        headerRequestedCount = state.itemCount
        viewModelScope.launch {
            val cover = runCatching { thumbnails.libraryCover(state.libraryId) }.getOrNull()
            _state.value = _state.value.copy(headerCover = cover)
        }
    }

    /** W45：列表行缩略图——行可见时按需生成（失败标记后不再重试，回退类型图标）。 */
    fun loadThumbnail(entry: LocalLibraryEntry) {
        if (entry.itemId in thumbRequests) return
        thumbRequests += entry.itemId
        viewModelScope.launch {
            val thumb = runCatching { thumbnails.thumbnail(entry) }.getOrNull()
            _state.value = _state.value.copy(thumbs = _state.value.thumbs + (entry.itemId to thumb))
        }
    }

    fun rename(name: String) {
        viewModelScope.launch {
            repository.updateLibrary(_state.value.libraryId, name = name)
            refresh()
        }
    }

    fun setType(type: LocalLibraryType) {
        viewModelScope.launch {
            repository.updateLibrary(_state.value.libraryId, type = type)
            refresh()
        }
    }

    fun setVisible(visible: Boolean) {
        viewModelScope.launch {
            repository.updateLibrary(_state.value.libraryId, visible = visible)
            refresh()
        }
    }

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch {
            repository.addFolder(_state.value.libraryId, treeUri)
            refresh()
        }
    }

    fun removeFolder(folderId: Long) {
        viewModelScope.launch {
            repository.removeFolder(folderId)
            refresh()
        }
    }

    fun setBrowseMode(folderId: Long, mode: LocalFolderBrowseMode) {
        viewModelScope.launch {
            repository.setFolderBrowseMode(folderId, mode)
            refresh()
        }
    }

    fun rescan() {
        viewModelScope.launch {
            repository.rescan(_state.value.libraryId)
            refresh()
        }
    }

    /** 删除媒体库：只解除关联（源文件保留）。 */
    fun deleteLibrary(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteLibrary(_state.value.libraryId)
            onDeleted()
        }
    }

    /** 播放某个本地曲目：队列 = 同一文件夹的全部本地曲目。 */
    fun playMusic(folderId: Long, itemId: UUID, onStarted: () -> Unit) {
        viewModelScope.launch {
            val started = runCatching {
                playLocalMusicFolder(repository, localMusicPlayer, folderId, itemId)
            }
                .getOrDefault(false)
            if (started) onStarted()
        }
    }

    private fun com.zhangwenkang.cinefin.local.LocalLibraryFolder.toContent(
        library: LocalLibrary,
        entries: List<LocalLibraryEntry>,
    ): FolderContent {
        val mine = entries.filter { it.folderId == id }
        return FolderContent(
            folderId = id,
            name = displayName,
            browseMode = browseMode,
            itemCount = mine.size,
            groups =
                if (library.type == LocalLibraryType.MIXED) {
                    LocalLibraryGrouping.sections(mine, LocalLibraryType.MIXED).map { section ->
                        ContentGroup(
                            section.title,
                            LocalLibraryBrowse.rows(section.entries, browseMode),
                        )
                    }
                } else {
                    listOf(ContentGroup(null, LocalLibraryBrowse.rows(mine, browseMode)))
                },
        )
    }
}

/** W43 本地音乐起播（本地库详情页与媒体库搜索共用）： 队列 = 同一文件夹的全部本地曲目；用 `folderId` 取队列（folderId 全局唯一），搜索结果无需再解析所属库。 */
internal suspend fun playLocalMusicFolder(
    repository: LocalLibraryRepository,
    localMusicPlayer: LocalMusicPlayer,
    folderId: Long,
    itemId: UUID,
): Boolean {
    val entries = repository.allEntries().filter { it.folderId == folderId }
    return localMusicPlayer.play(entries, itemId)
}

/** W43 媒体库搜索浮层的本地音乐起播；起播成功由调用方跳到音乐 Tab（与 W37 详情页一致）。 */
@HiltViewModel
class LocalSearchPlaybackViewModel
@Inject
constructor(
    private val repository: LocalLibraryRepository,
    private val localMusicPlayer: LocalMusicPlayer,
) : ViewModel() {

    fun playMusic(entry: LocalLibraryEntry, onStarted: () -> Unit) {
        viewModelScope.launch {
            val started = runCatching {
                playLocalMusicFolder(
                    repository,
                    localMusicPlayer,
                    entry.folderId,
                    entry.itemId,
                )
            }
                .getOrDefault(false)
            if (started) onStarted()
        }
    }
}
