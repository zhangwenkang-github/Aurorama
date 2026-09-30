package com.zhangwenkang.cinefin.repository

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 书签本地存储：应用私有 JSON 文件（`filesDir/reader/bookmarks.json`）。
 *
 * 与 `ReaderProgressStore` 同一套原子写策略：先写 `.tmp` 再 rename，读失败时回退为空列表， 不让脏文件阻塞阅读页。
 */
class ReaderBookmarkStore(private val storageFile: File) {
    private val mutex = Mutex()

    suspend fun list(itemId: UUID): List<ReaderBookmark> =
        withContext(Dispatchers.IO) {
            mutex.withLock { readFile().filter { it.itemId == itemId }.sortedForReading() }
        }

    suspend fun save(bookmark: ReaderBookmark) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val items = readFile().filterNot { it.id == bookmark.id } + bookmark
                writeFile(items.sortedForReading())
            }
        }
    }

    suspend fun delete(itemId: UUID, bookmarkId: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                writeFile(readFile().filterNot { it.itemId == itemId && it.id == bookmarkId })
            }
        }
    }

    suspend fun deleteAll(itemId: UUID) {
        withContext(Dispatchers.IO) {
            mutex.withLock { writeFile(readFile().filterNot { it.itemId == itemId }) }
        }
    }

    private fun readFile(): List<ReaderBookmark> {
        if (!storageFile.isFile) return emptyList()
        return runCatching { decodeReaderBookmarks(storageFile.readText()) }
            .getOrElse { emptyList() }
    }

    private fun writeFile(bookmarks: List<ReaderBookmark>) {
        storageFile.parentFile?.mkdirs()
        val tmp = File(storageFile.parentFile, "${storageFile.name}.tmp")
        tmp.writeText(encodeReaderBookmarks(bookmarks))
        if (!tmp.renameTo(storageFile)) {
            storageFile.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
