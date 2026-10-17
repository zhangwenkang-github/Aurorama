package com.zhangwenkang.cinefin.models

import android.net.Uri
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.utils.ImageCacheRules
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.PersonKind

data class FindroidItemPersonImage(val uri: Uri?, val blurHash: String?)

fun BaseItemPerson.toFindroidImage(repository: JellyfinRepository): FindroidItemPersonImage {
    val baseUrl = repository.getBaseUrl()
    return FindroidItemPersonImage(
        uri =
            ImageCacheRules.itemImageUrl(
                    baseUrl = baseUrl,
                    itemId = id,
                    variant = ImageCacheRules.ArtworkVariant.PRIMARY,
                    tag = primaryImageTag,
                    pathCase = ImageCacheRules.ItemPathCase.LOWER,
                )
                ?.let(Uri::parse),
        blurHash = imageBlurHashes?.get(ImageType.PRIMARY)?.get(primaryImageTag),
    )
}

data class FindroidItemPerson(
    val id: UUID,
    val name: String,
    val type: PersonKind,
    val role: String,
    val image: FindroidItemPersonImage,
)

fun BaseItemPerson.toFindroidPerson(repository: JellyfinRepository): FindroidItemPerson {
    return FindroidItemPerson(
        id = id,
        name = name.orEmpty(),
        type = type,
        role = role.orEmpty(),
        image = toFindroidImage(repository),
    )
}
