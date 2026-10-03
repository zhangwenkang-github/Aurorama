package com.zhangwenkang.cinefin.utils

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.zip.Inflater
import kotlin.math.min

/**
 * W59 书籍封面：ZIP 归档只读解析（EPUB / CBZ 共用），基于 [ByteSource] 的随机访问。
 *
 * 只读取「中央目录 + 目标条目」的字节区间：在线书籍经 HTTP Range 拉取，**不整本下载**。 支持 STORED(0) / DEFLATE(8)、ZIP64 中央目录（大体积 CBZ
 * 条目数 / 尺寸溢出）。
 */
internal class ZipArchiveReader(private val source: ByteSource) : Closeable {

    data class Entry(
        val name: String,
        val method: Int,
        val flags: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    val entries: List<Entry> by lazy { readCentralDirectory() }

    fun find(name: String): Entry? = entries.firstOrNull { it.name == name }

    /** 解出单个条目字节（体积超上限 / 加密 / 不支持的压缩方式直接失败）。 */
    fun readBytes(entry: Entry, maxBytes: Long = BookCoverRules.MAX_ENTRY_BYTES): ByteArray {
        if (entry.flags and FLAG_ENCRYPTED != 0) throw IOException("ZIP 条目已加密：${entry.name}")
        if (entry.compressedSize < 0L || entry.compressedSize > maxBytes) {
            throw IOException("ZIP 条目压缩体积超限：${entry.name}")
        }
        if (entry.uncompressedSize > maxBytes) {
            throw IOException("ZIP 条目解压体积超限：${entry.name}")
        }
        val header = source.readAt(entry.localHeaderOffset, LOCAL_HEADER_BYTES)
        if (header.size < LOCAL_HEADER_BYTES || u32(header, 0) != LOCAL_HEADER_SIGNATURE) {
            throw IOException("ZIP 本地文件头无效：${entry.name}")
        }
        val nameLength = u16(header, 26)
        val extraLength = u16(header, 28)
        val dataOffset = entry.localHeaderOffset + LOCAL_HEADER_BYTES + nameLength + extraLength
        val compressed = source.readAt(dataOffset, entry.compressedSize.toInt())
        return when (entry.method) {
            METHOD_STORED -> {
                if (compressed.size.toLong() < entry.uncompressedSize) {
                    throw IOException("ZIP 条目读取不完整：${entry.name}")
                }
                compressed
            }
            METHOD_DEFLATED -> inflate(compressed, maxBytes)
            else -> throw IOException("不支持的 ZIP 压缩方式 ${entry.method}：${entry.name}")
        }
    }

    override fun close() {
        source.close()
    }

    // ------------------------------------------------------------------ 中央目录

    private data class Eocd(
        val entries: Long,
        val centralDirectoryOffset: Long,
        val centralDirectorySize: Long,
    )

    private fun readCentralDirectory(): List<Entry> {
        val eocd = findEocd()
        if (
            eocd.centralDirectorySize <= 0L ||
                eocd.centralDirectorySize > MAX_CENTRAL_DIRECTORY_BYTES
        ) {
            throw IOException("ZIP 中央目录体积异常：${eocd.centralDirectorySize}")
        }
        val directory =
            source.readAt(eocd.centralDirectoryOffset, eocd.centralDirectorySize.toInt())
        val entries = ArrayList<Entry>(min(eocd.entries, 4096L).toInt().coerceAtLeast(1))
        var offset = 0
        var parsed = 0L
        while (parsed < eocd.entries && offset + CENTRAL_HEADER_BYTES <= directory.size) {
            if (u32(directory, offset) != CENTRAL_HEADER_SIGNATURE) break
            val flags = u16(directory, offset + 8)
            val method = u16(directory, offset + 10)
            val compressedSize = u32(directory, offset + 20)
            val uncompressedSize = u32(directory, offset + 24)
            val nameLength = u16(directory, offset + 28)
            val extraLength = u16(directory, offset + 30)
            val commentLength = u16(directory, offset + 32)
            val localHeaderOffset = u32(directory, offset + 42)
            val recordEnd = offset + CENTRAL_HEADER_BYTES + nameLength + extraLength + commentLength
            if (recordEnd > directory.size) throw IOException("ZIP 中央目录越界")
            val name = String(directory, offset + 46, nameLength, Charsets.UTF_8)
            val extraStart = offset + 46 + nameLength
            val zip64 =
                parseZip64Extra(
                    extra = directory.copyOfRange(extraStart, extraStart + extraLength),
                    needUncompressed = uncompressedSize == ZIP64_MARKER,
                    needCompressed = compressedSize == ZIP64_MARKER,
                    needOffset = localHeaderOffset == ZIP64_MARKER,
                )
            entries +=
                Entry(
                    name = name,
                    method = method,
                    flags = flags,
                    compressedSize = zip64.compressedSize ?: compressedSize,
                    uncompressedSize = zip64.uncompressedSize ?: uncompressedSize,
                    localHeaderOffset = zip64.localHeaderOffset ?: localHeaderOffset,
                )
            offset = recordEnd
            parsed++
        }
        if (entries.isEmpty()) throw IOException("ZIP 归档为空")
        return entries
    }

    private fun findEocd(): Eocd {
        val total = source.length()
        if (total < END_OF_CENTRAL_DIRECTORY_BYTES) throw IOException("文件过小，不是有效 ZIP")
        val tailLength =
            min(total, END_OF_CENTRAL_DIRECTORY_BYTES + MAX_COMMENT_BYTES + 64L).toInt()
        val tail = source.readAt(total - tailLength, tailLength)
        var index = tail.size - END_OF_CENTRAL_DIRECTORY_BYTES
        while (index >= 0) {
            if (u32(tail, index) == END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                val commentLength = u16(tail, index + 20)
                if (index + END_OF_CENTRAL_DIRECTORY_BYTES + commentLength > tail.size) {
                    index--
                    continue
                }
                val entries = u16(tail, index + 10).toLong()
                val size = u32(tail, index + 12)
                val offset = u32(tail, index + 16)
                if (entries != ZIP64_MARKER_16 && size != ZIP64_MARKER && offset != ZIP64_MARKER) {
                    return Eocd(entries, offset, size)
                }
                return readZip64Eocd(total, index, tail) ?: Eocd(entries, offset, size)
            }
            index--
        }
        throw IOException("不是有效的 ZIP（未找到中央目录）")
    }

    /** ZIP64 扩展定位：EOCD 前 20 字节的 locator → ZIP64 EOCD 记录。 */
    private fun readZip64Eocd(total: Long, eocdIndexInTail: Int, tail: ByteArray): Eocd? {
        val locatorIndex = eocdIndexInTail - ZIP64_LOCATOR_BYTES
        if (locatorIndex < 0 || u32(tail, locatorIndex) != ZIP64_LOCATOR_SIGNATURE) return null
        val zip64Offset = u64(tail, locatorIndex + 8)
        if (zip64Offset < 0L || zip64Offset + ZIP64_EOCD_BYTES > total) return null
        val record = source.readAt(zip64Offset, ZIP64_EOCD_BYTES.toInt())
        if (record.size < ZIP64_EOCD_BYTES || u32(record, 0) != ZIP64_EOCD_SIGNATURE) return null
        return Eocd(
            entries = u64(record, 32),
            centralDirectorySize = u64(record, 40),
            centralDirectoryOffset = u64(record, 48),
        )
    }

    private data class Zip64Values(
        val uncompressedSize: Long?,
        val compressedSize: Long?,
        val localHeaderOffset: Long?,
    )

    /** ZIP64 extra（0x0001）：按「未压缩 → 压缩 → 局部头偏移」顺序仅补齐被标记的字段。 */
    private fun parseZip64Extra(
        extra: ByteArray,
        needUncompressed: Boolean,
        needCompressed: Boolean,
        needOffset: Boolean,
    ): Zip64Values {
        if (!needUncompressed && !needCompressed && !needOffset) {
            return Zip64Values(null, null, null)
        }
        var index = 0
        while (index + 4 <= extra.size) {
            val id = u16(extra, index)
            val size = u16(extra, index + 2)
            if (index + 4 + size > extra.size) break
            if (id == ZIP64_EXTRA_ID) {
                var cursor = index + 4
                var uncompressed: Long? = null
                var compressed: Long? = null
                var offset: Long? = null
                if (needUncompressed && cursor + 8 <= index + 4 + size) {
                    uncompressed = u64(extra, cursor)
                    cursor += 8
                }
                if (needCompressed && cursor + 8 <= index + 4 + size) {
                    compressed = u64(extra, cursor)
                    cursor += 8
                }
                if (needOffset && cursor + 8 <= index + 4 + size) {
                    offset = u64(extra, cursor)
                }
                return Zip64Values(uncompressed, compressed, offset)
            }
            index += 4 + size
        }
        return Zip64Values(null, null, null)
    }

    // ------------------------------------------------------------------ 解压

    private fun inflate(compressed: ByteArray, maxBytes: Long): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(compressed)
            val output =
                ByteArrayOutputStream(
                    min(compressed.size * 4L, maxBytes).toInt().coerceAtLeast(1024)
                )
            val buffer = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw IOException("ZIP 条目解压提前结束")
                }
                output.write(buffer, 0, read)
                if (output.size() > maxBytes) throw IOException("ZIP 条目解压体积超限")
            }
            return output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private companion object {
        const val CENTRAL_HEADER_BYTES = 46
        const val LOCAL_HEADER_BYTES = 30
        const val CENTRAL_HEADER_SIGNATURE = 0x02014B50L
        const val LOCAL_HEADER_SIGNATURE = 0x04034B50L
        const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054B50L
        const val END_OF_CENTRAL_DIRECTORY_BYTES = 22
        const val ZIP64_LOCATOR_SIGNATURE = 0x07064B50L
        const val ZIP64_LOCATOR_BYTES = 20
        const val ZIP64_EOCD_SIGNATURE = 0x06064B50L
        const val ZIP64_EOCD_BYTES = 56
        const val ZIP64_EXTRA_ID = 0x0001
        const val ZIP64_MARKER = 0xFFFFFFFFL
        const val ZIP64_MARKER_16 = 0xFFFFL
        const val MAX_COMMENT_BYTES = 65535L
        const val MAX_CENTRAL_DIRECTORY_BYTES = 64L * 1024 * 1024
        const val FLAG_ENCRYPTED = 0x1
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8
    }
}

private fun u16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun u32(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)

private fun u64(bytes: ByteArray, offset: Int): Long =
    u32(bytes, offset) or (u32(bytes, offset + 4) shl 32)
