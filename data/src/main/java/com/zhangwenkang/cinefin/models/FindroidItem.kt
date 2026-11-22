package com.zhangwenkang.cinefin.models

import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

interface FindroidItem {
    val id: UUID
    val name: String
    val originalTitle: String?
    val overview: String
    val played: Boolean
    val favorite: Boolean
    val canPlay: Boolean
    val canDownload: Boolean
    val sources: List<FindroidSource>
    val runtimeTicks: Long
    val playbackPositionTicks: Long
    /**
     * W60：服务器 `UserData.PlayedPercentage`（0–100）。
     *
     * 书籍 / 音频等没有 `runtimeTicks`（或还没有时长）的续读 / 续听条目用它换算进度条； 有 `runtimeTicks` 的条目仍以 `playbackPosition
     * / runtime` 为准。默认 null = 没有进度数据。
     */
    val playedPercentage: Double?
        get() = null

    /**
     * W6-T10（READER-39）：作者 / 创作者名（书籍条目才可能有）。
     *
     * 来源 = Jellyfin `People` 里 `PersonKind.AUTHOR` 的条目；只有书籍查询会请求该字段（见 `getItems` 的 fields），
     * 其余类型保持空列表，既有卡片不吃额外网络开销。
     */
    val authors: List<String>
        get() = emptyList()

    val unplayedItemCount: Int?
    val images: FindroidImages
    val chapters: List<FindroidChapter>

    /**
     * W8-T3：服务器 `UserData.Rating`（个人评分，0–10，单位「分」；7.5 = 3.75 星）。
     *
     * 只有电影 / 单集详情读取并展示它；其他条目类型保持 null（默认值，零改动）。
     */
    val personalRating: Double?
        get() = null
}

suspend fun BaseItemDto.toFindroidItem(
    jellyfinRepository: JellyfinRepository,
    serverDatabase: ServerDatabaseDao? = null,
): FindroidItem? {
    return when (type) {
        BaseItemKind.MOVIE -> toFindroidMovie(jellyfinRepository, serverDatabase)
        BaseItemKind.EPISODE -> toFindroidEpisode(jellyfinRepository)
        BaseItemKind.SEASON -> toFindroidSeason(jellyfinRepository)
        BaseItemKind.SERIES -> toFindroidShow(jellyfinRepository)
        BaseItemKind.BOX_SET -> toFindroidBoxSet(jellyfinRepository)
        BaseItemKind.FOLDER -> toFindroidFolder(jellyfinRepository)
        // 极光幕：非影视库也要能用——专辑 / 歌单 / 图书 / 照片按「容器」处理，可以继续往里点
        BaseItemKind.MUSIC_ALBUM,
        BaseItemKind.MUSIC_ARTIST,
        BaseItemKind.PLAYLIST,
        BaseItemKind.BOOK,
        BaseItemKind.PHOTO,
        BaseItemKind.PHOTO_ALBUM -> toFindroidFolder(jellyfinRepository)
        // 音频与家庭视频本身就是可播放条目，直接走既有播放管线
        BaseItemKind.AUDIO,
        BaseItemKind.VIDEO -> toFindroidMovie(jellyfinRepository, serverDatabase)
        else -> null
    }
}

fun FindroidItem.isDownloading(): Boolean {
    return sources
        .filter { it.type == FindroidSourceType.LOCAL }
        .any { it.path.endsWith(".download") }
}

fun FindroidItem.isDownloaded(): Boolean {
    return sources
        .filter { it.type == FindroidSourceType.LOCAL }
        .any { !it.path.endsWith(".download") }
}

/**
 * W77-5：服务器侧原始文件路径（只有书籍等「容器」条目映射，见 [FindroidFolder.sourcePath]）。
 *
 * 书架 / 首页卡片用它零请求判断远端 PDF（`BookCoverRules.isPdfPath`），避免 PdfBox 整本 Range 解析封面。
 */
val FindroidItem.bookSourcePath: String?
    get() = (this as? FindroidFolder)?.sourcePath
