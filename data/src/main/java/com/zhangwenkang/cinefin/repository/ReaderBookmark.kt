package com.zhangwenkang.cinefin.repository

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 阅读书签（EB-8 基础版）。
 *
 * 服务端没有标准批注 API（ARCHITECTURE §3.6），因此书签只存本地：`locatorJson` 是 Readium Locator， 用于精确跳回原位置。
 */
data class ReaderBookmark(
    val id: String,
    val itemId: UUID,
    val locatorJson: String,
    val progression: Double,
    val label: String,
    val createdAt: Instant,
)

/** 书签按进度升序、同进度按创建时间升序排列（阅读页列表与单测共用）。 */
fun List<ReaderBookmark>.sortedForReading(): List<ReaderBookmark> =
    sortedWith(compareBy({ normalizedProgression(it.progression) }, { it.createdAt }, { it.id }))

private val bookmarkJson = Json { ignoreUnknownKeys = true }

/** 书签文件序列化（纯函数，便于 JVM 单测覆盖序列化 / 反序列化 / 脏数据回退）。 */
fun encodeReaderBookmarks(bookmarks: List<ReaderBookmark>): String =
    bookmarkJson.encodeToString(StoredBookmarks(bookmarks.map { it.toStored() }))

fun decodeReaderBookmarks(text: String): List<ReaderBookmark> {
    if (text.isBlank()) return emptyList()
    return runCatching {
        bookmarkJson.decodeFromString<StoredBookmarks>(text).items.mapNotNull {
            it.toBookmarkOrNull()
        }
    }
        .getOrElse { emptyList() }
}

@Serializable private data class StoredBookmarks(val items: List<StoredBookmark> = emptyList())

@Serializable
private data class StoredBookmark(
    val id: String,
    val itemId: String,
    val locatorJson: String,
    val progression: Double,
    val label: String,
    val createdAtEpochMillis: Long,
) {
    fun toBookmarkOrNull(): ReaderBookmark? {
        val uuid = runCatching { UUID.fromString(itemId) }.getOrNull() ?: return null
        return ReaderBookmark(
            id = id,
            itemId = uuid,
            locatorJson = locatorJson,
            progression = normalizedProgression(progression),
            label = label,
            createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
        )
    }
}

private fun ReaderBookmark.toStored(): StoredBookmark =
    StoredBookmark(
        id = id,
        itemId = itemId.toString(),
        locatorJson = locatorJson,
        progression = normalizedProgression(progression),
        label = label,
        createdAtEpochMillis = createdAt.toEpochMilli(),
    )
