package com.zhangwenkang.cinefin.player.local.audio

/**
 * ReplayGain（W30-MUSIC-FX）标签解析：FLAC（VorbisComment）与 ID3v2（TXXX）子集。
 *
 * 为什么客户端自己解析：音乐播放固定走 ExoPlayer（`PlayerHolder.audioSession()`，W1 决策 D3），mpv 的 `replaygain`
 * 选项对音乐不可达；Media3 没有原生 ReplayGain。服务器实测（W30 只读探测）100 首 FLAC 的 `MediaSources` JSON 与文件标签里都没有
 * ReplayGain——主数据源是文件标签，另支持本机覆盖文件 `<filesDir>/replaygain/<itemId>.txt`（`track=-6.5` /
 * `album=-8.0`，供无标签文件手动指定）。
 */
data class TrackReplayGain(
    val trackGainDb: Float?,
    val albumGainDb: Float?,
    val source: ReplayGainSource,
) {
    val isEmpty: Boolean
        get() = trackGainDb == null && albumGainDb == null
}

enum class ReplayGainSource(val label: String) {
    /** 本机覆盖文件（`files/replaygain/<itemId>.txt`）。 */
    LOCAL_OVERRIDE("本机设置"),

    /** 音频文件内嵌标签（FLAC VorbisComment / ID3v2 TXXX）。 */
    EMBEDDED("文件标签"),
}

/**
 * 解析一个音频文件头部字节，返回其中的 ReplayGain 标签；没有可用标签时返回 null。
 *
 * 只做头部解析，调用方负责按 Range 读取前若干 KB。
 */
fun parseReplayGainBytes(bytes: ByteArray): TrackReplayGain? {
    val tags =
        when {
            bytes.size >= 4 && bytes.compareToAscii(0, "fLaC") -> parseFlacVorbisComments(bytes)
            bytes.size >= 3 && bytes.compareToAscii(0, "ID3") -> parseId3v2Tags(bytes)
            else -> null
        } ?: return null
    return tags.toReplayGain(ReplayGainSource.EMBEDDED)
}

/** 解析本机覆盖文本（`track=-6.5`、`album=-8.0`；忽略空行与 `#` 注释）。 */
fun parseReplayGainOverride(text: String): TrackReplayGain? {
    var track: Float? = null
    var album: Float? = null
    text.lineSequence().forEach { rawLine ->
        val line = rawLine.substringBefore('#').trim()
        if (line.isEmpty() || !line.contains('=')) return@forEach
        val key = line.substringBefore('=').trim().lowercase()
        val value = parseGainDb(line.substringAfter('='))
        when (key) {
            "track",
            "track_gain",
            "replaygain_track_gain" -> track = value
            "album",
            "album_gain",
            "replaygain_album_gain" -> album = value
        }
    }
    val result = TrackReplayGain(track, album, ReplayGainSource.LOCAL_OVERRIDE)
    return result.takeUnless { it.isEmpty }
}

/** `"-7.23 dB"` / `"-7.23"` / `"3.5"` → dB 值；非法返回 null。 */
fun parseGainDb(text: String?): Float? {
    val token = text?.trim()?.substringBefore(' ')?.trim().orEmpty()
    val cleaned = token.replace(Regex("[dD][bB]$"), "").trim()
    if (cleaned.isEmpty()) return null
    val value = cleaned.toFloatOrNull() ?: return null
    return value.takeIf { it.isFinite() && it in -60f..60f }
}

private fun Map<String, String>.toReplayGain(source: ReplayGainSource): TrackReplayGain? {
    val track = parseGainDb(this["REPLAYGAIN_TRACK_GAIN"])
    val album = parseGainDb(this["REPLAYGAIN_ALBUM_GAIN"])
    val result = TrackReplayGain(track, album, source)
    return result.takeUnless { it.isEmpty }
}

private fun ByteArray.compareToAscii(offset: Int, text: String): Boolean {
    if (offset + text.length > size) return false
    for (index in text.indices) {
        if (this[offset + index] != text[index].code.toByte()) return false
    }
    return true
}

/** FLAC metadata：`fLaC` + 若干 block（1 字节 last/type + 3 字节大端长度），取 type=4（VORBIS_COMMENT）。 */
internal fun parseFlacVorbisComments(bytes: ByteArray): Map<String, String>? {
    var pos = 4
    while (pos + 4 <= bytes.size) {
        val header = bytes[pos].toInt() and 0xFF
        val last = header and 0x80 != 0
        val type = header and 0x7F
        val length = readUInt24Be(bytes, pos + 1)
        pos += 4
        if (length < 0 || pos + length > bytes.size) return null
        if (type == 4) {
            return parseVorbisComment(bytes, pos, length)
        }
        if (last) return null
        pos += length
    }
    return null
}

private fun readUInt24Be(bytes: ByteArray, offset: Int): Int {
    if (offset + 3 > bytes.size) return -1
    return ((bytes[offset].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        (bytes[offset + 2].toInt() and 0xFF)
}

/** VorbisComment：vendor 长度（LE u32）+ vendor + 条数（LE u32）+ n×(长度 LE u32 + "KEY=VALUE")。 */
private fun parseVorbisComment(
    bytes: ByteArray,
    offset: Int,
    length: Int,
): Map<String, String>? {
    val end = offset + length
    var pos = offset
    if (pos + 4 > end) return null
    val vendorLength = readUInt32Le(bytes, pos)
    if (vendorLength < 0 || pos + 4 + vendorLength + 4 > end) return null
    pos += 4 + vendorLength
    val count = readUInt32Le(bytes, pos)
    if (count < 0) return null
    pos += 4
    val tags = mutableMapOf<String, String>()
    repeat(count.coerceAtMost(4_096)) {
        if (pos + 4 > end) return tags
        val itemLength = readUInt32Le(bytes, pos)
        if (itemLength < 0 || pos + 4 + itemLength > end) return tags
        pos += 4
        val entry = String(bytes, pos, itemLength, Charsets.UTF_8)
        pos += itemLength
        val separator = entry.indexOf('=')
        if (separator > 0) {
            tags[entry.substring(0, separator).uppercase()] = entry.substring(separator + 1)
        }
    }
    return tags
}

private fun readUInt32Le(bytes: ByteArray, offset: Int): Int {
    if (offset + 4 > bytes.size) return -1
    return (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}

/**
 * ID3v2 标签（2.2 / 2.3 / 2.4）：只提取 TXXX 帧里的 `REPLAYGAIN_*` 描述。
 *
 * 不处理 unsynchronisation / zlib 压缩帧——这类帧跳过（返回已收集的标签）。
 */
internal fun parseId3v2Tags(bytes: ByteArray): Map<String, String>? {
    if (bytes.size < 10) return null
    val major = bytes[3].toInt() and 0xFF
    if (major !in 2..4) return null
    val tagSize =
        ((bytes[6].toInt() and 0x7F) shl 21) or
            ((bytes[7].toInt() and 0x7F) shl 14) or
            ((bytes[8].toInt() and 0x7F) shl 7) or
            (bytes[9].toInt() and 0x7F)
    val end = (10 + tagSize).coerceAtMost(bytes.size)
    var pos = 10
    val tags = mutableMapOf<String, String>()
    while (pos + 6 <= end) {
        if (bytes[pos].toInt() == 0) break
        val isV22 = major == 2
        val frameId = String(bytes, pos, if (isV22) 3 else 4, Charsets.ISO_8859_1)
        val headerSize = if (isV22) 6 else 10
        if (pos + headerSize > end) break
        val frameSize =
            if (isV22) {
                readUInt24Be(bytes, pos + 3)
            } else {
                readUInt32Be(bytes, pos + 4)
            }
        if (frameSize <= 0 || pos + headerSize + frameSize > end) break
        val payloadOffset = pos + headerSize
        if (frameId == "TXXX") {
            parseTxxxFrame(bytes, payloadOffset, frameSize)?.let { (key, value) ->
                tags[key.uppercase()] = value
            }
        }
        pos += headerSize + frameSize
    }
    return tags
}

private fun readUInt32Be(bytes: ByteArray, offset: Int): Int {
    if (offset + 4 > bytes.size) return -1
    return ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
}

/** TXXX 帧：编码字节 + 描述 + 0x00 分隔 + 值。 */
private fun parseTxxxFrame(
    bytes: ByteArray,
    offset: Int,
    length: Int,
): Pair<String, String>? {
    if (length < 2) return null
    val encoding = bytes[offset].toInt() and 0xFF
    val body = offset + 1
    val bodyEnd = offset + length
    val charset =
        when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
    val separatorSize = if (charset == Charsets.UTF_16) 2 else 1
    var separator = -1
    var index = body
    while (index + separatorSize <= bodyEnd) {
        val isZero =
            bytes[index].toInt() == 0 && (separatorSize == 1 || bytes[index + 1].toInt() == 0)
        if (isZero) {
            separator = index
            break
        }
        index += separatorSize
    }
    if (separator < 0) return null
    val description = String(bytes, body, separator - body, charset).trim('\u0000').trim()
    val valueStart = separator + separatorSize
    if (valueStart > bodyEnd) return null
    val value = String(bytes, valueStart, bodyEnd - valueStart, charset).trim('\u0000').trim()
    if (!description.uppercase().startsWith("REPLAYGAIN_")) return null
    return description to value
}
