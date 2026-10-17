package com.zhangwenkang.cinefin.models

import android.net.Uri
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.utils.ImageCacheRules
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType

data class FindroidImages(
    val primary: Uri? = null,
    val backdrop: Uri? = null,
    val logo: Uri? = null,
    val showPrimary: Uri? = null,
    val showBackdrop: Uri? = null,
    val showLogo: Uri? = null,
)

fun BaseItemDto.toFindroidImages(jellyfinRepository: JellyfinRepository): FindroidImages {
    val baseUrl = jellyfinRepository.getBaseUrl()
    fun imageUrl(itemId: UUID, variant: ImageCacheRules.ArtworkVariant, tag: String?): Uri? =
        ImageCacheRules.itemImageUrl(
                baseUrl = baseUrl,
                itemId = itemId,
                variant = variant,
                tag = tag,
                index = if (variant == ImageCacheRules.ArtworkVariant.BACKDROP) 0 else null,
                pathCase = ImageCacheRules.ItemPathCase.LOWER,
            )
            ?.let(Uri::parse)

    val primary =
        imageUrl(id, ImageCacheRules.ArtworkVariant.PRIMARY, imageTags?.get(ImageType.PRIMARY))
    val backdrop =
        imageUrl(id, ImageCacheRules.ArtworkVariant.BACKDROP, backdropImageTags?.firstOrNull())
    val logo = imageUrl(id, ImageCacheRules.ArtworkVariant.LOGO, imageTags?.get(ImageType.LOGO))
    val seriesItemId = seriesId
    val showPrimary = seriesItemId?.let {
        imageUrl(it, ImageCacheRules.ArtworkVariant.PRIMARY, seriesPrimaryImageTag)
    }
    val showBackdrop = seriesItemId?.let {
        imageUrl(it, ImageCacheRules.ArtworkVariant.BACKDROP, seriesPrimaryImageTag)
    }
    val showLogo = seriesItemId?.let {
        imageUrl(it, ImageCacheRules.ArtworkVariant.LOGO, seriesPrimaryImageTag)
    }

    return FindroidImages(
        primary = primary,
        backdrop = backdrop,
        logo = logo,
        showPrimary = showPrimary,
        showBackdrop = showBackdrop,
        showLogo = showLogo,
    )
}

fun FindroidMovieDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary =
            Uri.Builder()
                .appendEncodedPath(
                    ImageCacheRules.localImagePath(itemId, ImageCacheRules.ArtworkVariant.PRIMARY)
                )
                .build(),
        backdrop =
            Uri.Builder()
                .appendEncodedPath(
                    ImageCacheRules.localImagePath(itemId, ImageCacheRules.ArtworkVariant.BACKDROP)
                )
                .build(),
    )
}

fun FindroidShowDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary =
            Uri.Builder()
                .appendEncodedPath(
                    ImageCacheRules.localImagePath(itemId, ImageCacheRules.ArtworkVariant.PRIMARY)
                )
                .build(),
        backdrop =
            Uri.Builder()
                .appendEncodedPath(
                    ImageCacheRules.localImagePath(itemId, ImageCacheRules.ArtworkVariant.BACKDROP)
                )
                .build(),
    )
}

fun FindroidSeasonDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary =
            Uri.Builder()
                .appendEncodedPath(
                    ImageCacheRules.localImagePath(itemId, ImageCacheRules.ArtworkVariant.PRIMARY)
                )
                .build(),
        backdrop =
            Uri.Builder()
                .appendEncodedPath(
                    ImageCacheRules.localImagePath(itemId, ImageCacheRules.ArtworkVariant.BACKDROP)
                )
                .build(),
        showPrimary =
            Uri.Builder()
                .appendEncodedPath(
                    ImageCacheRules.localImagePath(
                        seriesId,
                        ImageCacheRules.ArtworkVariant.PRIMARY,
                    )
                )
                .build(),
        showBackdrop =
            Uri.Builder()
                .appendEncodedPath(
                    ImageCacheRules.localImagePath(
                        seriesId,
                        ImageCacheRules.ArtworkVariant.BACKDROP,
                    )
                )
                .build(),
    )
}

fun FindroidEpisodeDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary =
            Uri.Builder()
                .appendEncodedPath(
                    ImageCacheRules.localImagePath(itemId, ImageCacheRules.ArtworkVariant.PRIMARY)
                )
                .build(),
        backdrop =
            Uri.Builder()
                .appendEncodedPath(
                    ImageCacheRules.localImagePath(itemId, ImageCacheRules.ArtworkVariant.BACKDROP)
                )
                .build(),
        showPrimary =
            Uri.Builder()
                .appendEncodedPath(
                    ImageCacheRules.localImagePath(
                        seriesId,
                        ImageCacheRules.ArtworkVariant.PRIMARY,
                    )
                )
                .build(),
        showBackdrop =
            Uri.Builder()
                .appendEncodedPath(
                    ImageCacheRules.localImagePath(
                        seriesId,
                        ImageCacheRules.ArtworkVariant.BACKDROP,
                    )
                )
                .build(),
    )
}
