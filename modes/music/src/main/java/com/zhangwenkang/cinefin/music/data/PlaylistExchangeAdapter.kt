package com.zhangwenkang.cinefin.music.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 单歌单交换文档；Room 仍是运行真源，本类型仅存在于文件导入导出边界。 */
data class PlaylistExchangeDocument(
    val name: String,
    val entries: List<PlaylistExchangeEntry>,
)

data class PlaylistExchangeEntry(
    val track: LocalPlaylistTrack,
    val unavailable: Boolean,
)

class PlaylistExchangeException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * 交换格式适配器。
 *
 * JSON 是完整备份；M3U8 只从显式 `#EXT-X-AURORAMA-TRACK` 恢复身份。EXTINF 标题、媒体路径、UUID 形状的文本均不参与 身份推断。
 */
object PlaylistExchangeAdapter {
    const val MAX_FILE_BYTES: Int = 2 * 1024 * 1024
    const val MAX_ENTRIES: Int = 5_000
    private const val MAX_NAME_LENGTH = 512
    private const val MAX_TEXT_LENGTH = 8_192
    private const val FORMAT_MARKER = "aurorama-playlist"
    private const val SCHEMA_VERSION = 1
    private const val TICKS_PER_SECOND = 10_000_000L

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
        encodeDefaults = true
    }

    fun encodeJson(
        document: PlaylistExchangeDocument,
        enforceLimits: Boolean = true,
    ): String {
        requireValidDocument(document, enforceLimits = enforceLimits)
        val encoded =
            json.encodeToString(
                WireDocument(
                    format = FORMAT_MARKER,
                    schemaVersion = SCHEMA_VERSION,
                    playlist =
                        WirePlaylist(
                            name = document.name,
                            entries =
                                document.entries.mapIndexed { index, entry ->
                                    entry.toWire(position = index)
                                },
                        ),
                )
            )
        if (enforceLimits && encoded.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
            throw PlaylistExchangeException("JSON 歌单超过文件大小上限")
        }
        return encoded
    }

    fun decodeJson(raw: String): PlaylistExchangeDocument {
        validateEncodedSize(raw, "JSON")
        val wire =
            try {
                json.decodeFromString<WireDocument>(raw)
            } catch (error: SerializationException) {
                throw PlaylistExchangeException("JSON 歌单格式无效或不完整", error)
            } catch (error: IllegalArgumentException) {
                throw PlaylistExchangeException("JSON 歌单格式无效或不完整", error)
            }
        if (wire.format != FORMAT_MARKER) {
            throw PlaylistExchangeException("不是 Aurorama 歌单备份")
        }
        if (wire.schemaVersion != SCHEMA_VERSION) {
            throw PlaylistExchangeException("不支持的 JSON schema 版本：${wire.schemaVersion}")
        }
        return try {
            wire.playlist.entries.forEachIndexed { index, entry ->
                if (entry.position != index) {
                    throw PlaylistExchangeException("JSON 条目顺序非法")
                }
            }
            val document =
                PlaylistExchangeDocument(
                    name = wire.playlist.name,
                    entries = wire.playlist.entries.map { it.toDomain() },
                )
            requireValidDocument(document, enforceLimits = true)
            document
        } catch (error: PlaylistExchangeException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw PlaylistExchangeException("JSON 歌单身份或字段无效", error)
        }
    }

    fun encodeM3u8(document: PlaylistExchangeDocument): String {
        requireValidDocument(document, enforceLimits = true)
        val output = StringBuilder()
        output.append("#EXTM3U\n")
        output.append("#EXT-X-AURORAMA-PLAYLIST:v1;name=")
        output.append(percentEncode(document.name))
        output.append('\n')
        document.entries.forEach { entry ->
            val snapshot = entry.track.snapshot
            output.append("#EXTINF:")
            output.append(formatSeconds(snapshot.runtimeTicks))
            output.append(',')
            output.append(singleLine(snapshot.displayName))
            output.append('\n')
            output.append("#EXT-X-AURORAMA-TRACK:v1;")
            when (val ref = entry.track.ref) {
                is TrackRef.Server -> {
                    output.append("kind=SERVER;serverId=")
                    output.append(percentEncode(ref.serverId))
                    output.append(";userId=")
                    output.append(ref.userId)
                    output.append(";itemId=")
                    output.append(ref.itemId)
                }
                is TrackRef.Local -> {
                    output.append("kind=LOCAL;localLibraryId=")
                    output.append(ref.libraryId)
                    output.append(";itemId=")
                    output.append(ref.itemId)
                }
                is TrackRef.Unresolved -> {
                    output.append("kind=PLACEHOLDER;placeholderKey=")
                    output.append(ref.placeholderKey)
                }
            }
            output.append(";unavailable=")
            output.append(if (entry.unavailable) "1" else "0")
            output.append(";ticks=")
            output.append(snapshot.runtimeTicks)
            output.append(";album=")
            output.append(percentEncode(snapshot.albumName))
            output.append(";artist=")
            output.append(percentEncode(snapshot.artist.orEmpty()))
            output.append(";image=")
            output.append(percentEncode(snapshot.imageUri.orEmpty()))
            output.append(";source=")
            output.append(percentEncode(snapshot.mediaSourceUri.orEmpty()))
            output.append('\n')
            output.append(
                snapshot.mediaSourceUri?.takeIf { it.isNotBlank() }?.let(::singleLine)
                    ?: when (val ref = entry.track.ref) {
                        is TrackRef.Server -> "aurorama://server/${ref.itemId}"
                        is TrackRef.Local -> "aurorama://local/${ref.libraryId}/${ref.itemId}"
                        is TrackRef.Unresolved -> "aurorama://placeholder/${ref.placeholderKey}"
                    }
            )
            output.append('\n')
        }
        val encoded = output.toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
            throw PlaylistExchangeException("M3U8 歌单超过文件大小上限")
        }
        return encoded
    }

    fun decodeM3u8(raw: String): PlaylistExchangeDocument {
        validateEncodedSize(raw, "M3U8")
        val normalized = raw.removePrefix("\uFEFF")
        val lines = normalized.lines()
        if (lines.firstOrNull()?.trim() != "#EXTM3U") {
            throw PlaylistExchangeException("M3U8 缺少 #EXTM3U 头")
        }
        var playlistName = "导入的歌单"
        var playlistExtensionSeen = false
        var pendingExtinf: Extinf? = null
        var pendingIdentity: ParsedIdentity? = null
        val entries = mutableListOf<PlaylistExchangeEntry>()
        val seenIdentities = mutableSetOf<String>()

        lines.drop(1).forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith(AURORAMA_PLAYLIST_PREFIX) -> {
                    if (playlistExtensionSeen) {
                        throw PlaylistExchangeException("M3U8 包含重复的歌单扩展")
                    }
                    val values = parseExtension(line, AURORAMA_PLAYLIST_PREFIX)
                    ensureKeys(values, setOf("v", "name"))
                    if (values["v"] != "1") {
                        throw PlaylistExchangeException("不支持的 M3U8 Aurorama 扩展版本")
                    }
                    playlistName = decodeRequired(values, "name", "M3U8 歌单缺少名称")
                    playlistExtensionSeen = true
                }
                line.startsWith(AURORAMA_TRACK_PREFIX) -> {
                    if (pendingExtinf == null || pendingIdentity != null) {
                        throw PlaylistExchangeException("M3U8 身份扩展缺少唯一对应的 EXTINF")
                    }
                    pendingIdentity =
                        try {
                            parseIdentity(parseExtension(line, AURORAMA_TRACK_PREFIX))
                        } catch (error: PlaylistExchangeException) {
                            throw error
                        } catch (error: IllegalArgumentException) {
                            throw PlaylistExchangeException("M3U8 曲目身份无效", error)
                        }
                }
                line.startsWith("#EXTINF:") -> {
                    if (pendingExtinf != null) {
                        throw PlaylistExchangeException("M3U8 EXTINF 缺少对应媒体行")
                    }
                    pendingExtinf = parseExtinf(line.removePrefix("#EXTINF:"))
                }
                line.startsWith("#EXT-X-AURORAMA-") ->
                    throw PlaylistExchangeException("未知的 Aurorama M3U8 扩展")
                line.startsWith("#") -> Unit
                else -> {
                    val extinf =
                        pendingExtinf ?: throw PlaylistExchangeException("M3U8 媒体行缺少 EXTINF")
                    val entry =
                        createM3u8Entry(
                            index = entries.size,
                            extinf = extinf,
                            parsedIdentity = pendingIdentity,
                            mediaLine = line,
                        )
                    val explicitKey = entry.track.ref.duplicateIdentityKey()
                    if (explicitKey != null && !seenIdentities.add(explicitKey)) {
                        throw PlaylistExchangeException("M3U8 包含重复的显式曲目身份")
                    }
                    entries += entry
                    if (entries.size > MAX_ENTRIES) {
                        throw PlaylistExchangeException("M3U8 条目数超过上限")
                    }
                    pendingExtinf = null
                    pendingIdentity = null
                }
            }
        }
        if (pendingExtinf != null || pendingIdentity != null) {
            throw PlaylistExchangeException("M3U8 在条目结束前被截断")
        }
        val document = PlaylistExchangeDocument(name = playlistName, entries = entries)
        requireValidDocument(document, enforceLimits = true)
        return document
    }

    fun validate(document: PlaylistExchangeDocument) {
        requireValidDocument(document, enforceLimits = true)
    }

    fun readBoundedUtf8(
        input: InputStream,
        maxBytes: Int = MAX_FILE_BYTES,
    ): String {
        require(maxBytes >= 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, 8_192))
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw PlaylistExchangeException("歌单文件超过大小上限")
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    fun importCopyName(originalName: String, existingNames: Set<String>): String {
        val base = originalName.trim().ifEmpty { "导入的歌单" }
        if (base !in existingNames) return base
        var suffix = 2
        while (true) {
            val suffixText = " (导入 $suffix)"
            val prefix = base.take(MAX_NAME_LENGTH - suffixText.length)
            val candidate = "$prefix$suffixText"
            if (candidate !in existingNames) return candidate
            suffix++
        }
    }

    private fun requireValidDocument(
        document: PlaylistExchangeDocument,
        enforceLimits: Boolean,
    ) {
        val name = document.name.trim()
        if (name.isEmpty()) throw PlaylistExchangeException("歌单名不能为空")
        if (name.length > MAX_NAME_LENGTH) throw PlaylistExchangeException("歌单名过长")
        if (document.entries.size > MAX_ENTRIES && enforceLimits) {
            throw PlaylistExchangeException("歌单条目数超过上限")
        }
        val explicitIdentities = mutableSetOf<String>()
        document.entries.forEach { entry ->
            validateSnapshot(entry.track.snapshot)
            val ref = entry.track.ref
            when (ref) {
                is TrackRef.Server -> {
                    if (ref.serverId.isBlank()) {
                        throw PlaylistExchangeException("服务器曲目缺少 serverId")
                    }
                }
                is TrackRef.Local -> {
                    if (ref.libraryId <= 0L) {
                        throw PlaylistExchangeException("本地曲目缺少 localLibraryId")
                    }
                }
                is TrackRef.Unresolved ->
                    if (!entry.unavailable) {
                        throw PlaylistExchangeException("无稳定身份的曲目必须保留为不可用占位")
                    }
            }
            ref.duplicateIdentityKey()?.let { key ->
                if (!explicitIdentities.add(key)) {
                    throw PlaylistExchangeException("歌单包含重复的显式曲目身份")
                }
            }
        }
    }

    private fun validateSnapshot(snapshot: PlaylistTrackSnapshot) {
        if (snapshot.displayName.length > MAX_TEXT_LENGTH) {
            throw PlaylistExchangeException("曲目标题过长")
        }
        if (snapshot.albumName.length > MAX_TEXT_LENGTH) {
            throw PlaylistExchangeException("专辑名过长")
        }
        if ((snapshot.artist?.length ?: 0) > MAX_TEXT_LENGTH) {
            throw PlaylistExchangeException("歌手名过长")
        }
        if ((snapshot.imageUri?.length ?: 0) > MAX_TEXT_LENGTH) {
            throw PlaylistExchangeException("封面地址过长")
        }
        if ((snapshot.mediaSourceUri?.length ?: 0) > MAX_TEXT_LENGTH) {
            throw PlaylistExchangeException("媒体地址过长")
        }
        if (snapshot.runtimeTicks < 0L) {
            throw PlaylistExchangeException("曲目时长不能为负数")
        }
    }

    private fun validateEncodedSize(raw: String, format: String) {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
            throw PlaylistExchangeException("$format 歌单超过文件大小上限")
        }
    }

    private fun PlaylistExchangeEntry.toWire(position: Int) =
        WireEntry(
            position = position,
            identity = track.ref.toWireIdentity(),
            snapshot =
                WireSnapshot(
                    displayName = track.snapshot.displayName,
                    albumName = track.snapshot.albumName,
                    artist = track.snapshot.artist,
                    imageUri = track.snapshot.imageUri,
                    mediaSourceUri = track.snapshot.mediaSourceUri,
                    runtimeTicks = track.snapshot.runtimeTicks,
                ),
            unavailable = unavailable,
        )

    private fun TrackRef.toWireIdentity() =
        when (this) {
            is TrackRef.Server ->
                WireIdentity(
                    kind = "server",
                    serverId = serverId,
                    userId = userId.toString(),
                    localLibraryId = null,
                    itemId = itemId.toString(),
                    placeholderKey = null,
                )
            is TrackRef.Local ->
                WireIdentity(
                    kind = "local",
                    serverId = null,
                    userId = null,
                    localLibraryId = libraryId,
                    itemId = itemId.toString(),
                    placeholderKey = null,
                )
            is TrackRef.Unresolved ->
                WireIdentity(
                    kind = "placeholder",
                    serverId = null,
                    userId = null,
                    localLibraryId = null,
                    itemId = null,
                    placeholderKey = placeholderKey.toString(),
                )
        }

    private fun WireEntry.toDomain(): PlaylistExchangeEntry =
        PlaylistExchangeEntry(
            track =
                LocalPlaylistTrack(
                    ref = identity.toDomain(),
                    snapshot =
                        PlaylistTrackSnapshot(
                            displayName = snapshot.displayName,
                            albumName = snapshot.albumName,
                            artist = snapshot.artist,
                            imageUri = snapshot.imageUri,
                            mediaSourceUri = snapshot.mediaSourceUri,
                            runtimeTicks = snapshot.runtimeTicks,
                        ),
                ),
            unavailable = unavailable,
        )

    private fun WireIdentity.toDomain(): TrackRef =
        when (kind.lowercase()) {
            "server" ->
                if (localLibraryId != null || placeholderKey != null) {
                    throw PlaylistExchangeException("服务器曲目身份包含歧义字段")
                } else {
                    TrackRef.Server(
                        serverId =
                            requireNotNull(serverId?.takeIf { it.isNotBlank() }) {
                                "服务器曲目缺少 serverId"
                            },
                        userId = UUID.fromString(requireNotNull(userId)),
                        itemId = UUID.fromString(requireNotNull(itemId)),
                    )
                }
            "local" ->
                if (serverId != null || userId != null || placeholderKey != null) {
                    throw PlaylistExchangeException("本地曲目身份包含歧义字段")
                } else {
                    TrackRef.Local(
                        libraryId = requireNotNull(localLibraryId),
                        itemId = UUID.fromString(requireNotNull(itemId)),
                    )
                }
            "placeholder" ->
                if (
                    serverId != null || userId != null || localLibraryId != null || itemId != null
                ) {
                    throw PlaylistExchangeException("占位曲目身份包含歧义字段")
                } else {
                    TrackRef.Unresolved(UUID.fromString(requireNotNull(placeholderKey)))
                }
            else -> throw PlaylistExchangeException("未知的曲目身份类型：$kind")
        }

    private fun parseIdentity(values: Map<String, String>): ParsedIdentity {
        if (values["v"] != "1") {
            throw PlaylistExchangeException("不支持的 M3U8 Aurorama 扩展版本")
        }
        val unavailable = values["unavailable"].parseBooleanFlag()
        val runtimeTicks =
            values["ticks"]?.let { raw ->
                raw.toLongOrNull()?.takeIf { it >= 0L }
                    ?: throw PlaylistExchangeException("M3U8 ticks 字段无效")
            }
        val snapshot =
            ParsedSnapshot(
                albumName = values["album"].orEmpty(),
                artist = values["artist"]?.takeIf { it.isNotEmpty() },
                imageUri = values["image"]?.takeIf { it.isNotEmpty() },
                mediaSourceUri = values["source"]?.takeIf { it.isNotEmpty() },
                runtimeTicks = runtimeTicks,
            )
        return when (values["kind"]) {
            "SERVER" -> {
                ensureKeys(
                    values,
                    setOf(
                        "v",
                        "kind",
                        "serverId",
                        "userId",
                        "itemId",
                        "unavailable",
                        "ticks",
                        "album",
                        "artist",
                        "image",
                        "source",
                    ),
                )
                ParsedIdentity(
                    ref =
                        TrackRef.Server(
                            serverId = decodeRequired(values, "serverId", "服务器曲目缺少 serverId"),
                            userId = UUID.fromString(decodeRequired(values, "userId", "缺少 userId")),
                            itemId = UUID.fromString(decodeRequired(values, "itemId", "缺少 itemId")),
                        ),
                    unavailable = unavailable,
                    snapshot = snapshot,
                )
            }
            "LOCAL" -> {
                ensureKeys(
                    values,
                    setOf(
                        "v",
                        "kind",
                        "localLibraryId",
                        "itemId",
                        "unavailable",
                        "ticks",
                        "album",
                        "artist",
                        "image",
                        "source",
                    ),
                )
                ParsedIdentity(
                    ref =
                        TrackRef.Local(
                            libraryId =
                                decodeRequired(values, "localLibraryId", "缺少 localLibraryId")
                                    .toLongOrNull()
                                    ?: throw PlaylistExchangeException("localLibraryId 无效"),
                            itemId = UUID.fromString(decodeRequired(values, "itemId", "缺少 itemId")),
                        ),
                    unavailable = unavailable,
                    snapshot = snapshot,
                )
            }
            "PLACEHOLDER" -> {
                ensureKeys(
                    values,
                    setOf(
                        "v",
                        "kind",
                        "placeholderKey",
                        "unavailable",
                        "ticks",
                        "album",
                        "artist",
                        "image",
                        "source",
                    ),
                )
                ParsedIdentity(
                    ref =
                        TrackRef.Unresolved(
                            placeholderKey =
                                UUID.fromString(
                                    decodeRequired(
                                        values,
                                        "placeholderKey",
                                        "缺少 placeholderKey",
                                    )
                                )
                        ),
                    unavailable = true,
                    snapshot = snapshot,
                )
            }
            else -> throw PlaylistExchangeException("未知的 M3U8 曲目身份类型")
        }
    }

    private fun createM3u8Entry(
        index: Int,
        extinf: Extinf,
        parsedIdentity: ParsedIdentity?,
        mediaLine: String,
    ): PlaylistExchangeEntry {
        if (parsedIdentity != null) {
            val parsedSnapshot = parsedIdentity.snapshot
            return PlaylistExchangeEntry(
                track =
                    LocalPlaylistTrack(
                        ref = parsedIdentity.ref,
                        snapshot =
                            PlaylistTrackSnapshot(
                                displayName = extinf.title,
                                albumName = parsedSnapshot.albumName,
                                artist = parsedSnapshot.artist,
                                imageUri = parsedSnapshot.imageUri,
                                mediaSourceUri = parsedSnapshot.mediaSourceUri,
                                runtimeTicks = parsedSnapshot.runtimeTicks ?: extinf.runtimeTicks,
                            ),
                    ),
                unavailable =
                    parsedIdentity.unavailable || parsedIdentity.ref is TrackRef.Unresolved,
            )
        }
        val placeholderKey =
            UUID.nameUUIDFromBytes(
                "m3u8-placeholder\u0000$index\u0000${extinf.title}\u0000${extinf.runtimeTicks}\u0000$mediaLine"
                    .toByteArray(Charsets.UTF_8)
            )
        return PlaylistExchangeEntry(
            track =
                LocalPlaylistTrack(
                    ref = TrackRef.Unresolved(placeholderKey),
                    snapshot =
                        PlaylistTrackSnapshot(
                            displayName = extinf.title,
                            albumName = "",
                            artist = null,
                            imageUri = null,
                            mediaSourceUri = null,
                            runtimeTicks = extinf.runtimeTicks,
                        ),
                ),
            unavailable = true,
        )
    }

    private fun parseExtinf(raw: String): Extinf {
        val comma = raw.indexOf(',')
        if (comma <= 0) throw PlaylistExchangeException("M3U8 EXTINF 格式无效")
        val seconds = raw.substring(0, comma).trim().toBigDecimalOrNull()
        if (seconds == null || seconds < BigDecimal.ZERO) {
            throw PlaylistExchangeException("M3U8 EXTINF 时长无效")
        }
        val title = raw.substring(comma + 1).trim()
        if (title.length > MAX_TEXT_LENGTH) {
            throw PlaylistExchangeException("M3U8 标题过长")
        }
        val ticks =
            try {
                seconds
                    .multiply(BigDecimal(TICKS_PER_SECOND))
                    .setScale(0, RoundingMode.DOWN)
                    .longValueExact()
            } catch (error: ArithmeticException) {
                throw PlaylistExchangeException("M3U8 EXTINF 时长超出范围", error)
            }
        return Extinf(title = title, runtimeTicks = ticks)
    }

    private fun parseExtension(line: String, prefix: String): Map<String, String> {
        val payload =
            line.removePrefix(prefix).takeIf { it.isNotBlank() }
                ?: throw PlaylistExchangeException("Aurorama M3U8 扩展为空")
        val parts = payload.split(';')
        val version =
            parts.firstOrNull()?.takeIf { it.startsWith("v") }
                ?: throw PlaylistExchangeException("Aurorama M3U8 扩展缺少版本")
        val versionValue = version.removePrefix("v").removePrefix("=")
        if (versionValue.isBlank()) {
            throw PlaylistExchangeException("Aurorama M3U8 扩展缺少版本")
        }
        val values = linkedMapOf("v" to versionValue)
        parts.drop(1).forEach { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) throw PlaylistExchangeException("Aurorama M3U8 扩展字段无效")
            val key = part.substring(0, separator)
            val value = part.substring(separator + 1)
            if (values.put(key, percentDecode(value)) != null) {
                throw PlaylistExchangeException("Aurorama M3U8 扩展字段重复：$key")
            }
        }
        return values
    }

    private fun ensureKeys(values: Map<String, String>, allowed: Set<String>) {
        if (!allowed.containsAll(values.keys)) {
            throw PlaylistExchangeException("Aurorama M3U8 扩展含未知字段")
        }
    }

    private fun decodeRequired(
        values: Map<String, String>,
        key: String,
        message: String,
    ): String = values[key]?.takeIf { it.isNotBlank() } ?: throw PlaylistExchangeException(message)

    private fun String?.parseBooleanFlag(): Boolean =
        when (this) {
            null,
            "0" -> false
            "1" -> true
            else -> throw PlaylistExchangeException("unavailable 标记必须是 0 或 1")
        }

    private fun percentEncode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val output = StringBuilder(bytes.size)
        bytes.forEach { byte ->
            val valueByte = byte.toInt() and 0xff
            val safe =
                valueByte in 'a'.code..'z'.code ||
                    valueByte in 'A'.code..'Z'.code ||
                    valueByte in '0'.code..'9'.code ||
                    valueByte == '-'.code ||
                    valueByte == '_'.code ||
                    valueByte == '.'.code ||
                    valueByte == '~'.code
            if (safe) {
                output.append(valueByte.toChar())
            } else {
                output.append('%')
                output.append(HEX[valueByte ushr 4])
                output.append(HEX[valueByte and 0x0f])
            }
        }
        return output.toString()
    }

    private fun percentDecode(value: String): String {
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%') {
                if (index + 2 >= value.length) {
                    throw PlaylistExchangeException("Aurorama M3U8 扩展的百分号编码不完整")
                }
                val high = value[index + 1].digitToIntOrNull(16)
                val low = value[index + 2].digitToIntOrNull(16)
                if (high == null || low == null) {
                    throw PlaylistExchangeException("Aurorama M3U8 扩展的百分号编码无效")
                }
                bytes.write((high shl 4) or low)
                index += 3
            } else {
                bytes.write(char.toString().toByteArray(Charsets.UTF_8))
                index++
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun formatSeconds(runtimeTicks: Long): String =
        BigDecimal(runtimeTicks)
            .divide(BigDecimal(TICKS_PER_SECOND), 3, RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()

    private fun singleLine(value: String): String = value.replace('\r', ' ').replace('\n', ' ')

    private fun TrackRef.duplicateIdentityKey(): String? =
        when (this) {
            is TrackRef.Server -> "server\u0000$serverId\u0000$userId\u0000$itemId"
            is TrackRef.Local -> "local\u0000$libraryId\u0000$itemId"
            is TrackRef.Unresolved -> "placeholder\u0000$placeholderKey"
        }

    private data class Extinf(val title: String, val runtimeTicks: Long)

    private data class ParsedIdentity(
        val ref: TrackRef,
        val unavailable: Boolean,
        val snapshot: ParsedSnapshot,
    )

    private data class ParsedSnapshot(
        val albumName: String,
        val artist: String?,
        val imageUri: String?,
        val mediaSourceUri: String?,
        val runtimeTicks: Long?,
    )

    @Serializable
    private data class WireDocument(
        val format: String,
        val schemaVersion: Int,
        val playlist: WirePlaylist,
    )

    @Serializable
    private data class WirePlaylist(
        val name: String,
        val entries: List<WireEntry>,
    )

    @Serializable
    private data class WireEntry(
        val position: Int,
        val identity: WireIdentity,
        val snapshot: WireSnapshot,
        val unavailable: Boolean,
    )

    @Serializable
    private data class WireIdentity(
        val kind: String,
        val serverId: String?,
        val userId: String?,
        val localLibraryId: Long?,
        @SerialName("itemId") val itemId: String?,
        val placeholderKey: String?,
    )

    @Serializable
    private data class WireSnapshot(
        val displayName: String,
        val albumName: String,
        val artist: String?,
        val imageUri: String?,
        val mediaSourceUri: String?,
        val runtimeTicks: Long,
    )

    private const val AURORAMA_PLAYLIST_PREFIX = "#EXT-X-AURORAMA-PLAYLIST:"
    private const val AURORAMA_TRACK_PREFIX = "#EXT-X-AURORAMA-TRACK:"
    private const val HEX = "0123456789ABCDEF"
}
