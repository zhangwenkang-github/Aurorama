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
    val unplayedItemCount: Int?
    val images: FindroidImages
    val chapters: List<FindroidChapter>
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
        // 影阁：非影视库也要能用——专辑 / 歌单 / 图书 / 照片按「容器」处理，可以继续往里点
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
