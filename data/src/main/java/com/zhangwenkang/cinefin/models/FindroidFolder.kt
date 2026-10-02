package com.zhangwenkang.cinefin.models

import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto

data class FindroidFolder(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String? = null,
    override val overview: String = "",
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean = false,
    override val canDownload: Boolean = false,
    override val sources: List<FindroidSource> = emptyList(),
    override val runtimeTicks: Long = 0L,
    override val playbackPositionTicks: Long = 0L,
    override val unplayedItemCount: Int?,
    override val images: FindroidImages,
    override val chapters: List<FindroidChapter> = emptyList(),
    /**
     * 服务器原始条目类型（BaseItemKind 名称，例如 Book / MusicAlbum / PhotoAlbum）。
     * 极光幕用它决定「点开之后怎么走」：图书交给服务器自带的阅读器，其余按容器继续列子项。
     */
    val kind: String? = null,
) : FindroidItem

fun BaseItemDto.toFindroidFolder(jellyfinRepository: JellyfinRepository): FindroidFolder {
    return FindroidFolder(
        id = id,
        name = name.orEmpty(),
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        unplayedItemCount = userData?.unplayedItemCount,
        images = toFindroidImages(jellyfinRepository),
        kind = type?.name,
    )
}
