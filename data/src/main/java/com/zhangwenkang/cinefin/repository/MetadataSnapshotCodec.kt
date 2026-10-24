package com.zhangwenkang.cinefin.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.jellyfin.sdk.model.api.BaseItemDto

internal enum class MetadataSnapshotPayloadKind(val wireName: String) {
    BASE_ITEM_DTO("baseItemDto"),
    BASE_ITEM_DTO_LIST("baseItemDtoList"),
    STRING("string"),
    BOOLEAN("boolean"),
    INT("int");

    companion object {
        fun fromWireName(value: String): MetadataSnapshotPayloadKind? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

internal data class EncodedMetadataSnapshotPayload(
    val kind: MetadataSnapshotPayloadKind,
    val payload: JsonElement,
)

/**
 * Applies a field allowlist to SDK DTOs before they can reach the snapshot store.
 *
 * The allowlist is applied to field names and nested object keys. String values are retained
 * verbatim, including ordinary URLs in fields such as `Overview`; no heuristic URL scan is used.
 * `UserData`, media sources, playback URLs, paths, and credential-bearing fields are not copied.
 */
internal class MetadataSnapshotCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun encode(value: Any): EncodedMetadataSnapshotPayload? =
        when (value) {
            is BaseItemDto ->
                EncodedMetadataSnapshotPayload(
                    kind = MetadataSnapshotPayloadKind.BASE_ITEM_DTO,
                    payload = sanitizeItem(value),
                )
            is List<*> -> encodeItemList(value)
            is String ->
                EncodedMetadataSnapshotPayload(
                    kind = MetadataSnapshotPayloadKind.STRING,
                    payload = JsonPrimitive(value),
                )
            is Boolean ->
                EncodedMetadataSnapshotPayload(
                    kind = MetadataSnapshotPayloadKind.BOOLEAN,
                    payload = JsonPrimitive(value),
                )
            is Int ->
                EncodedMetadataSnapshotPayload(
                    kind = MetadataSnapshotPayloadKind.INT,
                    payload = JsonPrimitive(value),
                )
            else -> null
        }

    fun decode(encoded: EncodedMetadataSnapshotPayload): Any? =
        try {
            when (encoded.kind) {
                MetadataSnapshotPayloadKind.BASE_ITEM_DTO ->
                    json.decodeFromJsonElement(
                        BaseItemDto.serializer(),
                        sanitizePayload(encoded.payload),
                    )
                MetadataSnapshotPayloadKind.BASE_ITEM_DTO_LIST ->
                    encoded.payload as? JsonArray ?: return null
                MetadataSnapshotPayloadKind.STRING ->
                    (encoded.payload as? JsonPrimitive)?.takeIf { it.isString }?.content
                MetadataSnapshotPayloadKind.BOOLEAN ->
                    (encoded.payload as? JsonPrimitive)
                        ?.takeIf { !it.isString }
                        ?.content
                        ?.toBooleanStrictOrNull()
                MetadataSnapshotPayloadKind.INT ->
                    (encoded.payload as? JsonPrimitive)
                        ?.takeIf { !it.isString }
                        ?.content
                        ?.toIntOrNull()
            }.let { decoded ->
                if (encoded.kind == MetadataSnapshotPayloadKind.BASE_ITEM_DTO_LIST) {
                    (decoded as? JsonArray)?.map { element ->
                        json.decodeFromJsonElement(
                            BaseItemDto.serializer(),
                            sanitizePayload(element),
                        )
                    }
                } else {
                    decoded
                }
            }
        } catch (_: Exception) {
            null
        }

    private fun encodeItemList(values: List<*>): EncodedMetadataSnapshotPayload? {
        if (values.any { it !is BaseItemDto }) return null
        return EncodedMetadataSnapshotPayload(
            kind = MetadataSnapshotPayloadKind.BASE_ITEM_DTO_LIST,
            payload =
                buildJsonArray {
                    values.forEach { value -> add(sanitizeItem(value as BaseItemDto)) }
                },
        )
    }

    private fun sanitizeItem(value: BaseItemDto): JsonObject {
        val serialized = json.encodeToJsonElement(BaseItemDto.serializer(), value).jsonObject
        return buildJsonObject {
            serialized.forEach { (name, child) ->
                if (name in ALLOWED_ITEM_FIELDS && !isForbiddenField(name)) {
                    put(name, sanitizePayload(child))
                }
            }
        }
    }

    private fun sanitizePayload(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject ->
                buildJsonObject {
                    value.forEach { (name, child) ->
                        if (!isForbiddenField(name)) {
                            put(name, sanitizePayload(child))
                        }
                    }
                }
            is JsonArray -> buildJsonArray { value.forEach { add(sanitizePayload(it)) } }
            else -> value
        }

    private fun isForbiddenField(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized in FORBIDDEN_FIELD_NAMES ||
            normalized.endsWith("path") ||
            normalized.endsWith("url") ||
            normalized.endsWith("token")
    }

    private companion object {
        val ALLOWED_ITEM_FIELDS =
            setOf(
                "Name",
                "OriginalTitle",
                "Id",
                "Container",
                "SortName",
                "PremiereDate",
                "CriticRating",
                "OfficialRating",
                "CustomRating",
                "Overview",
                "Taglines",
                "Genres",
                "CommunityRating",
                "CumulativeRunTimeTicks",
                "RunTimeTicks",
                "AspectRatio",
                "ProductionYear",
                "IndexNumber",
                "IndexNumberEnd",
                "ParentIndexNumber",
                "ParentId",
                "Type",
                "People",
                "Studios",
                "GenreItems",
                "ChildCount",
                "SeriesName",
                "SeriesId",
                "Status",
                "SeriesPrimaryImageTag",
                "ImageTags",
                "BackdropImageTags",
                "ScreenshotImageTags",
                "SeriesThumbImageTag",
                "SeriesStudio",
                "Chapters",
                "EndDate",
                "Width",
                "Height",
                "CollectionType",
                "LocationType",
                "IsoType",
                "MediaType",
                "ProductionLocations",
                "MovieCount",
                "SeriesCount",
                "EpisodeCount",
                "SongCount",
                "AlbumCount",
                "ArtistCount",
                "MusicVideoCount",
            )

        val FORBIDDEN_FIELD_NAMES =
            setOf(
                "mediasources",
                "mediastreams",
                "remotetrailers",
                "externalurls",
                "path",
                "directstreamurl",
                "transcodingurl",
                "userdata",
                "accesstoken",
                "authorization",
                "apikey",
                "api_key",
                "token",
                "headers",
                "requiredhttpheaders",
            )
    }
}
