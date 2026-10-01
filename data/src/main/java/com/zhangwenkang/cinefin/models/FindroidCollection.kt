package com.zhangwenkang.cinefin.models

import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto

data class FindroidCollection(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String? = null,
    override val overview: String = "",
    override val played: Boolean = false,
    override val favorite: Boolean = false,
    override val canPlay: Boolean = false,
    override val canDownload: Boolean = false,
    override val sources: List<FindroidSource> = emptyList(),
    override val runtimeTicks: Long = 0L,
    override val playbackPositionTicks: Long = 0L,
    override val unplayedItemCount: Int? = null,
    val type: CollectionType,
    override val images: FindroidImages,
    /** 库内条目总数（服务器 `ChildCount`，媒体库卡片展示"共 N 个项目"）；服务器未返回时为 null。 */
    val itemCount: Int? = null,
    override val chapters: List<FindroidChapter> = emptyList(),
) : FindroidItem

fun BaseItemDto.toFindroidCollection(jellyfinRepository: JellyfinRepository): FindroidCollection? {
    val type = CollectionType.fromString(collectionType?.serialName)

    if (type !in CollectionType.supported) {
        return null
    }

    return FindroidCollection(
        id = id,
        name = name.orEmpty(),
        type = type,
        itemCount = childCount,
        images = toFindroidImages(jellyfinRepository),
    )
}
