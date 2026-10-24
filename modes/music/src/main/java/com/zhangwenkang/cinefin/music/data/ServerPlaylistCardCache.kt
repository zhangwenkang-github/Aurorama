package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.repository.MetadataNamespace
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class CachedPlaylistCard(
    val id: UUID,
    val name: String,
    val songCount: Int?,
    val imageTag: String?,
)

internal data class CachedPlaylistCards(
    val cards: List<CachedPlaylistCard>,
    val savedAtMs: Long,
)

/**
 * W88-C：服务器歌单首屏的 24 小时卡片摘要缓存。
 *
 * 仅持久化 id / 名称 / 数量 / 主图 tag；歌单成员仍由 [MusicRepository.getPlaylistSongs] 每次读取服务器。 内存、磁盘和在途请求都按
 * server + user namespace 隔离。过期刷新失败时保留旧摘要。
 */
internal class ServerPlaylistCardCache(
    private val readSnapshot: (MetadataNamespace, String, Long) -> CachedPlaylistCards?,
    private val writeSnapshot: (MetadataNamespace, String, List<CachedPlaylistCard>, Long) -> Unit,
    private val deleteSnapshot: (MetadataNamespace, String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private data class ScopedKey(val namespace: MetadataNamespace, val logicalKey: String)

    private data class FlightEntry(
        val deferred: CompletableDeferred<List<CachedPlaylistCard>>,
        val owner: Boolean,
        val prior: CachedPlaylistCards? = null,
        val versionAtStart: Long = 0L,
    )

    private val memory = ConcurrentHashMap<ScopedKey, CachedPlaylistCards>()
    private val flights =
        ConcurrentHashMap<ScopedKey, CompletableDeferred<List<CachedPlaylistCard>>>()
    private val locks = ConcurrentHashMap<ScopedKey, Any>()
    private val versions = ConcurrentHashMap<ScopedKey, AtomicLong>()

    suspend fun load(
        namespace: MetadataNamespace,
        key: String,
        isCurrent: () -> Boolean,
        forceRefresh: Boolean = false,
        fetch: suspend () -> List<CachedPlaylistCard>,
    ): List<CachedPlaylistCard> {
        val scopedKey = ScopedKey(namespace, key)
        val version = versionFor(scopedKey)
        val entry =
            synchronized(lockFor(scopedKey)) {
                val existing = flights[scopedKey]
                if (existing != null) {
                    FlightEntry(deferred = existing, owner = false)
                } else {
                    val now = clock()
                    val memorySnapshot = memory[scopedKey]
                    if (
                        !forceRefresh &&
                            memorySnapshot != null &&
                            isFresh(memorySnapshot.savedAtMs, now)
                    ) {
                        return memorySnapshot.cards
                    }
                    val prior =
                        memorySnapshot
                            ?: runCatching { readSnapshot(namespace, key, TTL_MS) }
                                .getOrNull()
                                ?.also { memory[scopedKey] = it }
                    if (!forceRefresh && prior != null && isFresh(prior.savedAtMs, now)) {
                        return prior.cards
                    }
                    val deferred = CompletableDeferred<List<CachedPlaylistCard>>()
                    flights[scopedKey] = deferred
                    FlightEntry(
                        deferred = deferred,
                        owner = true,
                        prior = prior,
                        versionAtStart = version.get(),
                    )
                }
            }
        if (!entry.owner) {
            return try {
                entry.deferred.await()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                val fallback = memory[scopedKey]?.takeIf { isCurrent() }
                if (fallback != null) fallback.cards else throw error
            }
        }

        return try {
            val fresh = fetch()
            synchronized(lockFor(scopedKey)) {
                if (!isCurrent()) {
                    throw CancellationException("server playlist namespace changed before commit")
                }
                if (version.get() != entry.versionAtStart) {
                    throw CancellationException(
                        "server playlist cache was invalidated before commit"
                    )
                }
                val savedAt = clock()
                runCatching { writeSnapshot(namespace, key, fresh, savedAt) }
                memory[scopedKey] = CachedPlaylistCards(fresh, savedAt)
            }
            entry.deferred.complete(fresh)
            fresh
        } catch (cancellation: CancellationException) {
            entry.deferred.completeExceptionally(cancellation)
            throw cancellation
        } catch (error: Throwable) {
            val fallback =
                entry.prior?.takeIf { isCurrent() && version.get() == entry.versionAtStart }
            if (fallback != null) {
                entry.deferred.complete(fallback.cards)
                fallback.cards
            } else {
                entry.deferred.completeExceptionally(error)
                throw error
            }
        } finally {
            flights.remove(scopedKey, entry.deferred)
        }
    }

    fun invalidate(namespace: MetadataNamespace, key: String) {
        val scopedKey = ScopedKey(namespace, key)
        synchronized(lockFor(scopedKey)) {
            versionFor(scopedKey).incrementAndGet()
            memory.remove(scopedKey)
            flights.remove(scopedKey)
            runCatching { deleteSnapshot(namespace, key) }
        }
    }

    private fun lockFor(key: ScopedKey): Any = locks.computeIfAbsent(key) { Any() }

    private fun versionFor(key: ScopedKey): AtomicLong =
        versions.computeIfAbsent(key) { AtomicLong() }

    companion object {
        const val TTL_MS: Long = 24L * 60L * 60L * 1000L

        private fun isFresh(savedAtMs: Long, nowMs: Long): Boolean =
            nowMs >= savedAtMs && nowMs - savedAtMs < TTL_MS

        fun encodeCards(cards: List<CachedPlaylistCard>): String = buildJsonArray {
            cards.forEach { card ->
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(card.id.toString()))
                        put("name", JsonPrimitive(card.name))
                        card.songCount?.let { put("songCount", JsonPrimitive(it)) }
                        card.imageTag?.let { put("imageTag", JsonPrimitive(it)) }
                    }
                )
            }
        }
            .toString()

        fun decodeCards(raw: String?): List<CachedPlaylistCard>? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val array = kotlinx.serialization.json.Json.parseToJsonElement(raw) as JsonArray
                array.map { element ->
                    val objectValue = element as JsonObject
                    CachedPlaylistCard(
                        id = UUID.fromString(objectValue.requiredString("id")),
                        name = objectValue.requiredString("name"),
                        songCount =
                            objectValue["songCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                        imageTag =
                            objectValue["imageTag"]?.jsonPrimitive?.contentOrNull?.takeIf {
                                it.isNotBlank()
                            },
                    )
                }
            }
                .getOrNull()
        }

        private fun JsonObject.requiredString(key: String): String =
            this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: error("missing $key")
    }
}
