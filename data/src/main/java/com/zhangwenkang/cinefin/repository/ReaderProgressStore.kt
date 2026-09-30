package com.zhangwenkang.cinefin.repository

import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * W1 的轻量本地进度存储：仅应用私有文件，不含任何服务端凭据。
 *
 * 之所以暂不使用 Room：W1/R2 会并行修改 schema；W3-R1 再做带迁移的 Room 落地， 避免两条任务线争抢数据库版本号（PARALLEL_PLAN §1.3）。
 */
class ReaderProgressStore(
    private val storageFile: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val mutex = Mutex()

    suspend fun load(itemId: UUID): ReadingProgress? =
        withContext(Dispatchers.IO) {
            mutex
                .withLock { readFile().items.firstOrNull { it.itemId == itemId.toString() } }
                ?.toProgress()
        }

    suspend fun save(progress: ReadingProgress) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val stored = progress.toStored()
                val items = readFile().items.filterNot { it.itemId == stored.itemId } + stored
                writeFile(StoredProgressFile(items))
            }
        }
    }

    suspend fun markSynced(itemId: UUID) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val items =
                    readFile().items.map {
                        if (it.itemId == itemId.toString()) it.copy(pendingSync = false) else it
                    }
                writeFile(StoredProgressFile(items))
            }
        }
    }

    suspend fun remove(itemId: UUID) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val items = readFile().items.filterNot { it.itemId == itemId.toString() }
                writeFile(StoredProgressFile(items))
            }
        }
    }

    suspend fun pending(): List<ReadingProgress> =
        withContext(Dispatchers.IO) {
            mutex.withLock { readFile().items.filter { it.pendingSync }.map { it.toProgress() } }
        }

    private fun readFile(): StoredProgressFile {
        if (!storageFile.isFile) return StoredProgressFile()
        return runCatching {
            json.decodeFromString<StoredProgressFile>(storageFile.readText())
        }
            .getOrElse { StoredProgressFile() }
    }

    private fun writeFile(file: StoredProgressFile) {
        storageFile.parentFile?.mkdirs()
        val tmp = File(storageFile.parentFile, "${storageFile.name}.tmp")
        tmp.writeText(json.encodeToString(file))
        if (!tmp.renameTo(storageFile)) {
            storageFile.writeText(tmp.readText())
            tmp.delete()
        }
    }
}

@Serializable
private data class StoredProgressFile(val items: List<StoredReadingProgress> = emptyList())

@Serializable
private data class StoredReadingProgress(
    val itemId: String,
    val locatorJson: String,
    val progression: Double,
    val positionTicks: Long,
    val updatedAtEpochMillis: Long,
    val pendingSync: Boolean,
) {
    fun toProgress(): ReadingProgress =
        ReadingProgress(
            itemId = UUID.fromString(itemId),
            locatorJson = locatorJson,
            progression = progression,
            positionTicks = positionTicks,
            updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
            pendingSync = pendingSync,
        )
}

private fun ReadingProgress.toStored(): StoredReadingProgress =
    StoredReadingProgress(
        itemId = itemId.toString(),
        locatorJson = locatorJson,
        progression = progression,
        positionTicks = positionTicks,
        updatedAtEpochMillis = updatedAt.toEpochMilli(),
        pendingSync = pendingSync,
    )
