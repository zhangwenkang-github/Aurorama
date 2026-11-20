package com.zhangwenkang.cinefin.local

import java.util.UUID

/** 本地库类型：混合库按文件类型自动分组（视频 / 音乐 / 书籍）。 */
enum class LocalLibraryType(val label: String) {
    VIDEO("视频"),
    MUSIC("音乐"),
    BOOK("书籍"),
    MIXED("混合");

    companion object {
        fun fromName(raw: String?): LocalLibraryType =
            entries.firstOrNull { it.name == raw } ?: MIXED
    }
}

/** 文件类型域（混合库的分组维度）。 */
enum class LocalMediaKind(val label: String) {
    VIDEO("视频"),
    MUSIC("音乐"),
    BOOK("书籍");

    companion object {
        fun fromName(raw: String?): LocalMediaKind? = entries.firstOrNull { it.name == raw }
    }
}

/** 每个文件夹单独设置浏览模式。 */
enum class LocalFolderBrowseMode(val label: String, val shortLabel: String) {
    /** 按文件夹层级显示：文件夹行 + 直属文件，逐层展开（默认）。 */
    HIERARCHY("按文件夹层级显示", "层级"),

    /** 文件夹及子文件夹所有文件平铺显示。 */
    FLAT("文件夹及子文件夹所有文件平铺显示", "平铺");

    companion object {
        fun fromName(raw: String?): LocalFolderBrowseMode =
            entries.firstOrNull { it.name == raw } ?: HIERARCHY
    }
}

/** 扩展名白名单（W37 需求）：不在表内的文件不索引。 */
object LocalMediaExtensions {
    val VIDEO = setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m2ts")

    /**
     * 音乐扩展名（MUSIC-36）：mp3 / aac / flac / alac（m4a 容器）/ wav / ogg / opus + APE / DSD。
     *
     * APE 与 DSD（`.dsf` / `.dff`）只做识别与入库，播放引擎（ExoPlayer + FFmpeg 扩展）解不了， 由 [isEnginePlayable]
     * 给出「引擎不支持」判定供 UI 提示。
     */
    val MUSIC = setOf("mp3", "flac", "m4a", "aac", "opus", "ogg", "wav", "ape", "dsf", "dff")

    val BOOK = setOf("pdf", "epub", "cbz")

    /**
     * 引擎解不了的音乐扩展名（MUSIC-36）。
     *
     * 只列**确认**解不了的格式：APE（Monkey's Audio）与 DSD（.dsf / .dff）均为 FFmpeg 扩展与 ExoPlayer 原生皆不支持的编码；
     * ALAC（m4a）、FLAC、Opus 等由 ExoPlayer / FFmpeg 扩展覆盖，不在此表。
     */
    val UNSUPPORTED_MUSIC = setOf("ape", "dsf", "dff")

    /** 小写扩展名；无扩展名返回空串。 */
    fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    /** 文件名 → 媒体类型；白名单外返回 null。 */
    fun kindOf(fileName: String): LocalMediaKind? =
        when (extensionOf(fileName)) {
            in VIDEO -> LocalMediaKind.VIDEO
            in MUSIC -> LocalMediaKind.MUSIC
            in BOOK -> LocalMediaKind.BOOK
            else -> null
        }

    /**
     * 播放引擎是否支持该文件（MUSIC-36 的「明确提示，不静默失败」判定源）。
     *
     * 非音乐文件恒 true（视频 / 书籍走各自链路）；音乐按 [UNSUPPORTED_MUSIC] 判定。
     */
    fun isEnginePlayable(fileName: String): Boolean = extensionOf(fileName) !in UNSUPPORTED_MUSIC

    /** 扩展名 → 归一化的编码名（`LocalLibraryEntry.codec` / 本地 TrackSource 用）；未识别返回 null。 */
    fun musicCodecOf(fileName: String): String? {
        val extension = extensionOf(fileName)
        if (extension !in MUSIC) return null
        return extension
    }

    /** 该类型库是否收录这种媒体（混合库全收）。 */
    fun allowedIn(type: LocalLibraryType, kind: LocalMediaKind): Boolean =
        when (type) {
            LocalLibraryType.VIDEO -> kind == LocalMediaKind.VIDEO
            LocalLibraryType.MUSIC -> kind == LocalMediaKind.MUSIC
            LocalLibraryType.BOOK -> kind == LocalMediaKind.BOOK
            LocalLibraryType.MIXED -> true
        }

    /** 封面候选图（同目录封面策略用）。 */
    val COVER_NAMES =
        setOf(
            "cover.jpg",
            "cover.jpeg",
            "cover.png",
            "cover.webp",
            "folder.jpg",
            "folder.jpeg",
            "folder.png",
            "poster.jpg",
            "poster.png",
            "album.jpg",
            "album.png",
        )

    fun isCoverImage(fileName: String): Boolean = fileName.lowercase() in COVER_NAMES
}

/**
 * 本地条目的确定性 itemId：播放队列 / 进度 / 上报都按它寻址，重启后不变。
 *
 * 用文档 URI 而不是自增主键：即使库被删除重建，只要文件还在原路径，键就保持一致。
 */
fun localItemIdFor(documentUri: String): UUID =
    UUID.nameUUIDFromBytes(documentUri.toByteArray(Charsets.UTF_8))

/** 本地库索引条目（UI 与播放链路共用的扁平行）。 */
data class LocalLibraryEntry(
    val itemId: UUID,
    val folderId: Long,
    val name: String,
    val kind: LocalMediaKind,
    val documentUri: String,
    val relativePath: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val durationMs: Long = 0L,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackIndex: Int = 0,
    val coverUri: String? = null,
    // MUSIC-35：首次入库时读到的完整标签（缺失为 null，播放链路用占位 fallback）
    val albumArtist: String? = null,
    val discNumber: Int? = null,
    val genre: String? = null,
    val year: Int? = null,
    /** 源码率（bit/s）；retriever 取不到为 null。 */
    val bitrate: Int? = null,
    /** 采样率（Hz）；Android 12 以下取不到（retriever 无对应键）。 */
    val sampleRate: Int? = null,
    /** 位深（bit）；Android 12 以下取不到。 */
    val bitDepth: Int? = null,
) {
    /** 显示名：音乐优先标签标题，其余用文件名。 */
    val displayName: String
        get() = title?.takeIf { it.isNotBlank() } ?: name

    /** 所在文件夹的相对路径（根目录为空串）。 */
    val parentPath: String
        get() = relativePath.substringBeforeLast('/', "")

    /** MUSIC-36：播放引擎是否支持该文件（APE / DSD 明确提示，不静默失败）。 */
    val isEnginePlayable: Boolean
        get() = LocalMediaExtensions.isEnginePlayable(name)

    /** 归一化音乐编码（扩展名）；非音乐条目为 null。 */
    val musicCodec: String?
        get() = LocalMediaExtensions.musicCodecOf(name)
}

/** 混合库按类型分组后的一段；单一类型库只有一段（[kind] = null）。 */
data class LocalLibrarySection(
    val kind: LocalMediaKind?,
    val title: String,
    val entries: List<LocalLibraryEntry>,
)

/** 本地库分组（纯函数）：混合库按文件类型分组，其余库单段。 */
object LocalLibraryGrouping {
    private val ENTRY_ORDER =
        compareBy<LocalLibraryEntry>({ it.relativePath.lowercase() }, { it.name.lowercase() })

    fun sections(
        entries: List<LocalLibraryEntry>,
        type: LocalLibraryType,
    ): List<LocalLibrarySection> =
        if (type == LocalLibraryType.MIXED) {
            LocalMediaKind.entries.mapNotNull { kind ->
                val group = entries.filter { it.kind == kind }.sortedWith(ENTRY_ORDER)
                if (group.isEmpty()) null else LocalLibrarySection(kind, kind.label, group)
            }
        } else {
            val ordered = entries.sortedWith(ENTRY_ORDER)
            if (ordered.isEmpty()) emptyList()
            else listOf(LocalLibrarySection(null, type.label, ordered))
        }
}

/** 本地库浏览行（层级 / 平铺两种模式，纯函数）。 */
object LocalLibraryBrowse {
    sealed interface Row {
        val depth: Int

        data class Folder(
            val path: String,
            val title: String,
            override val depth: Int,
            val fileCount: Int,
        ) : Row

        data class Entry(val entry: LocalLibraryEntry, override val depth: Int) : Row
    }

    private val ENTRY_ORDER =
        compareBy<LocalLibraryEntry>({ it.relativePath.lowercase() }, { it.name.lowercase() })

    fun rows(entries: List<LocalLibraryEntry>, mode: LocalFolderBrowseMode): List<Row> =
        when (mode) {
            LocalFolderBrowseMode.FLAT -> entries.sortedWith(ENTRY_ORDER).map { Row.Entry(it, 0) }
            LocalFolderBrowseMode.HIERARCHY -> hierarchyRows(entries)
        }

    /**
     * 层级模式：先列根目录直属文件，再按路径顺序为每个文件夹输出「文件夹行 + 直属文件行」。
     *
     * 文件夹行只在存在子树时出现（避免空行）；[Row.Folder.fileCount] = 该文件夹下全部后代文件数。
     */
    private fun hierarchyRows(entries: List<LocalLibraryEntry>): List<Row> {
        val folders = sortedSetOf<String>()
        for (entry in entries) {
            val parent = entry.parentPath
            if (parent.isEmpty()) continue
            var path = ""
            for (segment in parent.split('/')) {
                path = if (path.isEmpty()) segment else "$path/$segment"
                folders += path
            }
        }
        val rows = mutableListOf<Row>()
        rows +=
            entries
                .filter { it.parentPath.isEmpty() }
                .sortedWith(ENTRY_ORDER)
                .map { Row.Entry(it, 0) }
        for (folder in folders) {
            val depth = folder.count { it == '/' }
            rows +=
                Row.Folder(
                    path = folder,
                    title = folder.substringAfterLast('/'),
                    depth = depth,
                    fileCount = entries.count { it.relativePath.startsWith("$folder/") },
                )
            rows +=
                entries
                    .filter { it.parentPath == folder }
                    .sortedWith(ENTRY_ORDER)
                    .map { Row.Entry(it, depth + 1) }
        }
        return rows
    }
}
