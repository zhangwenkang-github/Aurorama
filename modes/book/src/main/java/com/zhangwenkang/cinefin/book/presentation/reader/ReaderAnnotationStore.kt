package com.zhangwenkang.cinefin.book.presentation.reader

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 批注本地存储：`filesDir/reader/annotations/{itemId}.json`（一个 itemId 一个文件）。
 *
 * 与进度 / 书签同一套原子写策略：先写 `.tmp` 再 rename，读失败回退为空列表；不写服务器、不用 Room （READER_PLAN §2 D23）。
 *
 * [storageDir] 以目录注入（而不是直接吃 `Context`），单测用临时目录即可覆盖读写 / 删除 / 脏文件。
 */
internal class ReaderAnnotationStore(private val storageDir: File) {
    private val mutex = Mutex()

    fun storageFile(itemId: String): File = File(storageDir, "$itemId.json")

    suspend fun list(itemId: String): List<ReaderAnnotation> =
        withContext(Dispatchers.IO) { mutex.withLock { readFile(itemId) } }

    /** 新增 / 覆盖（按 id 去重），返回写入后的完整列表。 */
    suspend fun save(annotation: ReaderAnnotation): List<ReaderAnnotation> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val items =
                    readFile(annotation.itemId).filterNot { it.id == annotation.id } + annotation
                val sorted = items.sortedForReading()
                writeFile(annotation.itemId, sorted)
                sorted
            }
        }

    /** 只更新备注（不动位置 / 页码），返回写入后的完整列表。 */
    suspend fun updateNote(
        itemId: String,
        annotationId: String,
        note: String,
        nowMs: Long = System.currentTimeMillis(),
    ): List<ReaderAnnotation> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val items =
                    readFile(itemId).map { annotation ->
                        if (annotation.id == annotationId) {
                            annotation.copy(
                                note = sanitizeAnnotationNote(note),
                                updatedAtMs = nowMs,
                            )
                        } else {
                            annotation
                        }
                    }
                val sorted = items.sortedForReading()
                writeFile(itemId, sorted)
                sorted
            }
        }

    suspend fun delete(itemId: String, annotationId: String): List<ReaderAnnotation> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val remaining =
                    readFile(itemId).filterNot { it.id == annotationId }.sortedForReading()
                writeFile(itemId, remaining)
                remaining
            }
        }

    private fun readFile(itemId: String): List<ReaderAnnotation> {
        val file = storageFile(itemId)
        if (!file.isFile) return emptyList()
        return runCatching { ReaderAnnotationCodec.decode(itemId, file.readText()) }
            .getOrElse { emptyList() }
    }

    private fun writeFile(itemId: String, annotations: List<ReaderAnnotation>) {
        val file = storageFile(itemId)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(ReaderAnnotationCodec.encode(itemId, annotations))
        if (!tmp.renameTo(file)) {
            // 极端情况下 rename 失败（同目录一般不会）：退化成覆盖写，保证不留下半截文件。
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
