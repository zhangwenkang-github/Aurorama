package com.zhangwenkang.cinefin.utils

import android.view.Choreographer
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class DownloadDeleteTraceOwner
private constructor(
    private val enabled: () -> Boolean,
    private val sink: (String) -> Unit,
    private val monotonicMs: () -> Long,
    private val hmacKey: ByteArray,
    private val rootNonce: String,
    private val frameScheduler: DownloadDeleteTraceFrameScheduler,
) : DownloadDeleteTracePort {
    @Inject
    constructor() :
        this(
            enabled = { Timber.treeCount > 0 },
            sink = { line -> Timber.tag(TRACE_TAG).d("%s", line) },
            monotonicMs = { System.nanoTime() / 1_000_000L },
            hmacKey = randomBytes(HMAC_KEY_BYTES),
            rootNonce = randomHex(REQUEST_NONCE_BYTES),
            frameScheduler =
                DownloadDeleteTraceFrameScheduler { callback ->
                    Choreographer.getInstance().postFrameCallback { callback() }
                },
        )

    private val requestSequence = AtomicLong()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val hmacLock = Any()

    override fun start(requests: List<DownloadDeleteTraceRequest>): DownloadDeleteTraceRoot {
        if (requests.isEmpty() || !isEnabled()) return DownloadDeleteTraceRoot.Empty
        pruneExpired()
        val rootRequestId = nextRootRequestId()
        val rootBudget = RootBudget()
        val accepted = mutableListOf<Session>()
        val sourceBindings = linkedMapOf<String, DownloadDeleteTraceHandle>()
        requests.forEachIndexed { ordinal, request ->
            val existing = sessions[request.stableKey]
            if (existing != null && !existing.isClosed()) {
                emitDuplicate(rootRequestId, ordinal, existing)
                return@forEachIndexed
            }
            val identity =
                runCatching(request.identity).getOrElse {
                    DownloadDeleteTraceIdentity(
                        mediaKind = DownloadMediaKind.VIDEO,
                        serverId = null,
                        userId = null,
                        itemId = UNKNOWN_VALUE,
                        sourceId = request.sourceId,
                    )
                }
            val media = opaqueIdentity(identity)
            val session =
                Session(
                    requestId = "$rootRequestId/$ordinal",
                    stableKey = request.stableKey,
                    media = media,
                    rootBudget = rootBudget,
                    createdMs = monotonicMs(),
                )
            val previous = sessions.putIfAbsent(request.stableKey, session)
            if (previous != null && !previous.isClosed()) {
                emitDuplicate(rootRequestId, ordinal, previous)
                return@forEachIndexed
            }
            accepted += session
            request.sourceId?.takeIf(String::isNotBlank)?.let { sourceId ->
                sourceBindings[sourceId] = session
            }
            session.record(
                DownloadDeleteTraceEvent(
                    phase = DownloadDeleteTracePhase.D1_REQUEST_ACCEPTED,
                    result = DownloadDeleteTraceResult.ACCEPTED,
                )
            )
        }
        return DownloadDeleteTraceRoot(accepted, sourceBindings)
    }

    override fun beginCanonicalRefresh(
        trigger: DownloadDeleteTraceTrigger
    ): DownloadDeleteTraceRefresh? {
        if (!isEnabled()) return null
        pruneExpired()
        val startedMs = monotonicMs()
        val targets =
            sessions.values.mapNotNull { session ->
                session.beginRefresh(trigger, startedMs)?.let { token ->
                    RefreshTarget(session, token)
                }
            }
        if (targets.isEmpty()) return null
        return Refresh(trigger, targets)
    }

    override fun rowTrace(stableKey: String): DownloadDeleteTraceRowHandle? {
        if (!isEnabled()) return null
        pruneExpired()
        val session = sessions[stableKey] ?: return null
        if (session.isClosed()) {
            sessions.remove(stableKey, session)
            return null
        }
        return RowHandle(session)
    }

    private fun nextRootRequestId(): String =
        "RID-$rootNonce-${requestSequence.incrementAndGet().toString().padStart(4, '0')}"

    private fun emitDuplicate(
        rootRequestId: String,
        ordinal: Int,
        existing: Session,
    ) {
        existing.emitStandalone(
            requestId = "$rootRequestId/$ordinal",
            event =
                DownloadDeleteTraceEvent(
                    phase = DownloadDeleteTracePhase.D1_DUPLICATE_REJECTED,
                    result = DownloadDeleteTraceResult.REJECTED_DUPLICATE,
                ),
            canonicalMember = true,
            media = existing.media,
        )
    }

    private fun opaqueIdentity(identity: DownloadDeleteTraceIdentity): String {
        val material = buildString {
            appendField(identity.mediaKind.name)
            appendField(identity.serverId.orEmpty())
            appendField(identity.userId.orEmpty())
            appendField(identity.itemId)
            appendField(identity.sourceId.orEmpty())
        }
        val digest =
            synchronized(hmacLock) {
                Mac.getInstance(HMAC_ALGORITHM)
                    .apply { init(SecretKeySpec(hmacKey, HMAC_ALGORITHM)) }
                    .doFinal(material.toByteArray(Charsets.UTF_8))
            }
        return digest.take(MEDIA_HASH_BYTES).joinToString(separator = "") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
    }

    private fun StringBuilder.appendField(value: String) {
        append(value.length).append(':').append(value).append('|')
    }

    private fun emitRaw(line: String): Boolean = runCatching { sink(line) }.isSuccess

    private fun isEnabled(): Boolean = runCatching(enabled).getOrDefault(false)

    private fun pruneExpired() {
        if (sessions.isEmpty() || !isEnabled()) return
        val now = monotonicMs()
        sessions.forEach { (key, session) ->
            if (session.isExpired(now)) {
                session.close()
                sessions.remove(key, session)
            }
        }
    }

    private fun remove(session: Session) {
        sessions.remove(session.stableKey, session)
    }

    private inner class Session(
        val requestId: String,
        override val stableKey: String,
        val media: String,
        private val rootBudget: RootBudget,
        private val createdMs: Long,
    ) : DownloadDeleteTraceHandle {
        private val lock = Any()
        private val seen = mutableSetOf<String>()
        private var closed = false
        private var eventCount = 0
        private var sourceId: String? = null
        private var generation: Long? = null
        private var staleDropped = false
        private var canonicalMember = true
        private var pendingCleared = false
        private var rowDisposed = false
        private var rowAbsentEmitted = false
        private var frameScheduled = false
        private var d7QueueRecorded = false
        private var refreshToken = 0L
        private var activeRefreshToken: Long? = null
        private var refreshStartedMs: Long? = null
        private var d4JoinStartedMs: Long? = null
        private var d5PreStartedMs: Long? = null

        override fun bindSource(sourceId: String, generation: Long) {
            synchronized(lock) {
                if (closed) return
                this.sourceId = sourceId
                this.generation = generation
            }
        }

        override fun record(event: DownloadDeleteTraceEvent) {
            recordInternal(event)
        }

        override fun pendingCleared() {
            if (!isEnabled()) return
            val schedule =
                synchronized(lock) {
                    if (closed || pendingCleared) return
                    pendingCleared = true
                    emitLocked(
                        event =
                            DownloadDeleteTraceEvent(
                                phase = DownloadDeleteTracePhase.D9_PENDING_CLEARED,
                                result = DownloadDeleteTraceResult.CLEARED,
                                sourceId = sourceId,
                                generation = generation,
                            ),
                        canonicalMember = canonicalMember,
                    )
                    rowDisposed && !rowAbsentEmitted && !frameScheduled
                }
            if (schedule) scheduleRowAbsentFrame()
        }

        fun emitStandalone(
            requestId: String,
            event: DownloadDeleteTraceEvent,
            canonicalMember: Boolean,
            media: String,
        ) {
            val line = synchronized(lock) { formatLine(requestId, event, canonicalMember, media) }
            emitRaw(line)
        }

        fun attached() {
            synchronized(lock) {
                if (closed) return
                rowDisposed = false
            }
        }

        fun disposed() {
            val schedule =
                synchronized(lock) {
                    if (closed || rowAbsentEmitted) return
                    rowDisposed = true
                    pendingCleared && !frameScheduled
                }
            if (schedule) scheduleRowAbsentFrame()
        }

        fun beginRefresh(trigger: DownloadDeleteTraceTrigger, startedMs: Long): Long? =
            synchronized(lock) {
                if (
                    closed ||
                        canonicalMember.not() ||
                        pendingCleared ||
                        activeRefreshToken != null ||
                        (sourceId != null && !d7QueueRecorded)
                ) {
                    return null
                }
                val token = ++refreshToken
                activeRefreshToken = token
                refreshStartedMs = startedMs
                val emitted =
                    emitLocked(
                        event =
                            DownloadDeleteTraceEvent(
                                phase = DownloadDeleteTracePhase.D8_CANONICAL_REFRESH_BEGIN,
                                result = DownloadDeleteTraceResult.STARTED,
                                sourceId = sourceId,
                                generation = generation,
                                trigger = trigger,
                            ),
                        canonicalMember = canonicalMember,
                    )
                token.takeIf { emitted && !closed }
            }

        fun publishRefresh(
            token: Long,
            visibleKeys: Set<String>,
            trigger: DownloadDeleteTraceTrigger,
            nowMs: Long,
        ) {
            synchronized(lock) {
                if (closed || activeRefreshToken != token) return
                val started = refreshStartedMs
                activeRefreshToken = null
                refreshStartedMs = null
                if (stableKey in visibleKeys) return
                canonicalMember = false
                emitLocked(
                    event =
                        DownloadDeleteTraceEvent(
                            phase = DownloadDeleteTracePhase.D8_CANONICAL_REFRESH_END,
                            result = DownloadDeleteTraceResult.ABSENT,
                            sourceId = sourceId,
                            generation = generation,
                            durationMs = started?.let { (nowMs - it).coerceAtLeast(0L) },
                            trigger = trigger,
                        ),
                    canonicalMember = false,
                )
            }
        }

        fun failRefresh(
            token: Long,
            trigger: DownloadDeleteTraceTrigger,
            error: DownloadDeleteTraceError,
            nowMs: Long,
        ) {
            synchronized(lock) {
                if (closed || activeRefreshToken != token) return
                val started = refreshStartedMs
                activeRefreshToken = null
                refreshStartedMs = null
                emitLocked(
                    event =
                        DownloadDeleteTraceEvent(
                            phase = DownloadDeleteTracePhase.D8_CANONICAL_REFRESH_END,
                            result = DownloadDeleteTraceResult.FAILED,
                            error = error,
                            sourceId = sourceId,
                            generation = generation,
                            durationMs = started?.let { (nowMs - it).coerceAtLeast(0L) },
                            trigger = trigger,
                        ),
                    canonicalMember = canonicalMember,
                )
                closeLocked()
            }
        }

        fun isClosed(): Boolean = synchronized(lock) { closed }

        fun isExpired(nowMs: Long): Boolean =
            synchronized(lock) { closed || nowMs - createdMs >= SESSION_TTL_MS }

        fun close() {
            synchronized(lock) { closeLocked() }
        }

        private fun recordInternal(event: DownloadDeleteTraceEvent) {
            if (!isEnabled()) return
            val emitResult =
                synchronized(lock) {
                    if (closed) return
                    val eventSource = event.sourceId ?: sourceId
                    val eventGeneration = event.generation ?: generation
                    if (
                        (sourceId != null &&
                            event.sourceId != null &&
                            event.sourceId != sourceId) ||
                            (generation != null &&
                                event.generation != null &&
                                event.generation != generation)
                    ) {
                        if (!staleDropped) {
                            staleDropped = true
                        }
                        closeLocked()
                        return
                    }
                    val key =
                        listOf(
                                event.phase.name,
                                eventSource.orEmpty(),
                                eventGeneration?.toString().orEmpty(),
                                event.result.name,
                                event.category?.name.orEmpty(),
                            )
                            .joinToString("|")
                    if (!seen.add(key)) return
                    val nowMs = monotonicMs()
                    val normalizedEvent =
                        when {
                            event.phase == DownloadDeleteTracePhase.D4_JOIN_BEGIN -> {
                                d4JoinStartedMs = nowMs
                                event
                            }
                            event.phase == DownloadDeleteTracePhase.D4_JOIN_END &&
                                event.durationMs == null -> {
                                val started = d4JoinStartedMs
                                d4JoinStartedMs = null
                                event.copy(
                                    durationMs = started?.let { (nowMs - it).coerceAtLeast(0L) }
                                )
                            }
                            event.phase == DownloadDeleteTracePhase.D5_FS_PRE_BEGIN &&
                                event.category == null -> {
                                d5PreStartedMs = nowMs
                                event
                            }
                            event.phase == DownloadDeleteTracePhase.D5_FS_END &&
                                event.durationMs == null -> {
                                val started = d5PreStartedMs
                                d5PreStartedMs = null
                                event.copy(
                                    durationMs = started?.let { (nowMs - it).coerceAtLeast(0L) }
                                )
                            }
                            else -> event
                        }
                    when (rootBudget.reserve()) {
                        RootReservation.STOP -> {
                            closeLocked()
                            return
                        }
                        RootReservation.CAPPED -> {
                            emitCappedLocked(eventSource, eventGeneration)
                            closeLocked()
                            return
                        }
                        RootReservation.ALLOW -> Unit
                    }
                    if (eventCount >= PER_LEAF_HARD_CAP - 1) {
                        emitLocked(
                            event =
                                DownloadDeleteTraceEvent(
                                    phase = DownloadDeleteTracePhase.TRACE_CAPPED,
                                    result = DownloadDeleteTraceResult.CAPPED,
                                    sourceId = eventSource,
                                    generation = eventGeneration,
                                ),
                            canonicalMember = canonicalMember,
                        )
                        return
                    }
                    val emitted =
                        emitLocked(
                            event =
                                normalizedEvent.copy(
                                    sourceId = eventSource,
                                    generation = eventGeneration,
                                ),
                            canonicalMember = canonicalMember,
                        )
                    if (event.phase == DownloadDeleteTracePhase.D7_QUEUE_SIGNAL) {
                        d7QueueRecorded = true
                    }
                    if (
                        event.phase == DownloadDeleteTracePhase.D3_ROOM_DELETING_COMMITTED &&
                            event.result == DownloadDeleteTraceResult.SUPERSEDED
                    ) {
                        closeLocked()
                    }
                    emitted
                }
            if (!emitResult) close()
        }

        private fun emitCappedLocked(sourceId: String?, generation: Long?) {
            emitLocked(
                event =
                    DownloadDeleteTraceEvent(
                        phase = DownloadDeleteTracePhase.TRACE_CAPPED,
                        result = DownloadDeleteTraceResult.CAPPED,
                        sourceId = sourceId,
                        generation = generation,
                    ),
                canonicalMember = canonicalMember,
            )
        }

        private fun emitLocked(
            event: DownloadDeleteTraceEvent,
            canonicalMember: Boolean,
        ): Boolean {
            if (closed) return false
            eventCount += 1
            val line = formatLine(requestId, event, canonicalMember, media)
            if (!emitRaw(line)) {
                closeLocked()
                return false
            }
            if (event.phase == DownloadDeleteTracePhase.TRACE_CAPPED) {
                closeLocked()
            }
            return true
        }

        private fun scheduleRowAbsentFrame() {
            synchronized(lock) {
                if (closed || frameScheduled || rowAbsentEmitted) return
                frameScheduled = true
            }
            runCatching {
                frameScheduler.postFrame {
                    val emitted =
                        synchronized(lock) {
                            if (closed || !pendingCleared || !rowDisposed || rowAbsentEmitted) {
                                frameScheduled = false
                                return@synchronized false
                            }
                            rowAbsentEmitted = true
                            frameScheduled = false
                            emitLocked(
                                event =
                                    DownloadDeleteTraceEvent(
                                        phase = DownloadDeleteTracePhase.D9_ROW_ABSENT_FRAME,
                                        result = DownloadDeleteTraceResult.ABSENT,
                                        sourceId = sourceId,
                                        generation = generation,
                                    ),
                                canonicalMember = canonicalMember,
                            )
                        }
                    if (emitted) {
                        close()
                    }
                }
            }
                .onFailure { synchronized(lock) { frameScheduled = false } }
        }

        private fun closeLocked() {
            if (closed) return
            closed = true
            remove(this)
        }
    }

    private data class RefreshTarget(
        val session: Session,
        val token: Long,
    )

    private inner class Refresh(
        private val trigger: DownloadDeleteTraceTrigger,
        private val targets: List<RefreshTarget>,
    ) : DownloadDeleteTraceRefresh {
        override fun publish(visibleKeys: Set<String>) {
            val now = monotonicMs()
            targets.forEach { target ->
                target.session.publishRefresh(target.token, visibleKeys, trigger, now)
            }
        }

        override fun fail(error: DownloadDeleteTraceError) {
            val now = monotonicMs()
            targets.forEach { target ->
                target.session.failRefresh(target.token, trigger, error, now)
            }
        }
    }

    private inner class RowHandle(private val session: Session) : DownloadDeleteTraceRowHandle {
        override fun attached() {
            session.attached()
        }

        override fun disposed() {
            session.disposed()
        }
    }

    private class RootBudget {
        private var count = 0
        private var capEmitted = false

        @Synchronized
        fun reserve(): RootReservation {
            if (capEmitted) return RootReservation.STOP
            if (count >= PER_CONFIRM_HARD_CAP - 1) {
                capEmitted = true
                count += 1
                return RootReservation.CAPPED
            }
            count += 1
            return RootReservation.ALLOW
        }
    }

    private enum class RootReservation {
        ALLOW,
        CAPPED,
        STOP,
    }

    private fun formatLine(
        requestId: String,
        event: DownloadDeleteTraceEvent,
        canonicalMember: Boolean,
        media: String = mediaForRequest(requestId),
    ): String {
        val line = buildString {
            append(TRACE_PREFIX)
            append(" v=1 rid=").append(safeRequestId(requestId))
            append(" phase=").append(event.phase.name)
            append(" t=").append(monotonicMs())
            append(" src=").append(safeStructuralId(event.sourceId))
            append(" gen=").append(event.generation?.toString() ?: NA_VALUE)
            append(" media=").append(media)
            append(" result=").append(event.result.name)
            append(" error=").append(event.error.name)
            append(" canonical=").append(if (canonicalMember) "true" else "false")
            event.durationMs?.let { append(" dur_ms=").append(it.coerceAtLeast(0L)) }
            event.category?.let { append(" category=").append(it.name) }
            event.count?.let { append(" count=").append(it.coerceAtLeast(0)) }
            event.trigger?.let { append(" trigger=").append(it.name) }
        }
        if (line.length <= MAX_LINE_BYTES) return line
        return line.take(MAX_LINE_BYTES - 1).substringBeforeLast(' ')
    }

    private fun mediaForRequest(requestId: String): String {
        val stableRequest = requestId.substringBefore('/')
        return sessions.values.firstOrNull { it.requestId.startsWith("$stableRequest/") }?.media
            ?: DUPLICATE_MEDIA
    }

    private fun safeRequestId(value: String): String =
        value.takeIf { SAFE_REQUEST_ID.matches(it) } ?: UNKNOWN_VALUE

    private fun safeStructuralId(value: String?): String {
        val normalized = value?.trim().orEmpty()
        return normalized.takeIf { SAFE_STRUCTURAL_ID.matches(it) } ?: NA_VALUE
    }

    companion object {
        private const val TRACE_TAG = "DownloadDeleteTrace"
        private const val TRACE_PREFIX = "AURORAMA_DELETE_TRACE"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val HMAC_KEY_BYTES = 32
        private const val REQUEST_NONCE_BYTES = 8
        private const val MEDIA_HASH_BYTES = 8
        private const val PER_LEAF_HARD_CAP = 32
        private const val PER_CONFIRM_HARD_CAP = 512
        private const val MAX_LINE_BYTES = 384
        private const val SESSION_TTL_MS = 30_000L
        private const val NA_VALUE = "NA"
        private const val UNKNOWN_VALUE = "UNKNOWN"
        private const val DUPLICATE_MEDIA = "0000000000000000"
        private val SAFE_REQUEST_ID = Regex("""[A-Za-z0-9:_/-]{1,96}""")
        private val SAFE_STRUCTURAL_ID = Regex("""[A-Za-z0-9:_-]{1,96}""")

        private fun randomBytes(size: Int): ByteArray =
            ByteArray(size).also(SecureRandom()::nextBytes)

        private fun randomHex(size: Int): String =
            randomBytes(size).joinToString(separator = "") { byte ->
                "%02X".format(Locale.ROOT, byte.toInt() and 0xff)
            }

        internal fun createForTest(
            enabled: () -> Boolean,
            sink: (String) -> Unit,
            monotonicMs: () -> Long,
            hmacKey: ByteArray,
            rootNonce: String,
            frameScheduler: DownloadDeleteTraceFrameScheduler =
                DownloadDeleteTraceFrameScheduler {},
        ): DownloadDeleteTraceOwner =
            DownloadDeleteTraceOwner(
                enabled = enabled,
                sink = sink,
                monotonicMs = monotonicMs,
                hmacKey = hmacKey,
                rootNonce = rootNonce,
                frameScheduler = frameScheduler,
            )
    }
}
