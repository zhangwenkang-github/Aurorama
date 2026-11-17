package com.zhangwenkang.cinefin.utils

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDeleteTraceTest {

    @Test
    fun `disabled owner performs zero identity clock registry and sink work`() {
        var identityCalls = 0
        var clockCalls = 0
        val lines = mutableListOf<String>()
        val owner =
            DownloadDeleteTraceOwner.createForTest(
                enabled = { false },
                sink = lines::add,
                monotonicMs = {
                    clockCalls += 1
                    clockCalls.toLong()
                },
                hmacKey = testKey(),
                rootNonce = "TEST",
            )

        val root =
            owner.start(
                listOf(
                    request("leaf", "source-1") {
                        identityCalls += 1
                        identity("source-1")
                    }
                )
            )

        assertTrue(root.handles.isEmpty())
        assertEquals(0, identityCalls)
        assertEquals(0, clockCalls)
        assertTrue(lines.isEmpty())
        assertEquals(null, owner.rowTrace("leaf"))
    }

    @Test
    fun `normal lifecycle emits fixed safe monotonic D1 through D9 order`() {
        var now = 1_000L
        val lines = mutableListOf<String>()
        val frames = RecordingFrameScheduler()
        val owner =
            DownloadDeleteTraceOwner.createForTest(
                enabled = { true },
                sink = lines::add,
                monotonicMs = { now.also { now += 7L } },
                hmacKey = testKey(),
                rootNonce = "TEST",
                frameScheduler = frames,
            )
        val root =
            owner.start(
                listOf(
                    request("leaf-1", "source-1") {
                        identity(
                            sourceId = "source-1",
                            serverId = "server-secret",
                            userId = "user-secret",
                            itemId = "item-secret",
                        )
                    }
                )
            )
        val handle = root.handles.single()
        val row = requireNotNull(owner.rowTrace("leaf-1"))
        row.attached()

        handle.record(event(DownloadDeleteTracePhase.D2_PENDING_PROJECTED))
        handle.bindSource("source-1", 7L)
        handle.record(
            event(
                DownloadDeleteTracePhase.D3_ROOM_DELETING_COMMITTED,
                sourceId = "source-1",
                generation = 7L,
            )
        )
        handle.record(
            event(
                DownloadDeleteTracePhase.D4_JOIN_END,
                result = DownloadDeleteTraceResult.SKIPPED,
                sourceId = "source-1",
                generation = 7L,
            )
        )
        handle.record(
            event(
                DownloadDeleteTracePhase.D5_FS_PRE_BEGIN,
                sourceId = "source-1",
                generation = 7L,
            )
        )
        handle.record(
            event(
                DownloadDeleteTracePhase.D5_FS_PRE_BEGIN,
                sourceId = "source-1",
                generation = 7L,
                category = DownloadDeleteTraceCategory.PARTIAL,
            )
        )
        handle.record(
            event(
                DownloadDeleteTracePhase.D5_FS_PRE_END,
                sourceId = "source-1",
                generation = 7L,
                category = DownloadDeleteTraceCategory.PARTIAL,
            )
        )
        handle.record(
            event(
                DownloadDeleteTracePhase.D6_SOURCE_MEDIA_ROWS_REMOVED,
                result = DownloadDeleteTraceResult.REMOVED,
                sourceId = "source-1",
                generation = 7L,
            )
        )
        handle.record(
            event(
                DownloadDeleteTracePhase.D5_FS_POST_ROW_BEGIN,
                sourceId = "source-1",
                generation = 7L,
                category = DownloadDeleteTraceCategory.IMAGES,
            )
        )
        handle.record(
            event(
                DownloadDeleteTracePhase.D5_FS_POST_ROW_END,
                result = DownloadDeleteTraceResult.DONE,
                sourceId = "source-1",
                generation = 7L,
                category = DownloadDeleteTraceCategory.IMAGES,
            )
        )
        handle.record(
            event(
                DownloadDeleteTracePhase.D5_FS_END,
                sourceId = "source-1",
                generation = 7L,
                durationMs = 21L,
            )
        )
        handle.record(
            event(
                DownloadDeleteTracePhase.D7_RUNTIME_REMOVED,
                sourceId = "source-1",
                generation = 7L,
            )
        )
        handle.record(
            event(
                DownloadDeleteTracePhase.D7_NOTIFICATION_DECISION,
                result = DownloadDeleteTraceResult.SCHEDULED,
                sourceId = "source-1",
                generation = 7L,
            )
        )
        handle.record(
            event(
                DownloadDeleteTracePhase.D7_QUEUE_SIGNAL,
                sourceId = "source-1",
                generation = 7L,
            )
        )

        val refresh =
            owner.beginCanonicalRefresh(DownloadDeleteTraceTrigger.QUEUE)
                ?: error("enabled owner must create a refresh")
        refresh.publish(emptySet())
        handle.pendingCleared()
        row.disposed()
        frames.runNext()

        val trace = lines.filter { it.startsWith(TRACE_PREFIX) }
        val phases = trace.map { line ->
            requireNotNull(Regex("""\bphase=([A-Z0-9_]+)""").find(line)?.groupValues?.get(1))
        }
        assertEquals(
            listOf(
                "D1_REQUEST_ACCEPTED",
                "D2_PENDING_PROJECTED",
                "D3_ROOM_DELETING_COMMITTED",
                "D4_JOIN_END",
                "D5_FS_PRE_BEGIN",
                "D5_FS_PRE_BEGIN",
                "D5_FS_PRE_END",
                "D6_SOURCE_MEDIA_ROWS_REMOVED",
                "D5_FS_POST_ROW_BEGIN",
                "D5_FS_POST_ROW_END",
                "D5_FS_END",
                "D7_RUNTIME_REMOVED",
                "D7_NOTIFICATION_DECISION",
                "D7_QUEUE_SIGNAL",
                "D8_CANONICAL_REFRESH_BEGIN",
                "D8_CANONICAL_REFRESH_END",
                "D9_PENDING_CLEARED",
                "D9_ROW_ABSENT_FRAME",
            ),
            phases,
        )
        val joined = trace.joinToString(separator = "\n")
        assertTrue(
            "Every row must carry the fixed schema: $joined",
            trace.all { it.matches(TRACE_LINE_PATTERN) },
        )
        assertTrue(joined.contains("src=source-1 gen=7"))
        assertTrue(joined.contains("media="))
        assertFalse(joined.contains("server-secret"))
        assertFalse(joined.contains("user-secret"))
        assertFalse(joined.contains("item-secret"))
        assertTrue(joined.contains("canonical=false"))
        assertTrue(joined.contains("durationMs=21") || joined.contains("dur_ms=21"))
        assertTrue(joined.contains("category=IMAGES"))
        assertTrue(joined.contains("trigger=QUEUE"))
    }

    @Test
    fun `duplicate trigger emits one rejection and never hashes the duplicate identity`() {
        val lines = mutableListOf<String>()
        var firstIdentityCalls = 0
        var duplicateIdentityCalls = 0
        val owner =
            DownloadDeleteTraceOwner.createForTest(
                enabled = { true },
                sink = lines::add,
                monotonicMs = { 1L },
                hmacKey = testKey(),
                rootNonce = "TEST",
            )

        val first =
            owner.start(
                listOf(
                    request("leaf", "source-1") {
                        firstIdentityCalls += 1
                        identity("source-1")
                    }
                )
            )
        val duplicate =
            owner.start(
                listOf(
                    request("leaf", "source-1") {
                        duplicateIdentityCalls += 1
                        identity("source-1")
                    }
                )
            )

        assertEquals(1, first.handles.size)
        assertTrue(duplicate.handles.isEmpty())
        assertEquals(1, firstIdentityCalls)
        assertEquals(0, duplicateIdentityCalls)
        assertEquals(
            1,
            lines.count {
                it.contains("phase=D1_DUPLICATE_REJECTED") &&
                    it.contains("result=REJECTED_DUPLICATE")
            },
        )
    }

    @Test
    fun `generation drift drops old events and closes without fabricating D4 through D9`() {
        val lines = mutableListOf<String>()
        val owner =
            DownloadDeleteTraceOwner.createForTest(
                enabled = { true },
                sink = lines::add,
                monotonicMs = { 1L },
                hmacKey = testKey(),
                rootNonce = "TEST",
            )
        val handle =
            owner
                .start(listOf(request("leaf", "source-1") { identity("source-1") }))
                .handles
                .single()
        handle.bindSource("source-1", 7L)

        handle.record(
            event(
                DownloadDeleteTracePhase.D4_JOIN_BEGIN,
                sourceId = "source-1",
                generation = 6L,
            )
        )
        handle.record(
            event(
                DownloadDeleteTracePhase.D3_ROOM_DELETING_COMMITTED,
                result = DownloadDeleteTraceResult.SUPERSEDED,
                error = DownloadDeleteTraceError.CAS,
                sourceId = "source-1",
                generation = 6L,
            )
        )
        handle.record(
            event(
                DownloadDeleteTracePhase.D4_JOIN_BEGIN,
                sourceId = "source-1",
                generation = 7L,
            )
        )

        val trace = lines.filter { it.startsWith(TRACE_PREFIX) }
        assertFalse(trace.any { it.contains("gen=6") })
        assertFalse(trace.any { it.contains("phase=D4_JOIN_BEGIN") })
    }

    @Test
    fun `event cap emits one capped marker and stops the session`() {
        val lines = mutableListOf<String>()
        val owner =
            DownloadDeleteTraceOwner.createForTest(
                enabled = { true },
                sink = lines::add,
                monotonicMs = { 1L },
                hmacKey = testKey(),
                rootNonce = "TEST",
            )
        val handle =
            owner
                .start(listOf(request("leaf", "source-1") { identity("source-1") }))
                .handles
                .single()
        handle.bindSource("source-1", 1L)
        val phases =
            listOf(
                DownloadDeleteTracePhase.D5_FS_PRE_BEGIN,
                DownloadDeleteTracePhase.D5_FS_PRE_END,
                DownloadDeleteTracePhase.D5_FS_POST_ROW_BEGIN,
                DownloadDeleteTracePhase.D5_FS_POST_ROW_END,
            )
        val categories = DownloadDeleteTraceCategory.entries

        for (category in categories) {
            for (phase in phases) {
                handle.record(
                    event(
                        phase = phase,
                        sourceId = "source-1",
                        generation = 1L,
                        category = category,
                    )
                )
            }
        }
        handle.record(
            event(
                DownloadDeleteTracePhase.D7_QUEUE_SIGNAL,
                sourceId = "source-1",
                generation = 1L,
            )
        )

        val trace = lines.filter { it.startsWith(TRACE_PREFIX) }
        assertEquals(1, trace.count { it.contains("phase=TRACE_CAPPED") })
        assertTrue("cap must stop all later events: ${trace.size}", trace.size <= 32)
        assertEquals("TRACE_CAPPED", trace.last().phase())
    }

    @Test
    fun `sink failure is swallowed and closes only the observer session`() {
        var sinkCalls = 0
        val owner =
            DownloadDeleteTraceOwner.createForTest(
                enabled = { true },
                sink = {
                    sinkCalls += 1
                    error("sink exploded")
                },
                monotonicMs = { 1L },
                hmacKey = testKey(),
                rootNonce = "TEST",
            )

        val root = owner.start(listOf(request("leaf", "source-1") { identity("source-1") }))
        val handle = root.handles.single()
        handle.bindSource("source-1", 1L)
        handle.record(
            event(
                DownloadDeleteTracePhase.D4_JOIN_END,
                sourceId = "source-1",
                generation = 1L,
            )
        )

        assertEquals(1, sinkCalls)
    }

    @Test
    fun `row absence is emitted only on the first frame after both disposal and pending clear`() {
        val lines = mutableListOf<String>()
        val frames = RecordingFrameScheduler()
        val owner =
            DownloadDeleteTraceOwner.createForTest(
                enabled = { true },
                sink = lines::add,
                monotonicMs = { 1L },
                hmacKey = testKey(),
                rootNonce = "TEST",
                frameScheduler = frames,
            )
        val handle =
            owner
                .start(listOf(request("leaf", "source-1") { identity("source-1") }))
                .handles
                .single()
        val row = requireNotNull(owner.rowTrace("leaf"))
        row.attached()

        handle.pendingCleared()
        assertFalse(frames.hasCallbacks())
        assertEquals(
            0,
            lines.count { it.contains("phase=D9_ROW_ABSENT_FRAME") },
        )

        row.disposed()
        assertEquals(
            0,
            lines.count { it.contains("phase=D9_ROW_ABSENT_FRAME") },
        )
        frames.runNext()
        assertEquals(
            1,
            lines.count { it.contains("phase=D9_ROW_ABSENT_FRAME") },
        )
        assertFalse(frames.hasCallbacks())
        assertEquals(
            1,
            lines.count { it.contains("phase=D9_ROW_ABSENT_FRAME") },
        )
    }

    private fun request(
        stableKey: String,
        sourceId: String,
        identity: () -> DownloadDeleteTraceIdentity,
    ) =
        DownloadDeleteTraceRequest(
            stableKey = stableKey,
            sourceId = sourceId,
            identity = identity,
        )

    private fun identity(
        sourceId: String,
        serverId: String = "server-a",
        userId: String = "user-a",
        itemId: String = "item-a",
    ) =
        DownloadDeleteTraceIdentity(
            mediaKind = DownloadMediaKind.VIDEO,
            serverId = serverId,
            userId = userId,
            itemId = itemId,
            sourceId = sourceId,
        )

    private fun event(
        phase: DownloadDeleteTracePhase,
        result: DownloadDeleteTraceResult = DownloadDeleteTraceResult.STARTED,
        error: DownloadDeleteTraceError = DownloadDeleteTraceError.NONE,
        sourceId: String? = null,
        generation: Long? = null,
        durationMs: Long? = null,
        category: DownloadDeleteTraceCategory? = null,
    ) =
        DownloadDeleteTraceEvent(
            phase = phase,
            result = result,
            error = error,
            sourceId = sourceId,
            generation = generation,
            durationMs = durationMs,
            category = category,
        )

    private fun testKey(): ByteArray = ByteArray(32) { index -> index.toByte() }

    private class RecordingFrameScheduler : DownloadDeleteTraceFrameScheduler {
        private val callbacks = ArrayDeque<() -> Unit>()

        override fun postFrame(callback: () -> Unit) {
            callbacks.addLast(callback)
        }

        fun runNext() {
            callbacks.removeFirst().invoke()
        }

        fun hasCallbacks(): Boolean = callbacks.isNotEmpty()
    }

    private fun String.phase(): String =
        requireNotNull(Regex("""\bphase=([A-Z0-9_]+)""").find(this)?.groupValues?.get(1))

    private companion object {
        const val TRACE_PREFIX = "AURORAMA_DELETE_TRACE"
        val TRACE_LINE_PATTERN =
            Regex(
                """AURORAMA_DELETE_TRACE v=1 rid=\S+ phase=[A-Z0-9_]+ t=\d+ """ +
                    """src=\S+ gen=\S+ media=[0-9a-f]{16} result=[A-Z0-9_]+ """ +
                    """error=[A-Z0-9_]+ canonical=(true|false)(?: .+)?"""
            )
    }
}
