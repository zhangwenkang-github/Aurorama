package com.zhangwenkang.cinefin.player.local.audio

/**
 * ReplayGain（W30-MUSIC-FX）标签解析：FLAC（VorbisComment）、ID3v2（TXXX）与 M4A/MP4（iTunes free-form `----`
 * 原子）子集。
 *
 * 为什么客户端自己解析：音乐播放固定走 ExoPlayer（`PlayerHolder.audioSession()`，W1 决策 D3），mpv 的 `replaygain`
 * 选项对音乐不可达；Media3 没有原生 ReplayGain。服务器实测（W30 只读探测）100 首 FLAC 的 `MediaSources` JSON 与文件标签里都没有
 * ReplayGain——主数据源是文件标签，另支持本机覆盖文件 `<filesDir>/replaygain/<itemId>.txt`（`track=-6.5` /
 * `album=-8.0`，供无标签文件手动指定，W35 起面板内可直接设置）。
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

/** 编码本机覆盖文本（`track=-6.5` / `album=-8.0`）；两者都为空返回 null（调用方删除覆盖文件）。 */
fun encodeReplayGainOverride(trackGainDb: Float?, albumGainDb: Float?): String? {
    val lines = buildList {
        trackGainDb?.takeIf { it.isFinite() }?.let { add("track=$it") }
        albumGainDb?.takeIf { it.isFinite() }?.let { add("album=$it") }
    }
    return lines.takeIf { it.isNotEmpty() }?.joinToString(separator = "\n", postfix = "\n")
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

/** M4A/MP4：文件头第一个 box 是否为 `ftyp`（第二窗口从 moov 起，不适用本判断）。 */
internal fun isMp4Header(bytes: ByteArray): Boolean =
    bytes.size >= 12 && bytes.compareToAscii(4, "ftyp")

/**
 * M4A/MP4 的 ReplayGain：iTunes 风格 free-form 原子 `moov/udta/meta/ilst/----` （子项 `mean` / `name` /
 * `data`，如 `----:com.apple.iTunes:REPLAYGAIN_TRACK_GAIN`）。
 *
 * 只解析 free-form 名字以 `REPLAYGAIN_` 开头的项；`mean` 不校验（`com.apple.iTunes` 与
 * `org.hydrogenaudio.replaygain` 都可能出现）。
 */
internal data class Mp4ReplayGainResult(
    val tags: Map<String, String>?,
    val moovFound: Boolean,
)

/**
 * 顶层 box 链扫描（前缀窗口 / 从 moov 偏移起的窗口都适用）。
 *
 * - [moovStart] / [moovEnd]：窗口内找到 `moov` 时的内容范围（`moovEnd` 已截断到窗口末尾）；
 * - [nextBoxOffset]：窗口装不下下一个 box 时，其**绝对**偏移（调用方可按此二次 Range 拉取， M4A 非 faststart（moov 在文件尾）靠它定位）。
 */
internal data class Mp4BoxScan(
    val moovStart: Int = -1,
    val moovEnd: Int = -1,
    val moovCompleteInWindow: Boolean = false,
    val nextBoxOffset: Long? = null,
)

/** 扫描 MP4 顶层 box 链；[baseOffset] 为窗口首字节在文件中的绝对偏移。 */
internal fun scanMp4TopLevelBoxes(bytes: ByteArray, baseOffset: Long = 0L): Mp4BoxScan {
    var pos = 0
    while (pos + 8 <= bytes.size) {
        val size = readUInt32Be(bytes, pos)
        val type = readAsciiType(bytes, pos + 4) ?: return Mp4BoxScan()
        var headerSize = 8
        var boxSize = size.toLong()
        if (size == 1) {
            if (pos + 16 > bytes.size) return Mp4BoxScan()
            boxSize = readUInt64Be(bytes, pos + 8)
            headerSize = 16
        } else if (size == 0) {
            boxSize = (bytes.size - pos).toLong()
        }
        if (boxSize < headerSize) return Mp4BoxScan()
        if (type == "moov") {
            val end = pos + boxSize
            return Mp4BoxScan(
                moovStart = pos,
                moovEnd = end.coerceAtMost(bytes.size.toLong()).toInt(),
                moovCompleteInWindow = end <= bytes.size.toLong(),
            )
        }
        val next = pos + boxSize
        if (next > bytes.size) return Mp4BoxScan(nextBoxOffset = baseOffset + next)
        if (next <= pos) return Mp4BoxScan()
        pos = next.toInt()
    }
    return Mp4BoxScan()
}

/** 解析窗口内 `moov` 的 ReplayGain free-form 标签；窗口需从文件头或 moov 头开始。 */
internal fun parseMp4ReplayGain(bytes: ByteArray): Mp4ReplayGainResult {
    val scan = scanMp4TopLevelBoxes(bytes)
    if (scan.moovStart < 0) return Mp4ReplayGainResult(null, moovFound = false)
    val headerSize = if (readUInt32Be(bytes, scan.moovStart) == 1) 16 else 8
    val tags = LinkedHashMap<String, String>()
    forEachBox(bytes, scan.moovStart + headerSize, scan.moovEnd) { type, start, end ->
        if (type == "udta") collectUdtaTags(bytes, start, end, tags)
    }
    return Mp4ReplayGainResult(tags.takeIf { it.isNotEmpty() }, moovFound = true)
}

internal fun Mp4ReplayGainResult.toTrackReplayGain(): TrackReplayGain? =
    tags?.toReplayGain(ReplayGainSource.EMBEDDED)

private fun collectUdtaTags(
    bytes: ByteArray,
    start: Int,
    end: Int,
    tags: MutableMap<String, String>,
) {
    forEachBox(bytes, start, end) { type, contentStart, contentEnd ->
        if (type != "meta") return@forEachBox
        // ISO 规范里 meta 是 FullBox（含 4 字节 version/flags）；QuickTime 老文件可能没有，做探测。
        val childrenStart =
            if (looksLikeBoxHeader(bytes, contentStart, contentEnd)) {
                contentStart
            } else {
                (contentStart + 4).coerceAtMost(contentEnd)
            }
        forEachBox(bytes, childrenStart, contentEnd) { metaType, metaStart, metaEnd ->
            if (metaType != "ilst") return@forEachBox
            forEachBox(bytes, metaStart, metaEnd) { itemType, itemStart, itemEnd ->
                if (itemType == "----") collectFreeformTag(bytes, itemStart, itemEnd, tags)
            }
        }
    }
}

private fun collectFreeformTag(
    bytes: ByteArray,
    start: Int,
    end: Int,
    tags: MutableMap<String, String>,
) {
    var name: String? = null
    var value: String? = null
    forEachBox(bytes, start, end) { type, contentStart, contentEnd ->
        when (type) {
            "name" -> name = readFullBoxText(bytes, contentStart, contentEnd)
            "data" -> value = readDataBoxText(bytes, contentStart, contentEnd)
        }
    }
    val key = name?.trim()?.uppercase() ?: return
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
    if (key.startsWith("REPLAYGAIN_")) tags[key] = text
}

/** `mean` / `name`：4 字节 version/flags + UTF-8 文本。 */
private fun readFullBoxText(bytes: ByteArray, start: Int, end: Int): String? {
    val textStart = start + 4
    if (textStart > end) return null
    return String(bytes, textStart, end - textStart, Charsets.UTF_8)
}

/** `data`：4 字节 version/flags（type indicator）+ 4 字节 locale + 负载（1 = UTF-8，2 = UTF-16BE）。 */
private fun readDataBoxText(bytes: ByteArray, start: Int, end: Int): String? {
    val textStart = start + 8
    if (textStart > end) return null
    val charset = if (readUInt32Be(bytes, start) == 2) Charsets.UTF_16BE else Charsets.UTF_8
    return String(bytes, textStart, end - textStart, charset)
}

private inline fun forEachBox(
    bytes: ByteArray,
    start: Int,
    end: Int,
    action: (type: String, contentStart: Int, contentEnd: Int) -> Unit,
) {
    var pos = start
    while (pos + 8 <= end) {
        val size = readUInt32Be(bytes, pos)
        val type = readAsciiType(bytes, pos + 4) ?: return
        var headerSize = 8
        var boxSize = size.toLong()
        if (size == 1) {
            if (pos + 16 > end) return
            boxSize = readUInt64Be(bytes, pos + 8)
            headerSize = 16
        } else if (size == 0) {
            boxSize = (end - pos).toLong()
        }
        if (boxSize < headerSize) return
        val contentStart = pos + headerSize
        val contentEnd = (pos + boxSize).coerceAtMost(end.toLong()).toInt()
        action(type, contentStart, contentEnd)
        val next = pos + boxSize
        if (next <= pos || next > end) return
        pos = next.toInt()
    }
}

private fun looksLikeBoxHeader(bytes: ByteArray, pos: Int, end: Int): Boolean {
    if (pos + 8 > end) return false
    val size = readUInt32Be(bytes, pos)
    val type = readAsciiType(bytes, pos + 4) ?: return false
    if (type.isBlank()) return false
    return size == 0 || size == 1 || size >= 8
}

/** box 类型 4 字节：可打印 ASCII 或 `©`（0xA9）；含控制字节时返回 null。 */
private fun readAsciiType(bytes: ByteArray, offset: Int): String? {
    if (offset + 4 > bytes.size) return null
    for (index in 0 until 4) {
        val value = bytes[offset + index].toInt() and 0xFF
        if (value !in 0x20..0x7E && value != 0xA9) return null
    }
    return String(bytes, offset, 4, Charsets.ISO_8859_1)
}

private fun readUInt64Be(bytes: ByteArray, offset: Int): Long {
    if (offset + 8 > bytes.size) return -1L
    var value = 0L
    for (index in 0 until 8) {
        value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
    }
    return value
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
