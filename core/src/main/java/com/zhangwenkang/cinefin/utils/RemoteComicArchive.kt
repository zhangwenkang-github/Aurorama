package com.zhangwenkang.cinefin.utils

import java.io.Closeable
import java.io.File
import java.io.IOException
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * W77：远端读取的「在途请求登记」钩子（core 侧只依赖接口，不感知阅读页 / 协程）。
 *
 * 生产封面路径不登记；阅读页把 [HttpByteSource] 的每个在途 [Call] 登记进来，离开阅读页时 `cancelAll()` 确定性中止已在读响应体的阻塞请求（与 W64 /
 * W77-1 同口径的最小兜底）。
 */
interface HttpCallRegistry {
    fun register(call: Call)

    fun unregister(call: Call)
}

/**
 * W77 阅读流式：远端 CBZ 归档门面（只读）。
 *
 * 复用已上生产的 [HttpByteSource]（HEAD 容错 + 256 KB 分块 + LRU）与 [ZipArchiveReader]（中央目录 + 条目级解出）： 只读取「尾部
 * EOCD + 中央目录 + 目标条目」的字节区间，**不整本下载**；本地已下载文件走 [openFile]。
 *
 * 与 `BookCoverProvider` 的封面路径同源，但按「页」调整了入口：暴露条目名 / 未压缩体积 / 按名读取，并可选把在途请求登记给 [HttpCallRegistry]
 * 以便取消。
 */
class RemoteComicArchive
private constructor(
    private val archive: ZipArchiveReader,
    private val source: ByteSource,
) : Closeable {

    /** 归档条目（名 / 体积 / 压缩方式）；页序过滤与排序由调用方（阅读模块）负责。 */
    data class Entry(
        val name: String,
        val uncompressedSize: Long,
        val compressedSize: Long,
        val method: Int,
    )

    private val byName: Map<String, ZipArchiveReader.Entry> =
        archive.entries.associateBy { it.name }

    val entries: List<Entry> =
        archive.entries.map { Entry(it.name, it.uncompressedSize, it.compressedSize, it.method) }

    fun contains(name: String): Boolean = byName.containsKey(name)

    /** 条目的未压缩体积（页图按此做体积上限判断）；不存在返回 null。 */
    fun size(name: String): Long? = byName[name]?.uncompressedSize

    /**
     * 读文件头部（用于远端格式嗅探，顺带触发一次长度探测 + 首块入缓存）。
     *
     * 只读 `[0, maxBytes)`，越界自动截断；与后续页读取共用同一分块缓存，不产生重复请求。
     */
    fun readHead(maxBytes: Int): ByteArray = source.readAt(0, maxBytes)

    /** 解出单个条目字节（体积超上限 / 加密 / 不支持的压缩方式直接抛 [IOException]）。 */
    fun readBytes(name: String, maxBytes: Long): ByteArray {
        val entry = byName[name] ?: throw IOException("ZIP 条目不存在：$name")
        return archive.readBytes(entry, maxBytes)
    }

    override fun close() {
        runCatching { archive.close() }
    }

    companion object {
        /**
         * 打开远端归档（`GET /Items/{id}/Download`，支持 Range）。
         *
         * [knownSize] 已知时跳过一次长度探测（真机：单次 Range 请求 1.4–20 s，「请求次数」是第一成本）。 [callRegistry]
         * 非空时登记每个在途请求，供离开阅读页时取消。
         */
        fun openRemote(
            client: OkHttpClient,
            url: String,
            headers: Map<String, String> = emptyMap(),
            knownSize: Long? = null,
            callRegistry: HttpCallRegistry? = null,
        ): RemoteComicArchive {
            val source =
                HttpByteSource(
                    client = client,
                    url = url,
                    headers = headers,
                    knownSize = knownSize,
                    callRegistry = callRegistry,
                )
            return RemoteComicArchive(ZipArchiveReader(source), source)
        }

        /** 打开本地已下载归档（`files/books/<itemId>.book`）。 */
        fun openFile(file: File): RemoteComicArchive {
            val source = FileByteSource(file)
            return RemoteComicArchive(ZipArchiveReader(source), source)
        }
    }
}
