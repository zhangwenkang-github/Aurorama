package com.zhangwenkang.cinefin.local

import android.net.Uri
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSource
import java.util.UUID

/** 本地媒体库（W37）：库 / 文件夹 / 索引条目 / 播放源合成。 */
data class LocalLibrary(
    val id: Long,
    val name: String,
    val type: LocalLibraryType,
    /** 库级「在媒体库显示」开关。 */
    val visibleInLibrary: Boolean,
    val folders: List<LocalLibraryFolder>,
    val itemCount: Int,
    val countsByKind: Map<LocalMediaKind, Int>,
)

data class LocalLibraryFolder(
    val id: Long,
    val libraryId: Long,
    val treeUri: String,
    val displayName: String,
    val browseMode: LocalFolderBrowseMode,
)

/** 本地可播放条目（视频 / 音乐共用；uri = SAF 文档 URI）。 */
data class LocalPlayableSource(
    val itemId: UUID,
    val title: String,
    val uri: String,
    val kind: LocalMediaKind,
    val durationMs: Long = 0L,
    val coverUri: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackIndex: Int = 0,
)

/**
 * 本地媒体库仓库。
 *
 * 约定：
 * - 建立 / 移除都只动索引：**不删除、不移动、不拷贝源文件**；
 * - 索引只读源目录（SAF 持久化权限）；
 * - [syntheticMovie] / [playable] 给播放链路提供「非 sources 表」的本地媒体源， 避免下载管理把用户文件当成已下载媒体。
 */
interface LocalLibraryRepository {
    suspend fun libraries(includeHidden: Boolean = true): List<LocalLibrary>

    suspend fun library(id: Long): LocalLibrary?

    suspend fun createLibrary(name: String, type: LocalLibraryType): Long

    suspend fun updateLibrary(
        id: Long,
        name: String? = null,
        type: LocalLibraryType? = null,
        visible: Boolean? = null,
    )

    /** 删除库（含文件夹关联 / 索引条目），**不删除源文件**。 */
    suspend fun deleteLibrary(id: Long)

    /** 添加文件夹并立刻扫描；返回 null 表示 URI 不可用（权限 / 查询失败）。 */
    suspend fun addFolder(
        libraryId: Long,
        treeUri: Uri,
        browseMode: LocalFolderBrowseMode = LocalFolderBrowseMode.HIERARCHY,
    ): LocalLibraryFolder?

    /** 移除文件夹（连同索引条目），**不删除源文件**。 */
    suspend fun removeFolder(folderId: Long)

    suspend fun setFolderBrowseMode(folderId: Long, mode: LocalFolderBrowseMode)

    /** 重新扫描整个库（全部文件夹），返回索引条目数。 */
    suspend fun rescan(libraryId: Long): Int

    suspend fun entries(libraryId: Long): List<LocalLibraryEntry>

    /** 全部本地库条目（音乐线并入曲库用；不含「在媒体库显示」过滤）。 */
    suspend fun allEntries(): List<LocalLibraryEntry>

    suspend fun entry(itemId: UUID): LocalLibraryEntry?

    /** 播放链路用：本地条目 → 可直接播放的源（视频 / 音乐）。 */
    suspend fun playable(itemId: UUID): LocalPlayableSource?

    /**
     * 视频播放链路适配：本地视频合成 [FindroidMovie]（sources 里带 LOCAL `content://` 源）。
     *
     * 只对索引里存在的 itemId 生效；其余返回 null，调用方继续走服务器 / 下载链路。
     */
    suspend fun syntheticMovie(itemId: UUID): FindroidMovie?

    /** 视频播放链路适配：本地视频合成 LOCAL 源。 */
    suspend fun syntheticSources(itemId: UUID): List<FindroidSource>?

    /** SAF 树 URI 的显示名（建立库时展示文件夹名）。 */
    suspend fun treeDisplayName(treeUri: Uri): String?

    /** 播放上报门控：本地库条目的进度不写服务器（服务器没有这个 itemId）。 */
    suspend fun isLocalItem(itemId: UUID): Boolean
}
