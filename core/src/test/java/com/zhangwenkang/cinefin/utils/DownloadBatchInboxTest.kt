package com.zhangwenkang.cinefin.utils

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** W84：音乐批量点击先一次性受理全量意图，重复点击幂等。 */
class DownloadBatchInboxTest {

    private class MemoryIntentStore : DownloadIntentStore {
        val intents = mutableListOf<DownloadMusicIntentRequest>()
        private val saveAllCallCounter = AtomicInteger(0)
        val saveAllCalls: Int
            get() = saveAllCallCounter.get()

        override suspend fun existingCanonicalKeys(
            requests: List<DownloadMusicIntentRequest>
        ): Set<String> {
            delay(10)
            return requests
                .filter { request ->
                    request.canonicalIdentity.hasCompleteNamespace &&
                        intents.any {
                            it.canonicalIdentity.matchesProvenance(request.canonicalIdentity)
                        }
                }
                .mapNotNullTo(mutableSetOf()) { it.canonicalIdentity.foldKey }
        }

        override suspend fun saveAll(requests: List<DownloadMusicIntentRequest>) {
            saveAllCallCounter.incrementAndGet()
            requests.forEach { request ->
                val key = request.canonicalIdentity.foldKey
                if (key == null || intents.none { it.canonicalIdentity.foldKey == key }) {
                    intents += request
                }
            }
        }
    }

    private fun request(
        index: Int,
        serverId: String? = "server-a",
        userId: String? = "user-a",
    ) =
        DownloadMusicIntentRequest(
            itemId = UUID(0L, index.toLong()),
            name = "曲目 $index",
            albumName = "专辑",
            artist = "歌手",
            trackIndex = index,
            serverId = serverId,
            userId = userId,
        )

    @Test
    fun `首次受理单次持久化全部一百项并记录时钟`() {
        val store = MemoryIntentStore()
        val inbox = DownloadBatchInbox(store = store, clock = { 12_345L })

        val receipt = kotlinx.coroutines.runBlocking { inbox.accept((1..100).map(::request)) }

        assertEquals(100, receipt.acceptedIds.size)
        assertEquals(0, receipt.alreadyAcceptedCount)
        assertEquals(12_345L, receipt.acceptedAtMs)
        assertEquals(1, store.saveAllCalls)
        assertEquals(100, store.intents.size)
    }

    @Test
    fun `重复受理同一批不重复写入`() {
        val store = MemoryIntentStore()
        val requests = (1..100).map(::request)
        kotlinx.coroutines.runBlocking {
            DownloadBatchInbox(store) { 1L }.accept(requests)
            val second = DownloadBatchInbox(store) { 2L }.accept(requests)

            assertEquals(0, second.acceptedIds.size)
            assertEquals(100, second.alreadyAcceptedCount)
        }

        assertEquals(1, store.saveAllCalls)
        assertEquals(100, store.intents.size)
    }

    @Test
    fun `并发受理同一批只执行一次批量写入`() =
        runBlocking(Dispatchers.Default) {
            val store = MemoryIntentStore()
            val requests = (1..100).map(::request)
            val inbox = DownloadBatchInbox(store) { 1L }

            val receipts = (1..10).map { async { inbox.accept(requests) } }.awaitAll()

            assertEquals(1, store.saveAllCalls)
            assertEquals(100, store.intents.size)
            assertEquals(100, receipts.sumOf { it.acceptedIds.size })
            assertEquals(900, receipts.sumOf { it.alreadyAcceptedCount })
        }

    @Test
    fun `同 UUID 不同 server 的意图按 canonical identity 分别受理`() = runBlocking {
        val store = MemoryIntentStore()
        val itemId = UUID(0L, 7L)
        val receipt =
            DownloadBatchInbox(store)
                .accept(
                    listOf(
                        request(7, "server-a", "user-a").copy(itemId = itemId),
                        request(7, "server-b", "user-a").copy(itemId = itemId),
                    )
                )

        assertEquals(2, receipt.acceptedKeys.size)
        assertEquals(2, store.intents.size)
    }

    @Test
    fun `同 UUID 的未知意图逐条受理且不互相覆盖`() = runBlocking {
        val store = MemoryIntentStore()
        val itemId = UUID(0L, 9L)
        val receipt =
            DownloadBatchInbox(store)
                .accept(
                    listOf(
                        request(9, serverId = null, userId = null)
                            .copy(
                                itemId = itemId,
                                name = "A",
                            ),
                        request(9, serverId = null, userId = null)
                            .copy(
                                itemId = itemId,
                                name = "B",
                            ),
                    )
                )

        assertEquals(2, receipt.acceptedIds.size)
        assertEquals(listOf("A", "B"), store.intents.map { it.name })
    }
}
