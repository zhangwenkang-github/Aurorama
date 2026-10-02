package com.zhangwenkang.cinefin.local

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.LocalLibraryDto
import com.zhangwenkang.cinefin.models.LocalLibraryFolderDto
import com.zhangwenkang.cinefin.models.LocalMediaCountRow
import com.zhangwenkang.cinefin.models.LocalMediaItemDto
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 本地媒体库仓库实现（W37）。
 *
 * 扫描用框架 [DocumentsContract]（不引入 androidx.documentfile 依赖）：递归子文档、按扩展名白名单收录； 音乐尝试读
 * [MediaMetadataRetriever] 内嵌标签（标题 / 艺人 / 专辑 / 时长 / 内嵌封面），失败回退文件名。 索引条目只保存 URI 与元数据，**不拷贝源文件**。
 */
class LocalLibraryRepositoryImpl(
    private val context: Context,
    private val database: ServerDatabaseDao,
) : LocalLibraryRepository {

    private val resolver: ContentResolver
        get() = context.contentResolver

    override suspend fun libraries(includeHidden: Boolean): List<LocalLibrary> =
        withContext(Dispatchers.IO) {
            val counts = runCatching {
                database.getLocalMediaCounts()
            }
                .getOrElse { emptyList() }
                .groupBy { it.libraryId }
            val result = mutableListOf<LocalLibrary>()
            for (dto in database.getLocalLibraries()) {
                if (!includeHidden && !dto.visibleInLibrary) continue
                result += dto.toLibrary(counts[dto.id].orEmpty())
            }
            result
        }

    override suspend fun library(id: Long): LocalLibrary? =
        withContext(Dispatchers.IO) {
            val dto = database.getLocalLibrary(id) ?: return@withContext null
            dto.toLibrary(database.getLocalMediaCounts().filter { it.libraryId == id })
        }

    override suspend fun createLibrary(name: String, type: LocalLibraryType): Long =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            database.insertLocalLibrary(
                LocalLibraryDto(
                    name = name.trim().ifBlank { "本地媒体库" },
                    type = type.name,
                    visibleInLibrary = true,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }

    override suspend fun updateLibrary(
        id: Long,
        name: String?,
        type: LocalLibraryType?,
        visible: Boolean?,
    ) =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            name?.let { database.updateLocalLibraryName(id, it.trim().ifBlank { "本地媒体库" }, now) }
            type?.let { newType ->
                database.updateLocalLibraryType(id, newType.name, now)
                // 类型变化后按新类型重扫：切到单类型库时，其他类型的条目要从索引移除。
                rescan(id)
            }
            visible?.let { database.updateLocalLibraryVisible(id, it, now) }
            Unit
        }

    override suspend fun deleteLibrary(id: Long) =
        withContext(Dispatchers.IO) {
            // 只解除关联：删除索引与文件夹关联，源文件不受影响。
            database.deleteLocalLibraryItems(id)
            database.deleteLocalLibraryFolders(id)
            database.deleteLocalLibrary(id)
        }

    override suspend fun addFolder(
        libraryId: Long,
        treeUri: Uri,
        browseMode: LocalFolderBrowseMode,
    ): LocalLibraryFolder? =
        withContext(Dispatchers.IO) {
            runCatching {
                resolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
                .onFailure { Timber.w(it, "SAF 持久化权限申请失败：$treeUri") }
            val displayName = treeDisplayName(treeUri) ?: treeUri.lastPathSegment.orEmpty()
            val folderId =
                database.insertLocalLibraryFolder(
                    LocalLibraryFolderDto(
                        libraryId = libraryId,
                        treeUri = treeUri.toString(),
                        displayName = displayName,
                        browseMode = browseMode.name,
                        addedAt = System.currentTimeMillis(),
                    )
                )
            val folder = database.getLocalLibraryFolder(folderId) ?: return@withContext null
            val type =
                database.getLocalLibrary(libraryId)?.let { LocalLibraryType.fromName(it.type) }
                    ?: LocalLibraryType.MIXED
            runCatching { scanFolder(folder, type) }
                .onFailure { Timber.w(it, "扫描本地文件夹失败：${folder.treeUri}") }
            folder.toFolder()
        }

    override suspend fun removeFolder(folderId: Long) =
        withContext(Dispatchers.IO) {
            // 只解除关联：删除该文件夹的索引条目与关联行，源文件不动。
            database.clearLocalMediaByFolder(folderId)
            database.deleteLocalLibraryFolder(folderId)
        }

    override suspend fun setFolderBrowseMode(
        folderId: Long,
        mode: LocalFolderBrowseMode,
    ) = withContext(Dispatchers.IO) { database.updateLocalFolderBrowseMode(folderId, mode.name) }

    override suspend fun rescan(libraryId: Long): Int =
        withContext(Dispatchers.IO) {
            val library = database.getLocalLibrary(libraryId) ?: return@withContext 0
            val type = LocalLibraryType.fromName(library.type)
            database.getLocalLibraryFolders(libraryId).forEach { folder ->
                runCatching {
                    // 类型切换后需要按新类型过滤：先清空再按新类型重扫。
                    database.clearLocalMediaByFolder(folder.id)
                    scanFolder(folder, type)
                }
                    .onFailure { Timber.w(it, "重新扫描失败：${folder.treeUri}") }
            }
            database.getLocalMediaItems(libraryId).size
        }

    override suspend fun entries(libraryId: Long): List<LocalLibraryEntry> =
        withContext(Dispatchers.IO) { database.getLocalMediaItems(libraryId).map { it.toEntry() } }

    override suspend fun allEntries(): List<LocalLibraryEntry> =
        withContext(Dispatchers.IO) { database.getAllLocalMediaItems().map { it.toEntry() } }

    override suspend fun search(query: String): List<LocalSearchHit> =
        withContext(Dispatchers.IO) {
            if (LocalLibrarySearch.normalize(query).isEmpty()) return@withContext emptyList()
            val dtos = runCatching {
                database.getAllLocalMediaItems()
            }
                .getOrDefault(emptyList())
            val entries = dtos.map { it.toEntry() }
            if (entries.isEmpty()) return@withContext emptyList()
            // LocalLibraryEntry 不带 libraryId（W37 模型），按 itemId 回连 DTO 的归属库。
            val libraryIdByItem = dtos.associate { it.itemId to it.libraryId }
            val libraryNames = runCatching {
                database.getLocalLibraries()
            }
                .getOrDefault(emptyList())
                .associate { it.id to it.name }
            val folders = runCatching {
                database.getAllLocalLibraryFolders()
            }
                .getOrDefault(emptyList())
                .associateBy { it.id }
            LocalLibrarySearch.filter(entries, query).map { entry ->
                val folder = folders[entry.folderId]
                val libraryName = libraryIdByItem[entry.itemId]?.let(libraryNames::get)
                val label =
                    when {
                        folder == null -> libraryName
                        libraryName.isNullOrBlank() -> folder.displayName
                        else -> "$libraryName · ${folder.displayName}"
                    }
                LocalSearchHit(entry = entry, folderLabel = label?.takeIf { it.isNotBlank() })
            }
        }

    override suspend fun entry(itemId: UUID): LocalLibraryEntry? =
        withContext(Dispatchers.IO) { database.getLocalMediaItem(itemId)?.toEntry() }

    override suspend fun playable(itemId: UUID): LocalPlayableSource? =
        withContext(Dispatchers.IO) {
            val dto = database.getLocalMediaItem(itemId) ?: return@withContext null
            val kind = LocalMediaKind.fromName(dto.kind) ?: return@withContext null
            LocalPlayableSource(
                itemId = itemId,
                title = dto.title?.takeIf { it.isNotBlank() } ?: dto.name.substringBeforeLast('.'),
                uri = dto.documentUri,
                kind = kind,
                durationMs = dto.durationMs,
                coverUri = dto.coverUri,
                artist = dto.artist,
                album = dto.album,
                trackIndex = dto.trackIndex,
            )
        }

    override suspend fun syntheticMovie(itemId: UUID): FindroidMovie? =
        withContext(Dispatchers.IO) {
            val source = playable(itemId) ?: return@withContext null
            if (source.kind != LocalMediaKind.VIDEO) return@withContext null
            source.toFindroidMovie()
        }

    override suspend fun syntheticSources(itemId: UUID): List<FindroidSource>? =
        withContext(Dispatchers.IO) {
            val source = playable(itemId) ?: return@withContext null
            if (source.kind != LocalMediaKind.VIDEO) return@withContext null
            listOf(source.toFindroidSource())
        }

    override suspend fun treeDisplayName(treeUri: Uri): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val documentId = DocumentsContract.getTreeDocumentId(treeUri)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                resolver
                    .query(
                        documentUri,
                        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }
                .getOrNull()
        }

    override suspend fun isLocalItem(itemId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { database.getLocalMediaItem(itemId) != null }.getOrDefault(false)
        }

    // ---------------------------------------------------------------- 扫描

    private suspend fun scanFolder(
        folder: LocalLibraryFolderDto,
        type: LocalLibraryType,
    ) {
        val treeUri = Uri.parse(folder.treeUri)
        val rootDocumentId =
            runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val scanned = mutableListOf<LocalMediaItemDto>()
        walk(treeUri, rootDocumentId, "", type, folder, scanned, now, depth = 0)
        database.clearLocalMediaByFolder(folder.id)
        database.insertLocalMediaItems(scanned)
        Timber.d("W37 扫描文件夹 %s：索引 %d 条", folder.displayName, scanned.size)
    }

    private fun walk(
        treeUri: Uri,
        documentId: String,
        relativePath: String,
        type: LocalLibraryType,
        folder: LocalLibraryFolderDto,
        out: MutableList<LocalMediaItemDto>,
        now: Long,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        val children = queryChildren(treeUri, documentId) ?: return
        val coverUri =
            children
                .firstOrNull { child ->
                    !child.isDirectory && LocalMediaExtensions.isCoverImage(child.name)
                }
                ?.let { child ->
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId)
                        .toString()
                }
        for (child in children) {
            val childPath =
                if (relativePath.isEmpty()) child.name else "$relativePath/${child.name}"
            if (child.isDirectory) {
                walk(treeUri, child.documentId, childPath, type, folder, out, now, depth + 1)
                continue
            }
            val kind = LocalMediaExtensions.kindOf(child.name) ?: continue
            if (!LocalMediaExtensions.allowedIn(type, kind)) continue
            val documentUri =
                DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId).toString()
            val itemId = localItemIdFor(documentUri)
            val music =
                if (kind == LocalMediaKind.MUSIC) readMusicMetadata(documentUri, itemId) else null
            out +=
                LocalMediaItemDto(
                    itemId = itemId,
                    libraryId = folder.libraryId,
                    folderId = folder.id,
                    documentUri = documentUri,
                    documentId = child.documentId,
                    relativePath = childPath,
                    name = child.name,
                    extension = LocalMediaExtensions.extensionOf(child.name),
                    kind = kind.name,
                    sizeBytes = child.size,
                    lastModified = child.lastModified,
                    title = music?.title,
                    artist = music?.artist,
                    album = music?.album,
                    trackIndex = music?.trackIndex ?: 0,
                    durationMs = music?.durationMs ?: 0L,
                    coverUri = music?.coverUri ?: coverUri,
                    indexedAt = now,
                )
        }
    }

    private fun queryChildren(treeUri: Uri, documentId: String): List<DocumentChild>? =
        runCatching {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            resolver
                .query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(0) ?: continue
                            val name = cursor.getString(1) ?: continue
                            val mime = cursor.getString(2)
                            add(
                                DocumentChild(
                                    documentId = id,
                                    name = name,
                                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                                    size = cursor.getLong(3),
                                    lastModified = cursor.getLong(4),
                                )
                            )
                        }
                    }
                }
        }
        .onFailure { Timber.w(it, "查询 SAF 目录失败：$documentId") }
        .getOrNull()

    /** 音乐内嵌标签 + 内嵌封面（失败全部回退 null / 文件名）。 */
    private fun readMusicMetadata(documentUri: String, itemId: UUID): MusicMetadata? {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(context, Uri.parse(documentUri))
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val track =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')
                    ?.trim()
                    ?.toIntOrNull() ?: 0
            val duration =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            MusicMetadata(
                title = title?.takeIf { it.isNotBlank() },
                artist = artist?.takeIf { it.isNotBlank() },
                album = album?.takeIf { it.isNotBlank() },
                trackIndex = track,
                durationMs = duration,
                coverUri = writeEmbeddedCover(itemId, retriever.embeddedPicture),
            )
        }
            .getOrNull()
            .also { retriever.release() }
    }

    /** 内嵌封面落 `filesDir/local_covers/<itemId>.jpg`（索引只读源文件；封面是派生产物）。 */
    private fun writeEmbeddedCover(itemId: UUID, picture: ByteArray?): String? {
        if (picture == null || picture.isEmpty()) return null
        return runCatching {
            val dir = File(context.filesDir, "local_covers").apply { mkdirs() }
            val file = File(dir, "$itemId.jpg")
            FileOutputStream(file).use { it.write(picture) }
            file.absolutePath
        }
            .getOrNull()
    }

    private suspend fun LocalLibraryDto.toLibrary(counts: List<LocalMediaCountRow>): LocalLibrary {
        val folders = database.getLocalLibraryFolders(id)
        val byKind =
            counts
                .mapNotNull { row ->
                    LocalMediaKind.fromName(row.kind)?.let { kind -> kind to row.count }
                }
                .toMap()
        return LocalLibrary(
            id = id,
            name = name,
            type = LocalLibraryType.fromName(type),
            visibleInLibrary = visibleInLibrary,
            folders = folders.map { it.toFolder() },
            itemCount = byKind.values.sum(),
            countsByKind = byKind,
        )
    }

    private fun LocalLibraryFolderDto.toFolder(): LocalLibraryFolder =
        LocalLibraryFolder(
            id = id,
            libraryId = libraryId,
            treeUri = treeUri,
            displayName = displayName,
            browseMode = LocalFolderBrowseMode.fromName(browseMode),
        )

    private fun LocalMediaItemDto.toEntry(): LocalLibraryEntry =
        LocalLibraryEntry(
            itemId = itemId,
            folderId = folderId,
            name = name,
            kind = LocalMediaKind.fromName(kind) ?: LocalMediaKind.VIDEO,
            documentUri = documentUri,
            relativePath = relativePath,
            sizeBytes = sizeBytes,
            lastModified = lastModified,
            durationMs = durationMs,
            title = title,
            artist = artist,
            album = album,
            trackIndex = trackIndex,
            coverUri = coverUri,
        )

    private fun LocalPlayableSource.toFindroidSource(): FindroidSource =
        FindroidSource(
            id = "local-${itemId}",
            name = "本地文件",
            type = FindroidSourceType.LOCAL,
            path = uri,
            size = 0L,
            mediaStreams = emptyList(),
            downloadId = null,
            transcodingPath = null,
        )

    private fun LocalPlayableSource.toFindroidMovie(): FindroidMovie =
        FindroidMovie(
            id = itemId,
            name = title,
            originalTitle = null,
            overview = "",
            sources = listOf(toFindroidSource()),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = durationMs * TICKS_PER_MS,
            playbackPositionTicks = 0L,
            premiereDate = null,
            people = emptyList(),
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            status = "",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = FindroidImages(primary = coverUri?.let { Uri.parse(it) }),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private data class DocumentChild(
        val documentId: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
    )

    private data class MusicMetadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val trackIndex: Int,
        val durationMs: Long,
        val coverUri: String?,
    )

    private companion object {
        const val MAX_DEPTH = 16
        const val TICKS_PER_MS = 10_000L
    }
}
