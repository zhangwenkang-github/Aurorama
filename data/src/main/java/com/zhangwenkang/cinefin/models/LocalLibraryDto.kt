package com.zhangwenkang.cinefin.models

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.util.UUID

/**
 * W37 本地媒体库：一个库 = 名称 + 类型（视频 / 音乐 / 书籍 / 混合）+ 多个文件夹。
 *
 * 索引只读、不拷贝源文件；删除库 / 移除文件夹只解除关联（见 [LocalLibraryFolderDto] 与仓库实现）。
 */
@Entity(tableName = "local_libraries")
data class LocalLibraryDto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** [com.zhangwenkang.cinefin.local.LocalLibraryType] 的 name（只追加，不重排）。 */
    val type: String,
    /** 库级「在媒体库显示」开关：关闭后不在媒体库总览出现，数据与文件夹关联保留。 */
    @ColumnInfo(defaultValue = "1") val visibleInLibrary: Boolean = true,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

/** 本地库的一个 SAF 文件夹（`ACTION_OPEN_DOCUMENT_TREE` + 持久化读权限）。 */
@Entity(tableName = "local_library_folders", indices = [Index("libraryId")])
data class LocalLibraryFolderDto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val libraryId: Long,
    /** 树的持久化 URI（`content://…/tree/…`）。 */
    val treeUri: String,
    val displayName: String,
    /** [com.zhangwenkang.cinefin.local.LocalFolderBrowseMode] 的 name；每个文件夹单独设置。 */
    val browseMode: String,
    @ColumnInfo(defaultValue = "0") val addedAt: Long = 0,
)

/** 本地库索引条目（只读索引，不拷贝源文件）。 */
@Entity(
    tableName = "local_media_items",
    indices =
        [
            Index("libraryId"),
            Index("folderId"),
            Index(value = ["itemId"], unique = true),
        ],
)
data class LocalMediaItemDto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 确定性 UUID = `nameUUIDFromBytes(documentUri)`：播放 / 进度 / 队列的稳定键。 */
    val itemId: UUID,
    val libraryId: Long,
    val folderId: Long,
    /** SAF 文档 URI（打开链路直接复用，不落 sources 表）。 */
    val documentUri: String,
    val documentId: String,
    /** 相对文件夹根的路径（`专辑/曲目.flac`）。 */
    val relativePath: String,
    val name: String,
    /** 小写扩展名。 */
    val extension: String,
    /** [com.zhangwenkang.cinefin.local.LocalMediaKind] 的 name。 */
    val kind: String,
    val sizeBytes: Long,
    val lastModified: Long,
    /** 音乐内嵌标签标题（无标签为 null，显示回退文件名）。 */
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @ColumnInfo(defaultValue = "0") val trackIndex: Int = 0,
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0,
    /** 封面（内嵌标签落盘 / 同目录封面图）的本地路径或 URI。 */
    val coverUri: String? = null,
    @ColumnInfo(defaultValue = "0") val indexedAt: Long = 0,
)

/** 本地库条目数聚合行（库卡「共 N 项 / 视频 x · 音乐 y · 书籍 z」）。 */
data class LocalMediaCountRow(val libraryId: Long, val kind: String, val count: Int)
