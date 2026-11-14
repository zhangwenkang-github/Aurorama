package com.zhangwenkang.cinefin.utils

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

enum class DownloadDeleteTracePhase {
    D1_REQUEST_ACCEPTED,
    D1_DUPLICATE_REJECTED,
    D2_PENDING_PROJECTED,
    D3_ROOM_DELETING_COMMITTED,
    D3_RESUME_ALREADY_DELETING,
    D4_CANCEL_BEGIN,
    D4_JOIN_BEGIN,
    D4_JOIN_END,
    D5_FS_PRE_BEGIN,
    D5_FS_PRE_END,
    D5_FS_POST_ROW_BEGIN,
    D5_FS_POST_ROW_END,
    D5_FS_END,
    D6_SOURCE_MEDIA_ROWS_REMOVED,
    D6_HIERARCHY_ROWS_REMOVED,
    D7_RUNTIME_REMOVED,
    D7_NOTIFICATION_DECISION,
    D7_QUEUE_SIGNAL,
    D8_CANONICAL_REFRESH_BEGIN,
    D8_CANONICAL_REFRESH_END,
    D9_PENDING_CLEARED,
    D9_ROW_ABSENT_FRAME,
    TRACE_CAPPED,
}

enum class DownloadDeleteTraceResult {
    ACCEPTED,
    REJECTED_DUPLICATE,
    VISIBLE,
    COMMITTED,
    RESUMED,
    STARTED,
    DONE,
    REMOVED,
    SKIPPED,
    SCHEDULED,
    THROTTLED,
    NO_ACTIVE,
    CLEARED,
    ABSENT,
    FAILED,
    SUPERSEDED,
    RETAINED,
    CAPPED,
    UI_OBSERVATION_UNAVAILABLE,
}

enum class DownloadDeleteTraceError {
    NONE,
    CANCELLED,
    IO,
    ROOM,
    NOT_FOUND,
    CAS,
    SUPERSEDED,
    TIMEOUT,
    UNKNOWN,
}

enum class DownloadDeleteTraceCategory {
    PARTIAL,
    RESOLVE,
    FINAL,
    MEDIA_STREAM_FILE,
    SIDECAR,
    HIERARCHY,
    IMAGES,
    TRICKPLAY,
    IMAGE_CACHE,
}

enum class DownloadDeleteTraceTrigger {
    EXPLICIT,
    QUEUE,
    POLL,
}

data class DownloadDeleteTraceIdentity(
    val mediaKind: DownloadMediaKind,
    val serverId: String?,
    val userId: String?,
    val itemId: String,
    val sourceId: String?,
)

data class DownloadDeleteTraceRequest(
    val stableKey: String,
    val sourceId: String?,
    val identity: () -> DownloadDeleteTraceIdentity,
)

data class DownloadDeleteTraceEvent(
    val phase: DownloadDeleteTracePhase,
    val result: DownloadDeleteTraceResult,
    val error: DownloadDeleteTraceError = DownloadDeleteTraceError.NONE,
    val sourceId: String? = null,
    val generation: Long? = null,
    val durationMs: Long? = null,
    val category: DownloadDeleteTraceCategory? = null,
    val count: Int? = null,
    val trigger: DownloadDeleteTraceTrigger? = null,
)

interface DownloadDeleteTraceHandle {
    val stableKey: String

    fun bindSource(sourceId: String, generation: Long)

    fun record(event: DownloadDeleteTraceEvent)

    fun pendingCleared()
}

interface DownloadDeleteTraceRowHandle {
    fun attached()

    fun disposed()
}

interface DownloadDeleteTraceRefresh {
    fun publish(visibleKeys: Set<String>)

    fun fail(error: DownloadDeleteTraceError)
}

data class DownloadDeleteTraceRoot(
    val handles: List<DownloadDeleteTraceHandle>,
    val sourceBindings: Map<String, DownloadDeleteTraceHandle>,
) {
    companion object {
        val Empty = DownloadDeleteTraceRoot(emptyList(), emptyMap())
    }
}

interface DownloadDeleteTracePort {
    fun start(requests: List<DownloadDeleteTraceRequest>): DownloadDeleteTraceRoot

    fun beginCanonicalRefresh(trigger: DownloadDeleteTraceTrigger): DownloadDeleteTraceRefresh?

    fun rowTrace(stableKey: String): DownloadDeleteTraceRowHandle?
}

fun DownloadDeleteTraceHandle?.safeBindSource(sourceId: String, generation: Long) {
    runCatching { this?.bindSource(sourceId, generation) }
}

fun DownloadDeleteTraceHandle?.safeRecord(event: DownloadDeleteTraceEvent) {
    runCatching { this?.record(event) }
}

fun DownloadDeleteTraceHandle?.safePendingCleared() {
    runCatching { this?.pendingCleared() }
}

fun DownloadDeleteTraceRefresh?.safePublish(visibleKeys: Set<String>) {
    runCatching { this?.publish(visibleKeys) }
}

fun DownloadDeleteTraceRefresh?.safeFail(error: DownloadDeleteTraceError) {
    runCatching { this?.fail(error) }
}

fun DownloadDeleteTraceRowHandle?.safeAttached() {
    runCatching { this?.attached() }
}

fun DownloadDeleteTraceRowHandle?.safeDisposed() {
    runCatching { this?.disposed() }
}

fun interface DownloadDeleteTraceInstaller {
    fun install()
}

fun interface DownloadDeleteTraceFrameScheduler {
    fun postFrame(callback: () -> Unit)
}

object DownloadDeleteTraceRuntime {
    @Volatile private var port: DownloadDeleteTracePort? = null

    fun install(installer: DownloadDeleteTraceInstaller) {
        runCatching { installer.install() }
    }

    fun install(installedPort: DownloadDeleteTracePort) {
        port = installedPort
    }

    fun uninstall(installedPort: DownloadDeleteTracePort? = null) {
        if (installedPort == null || port === installedPort) {
            port = null
        }
    }

    fun start(requests: List<DownloadDeleteTraceRequest>): DownloadDeleteTraceRoot {
        if (requests.isEmpty()) return DownloadDeleteTraceRoot.Empty
        return runCatching { port?.start(requests) }.getOrNull() ?: DownloadDeleteTraceRoot.Empty
    }

    fun beginCanonicalRefresh(trigger: DownloadDeleteTraceTrigger): DownloadDeleteTraceRefresh? =
        runCatching {
            port?.beginCanonicalRefresh(trigger)
        }
        .getOrNull()

    fun rowTrace(stableKey: String): DownloadDeleteTraceRowHandle? = runCatching {
        port?.rowTrace(stableKey)
    }
        .getOrNull()
}

private class DownloadDeleteTraceCoroutineContext(
    val sourceBindings: Map<String, DownloadDeleteTraceHandle>
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DownloadDeleteTraceCoroutineContext>
}

suspend fun currentDownloadDeleteTraceHandle(sourceId: String): DownloadDeleteTraceHandle? =
    currentCoroutineContext()[DownloadDeleteTraceCoroutineContext]?.sourceBindings?.get(sourceId)

suspend fun <T> withDownloadDeleteTrace(
    root: DownloadDeleteTraceRoot,
    block: suspend () -> T,
): T {
    if (root.sourceBindings.isEmpty()) return block()
    return withContext(DownloadDeleteTraceCoroutineContext(root.sourceBindings)) { block() }
}
