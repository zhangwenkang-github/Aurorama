package com.zhangwenkang.cinefin.utils

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
        val intents = ConcurrentHashMap<UUID, DownloadMusicIntentRequest>()
        private val saveAllCallCounter = AtomicInteger(0)
        val saveAllCalls: Int
            get() = saveAllCallCounter.get()

        override suspend fun existingItemIds(itemIds: Set<UUID>): Set<UUID> {
            delay(10)
            return itemIds.filterTo(mutableSetOf()) { itemId -> intents.containsKey(itemId) }
        }

        override suspend fun saveAll(requests: List<DownloadMusicIntentRequest>) {
            saveAllCallCounter.incrementAndGet()
            requests.forEach { request -> intents.putIfAbsent(request.itemId, request) }
        }
    }

    private fun request(index: Int) =
        DownloadMusicIntentRequest(
            itemId = UUID(0L, index.toLong()),
            name = "曲目 $index",
            albumName = "专辑",
            artist = "歌手",
            trackIndex = index,
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
}
