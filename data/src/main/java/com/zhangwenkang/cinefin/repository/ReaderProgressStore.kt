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
 * 轻量本地进度存储：仅应用私有文件，不含任何服务端凭据。
 *
 * 之所以暂不使用 Room：W1/R2 会并行修改 schema；W3 时 R2-LYRICS 正在占用数据库版本号， 本波继续沿用文件存储，Room 迁移方案见 READER_PLAN 决策
 * D11（PARALLEL_PLAN §1.3）。
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

    /**
     * 仅当本地记录仍是回传的那一条时才清掉待同步标记。
     *
     * 回传是异步的：清标记前用户可能又翻了几页，此时新记录必须继续保留 `pendingSync`。
     */
    suspend fun markSyncedIfUnchanged(itemId: UUID, updatedAt: Instant) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val target = updatedAt.toEpochMilli()
                val items =
                    readFile().items.map {
                        if (
                            it.itemId == itemId.toString() &&
                                it.pendingSync &&
                                it.updatedAtEpochMillis == target
                        ) {
                            it.copy(pendingSync = false)
                        } else {
                            it
                        }
                    }
                writeFile(StoredProgressFile(items))
            }
        }
    }

    /** 缓存书目的 `RunTimeTicks`，离线时也能算出正确的 `positionTicks`。 */
    suspend fun setRuntimeTicks(itemId: UUID, runtimeTicks: Long) {
        if (runtimeTicks <= 0) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val items =
                    readFile().items.map {
                        if (it.itemId == itemId.toString() && it.runtimeTicks != runtimeTicks) {
                            it.copy(runtimeTicks = runtimeTicks)
                        } else {
                            it
                        }
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

    suspend fun pendingCount(): Int =
        withContext(Dispatchers.IO) { mutex.withLock { readFile().items.count { it.pendingSync } } }

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
    val runtimeTicks: Long = 0L,
) {
    fun toProgress(): ReadingProgress =
        ReadingProgress(
            itemId = UUID.fromString(itemId),
            locatorJson = locatorJson,
            progression = progression,
            positionTicks = positionTicks,
            updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
            pendingSync = pendingSync,
            runtimeTicks = runtimeTicks,
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
        runtimeTicks = runtimeTicks,
    )
