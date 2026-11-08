package com.zhangwenkang.cinefin.identity

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

data class IdentitySelection(
    val serverId: String? = null,
    val userId: String? = null,
    val addressId: String? = null,
    val offlineMode: Boolean = false,
) {
    init {
        require(userId == null || serverId != null)
        require(addressId == null || serverId != null)
    }
}

enum class IdentityStateStatus {
    READY,
    RECOVERING,
}

data class IdentitySnapshot(
    val selection: IdentitySelection,
    val identityGeneration: Long,
    val status: IdentityStateStatus,
) {
    init {
        require(identityGeneration >= 0L)
    }
}

interface IdentityReadPort {
    suspend fun hydrate(): IdentitySnapshot

    suspend fun current(): IdentitySnapshot
}

/**
 * Coordinator-issued proof of one visible READY snapshot and its intent epoch. External modules can
 * carry this capability but cannot mint or rewrite it.
 */
class IdentityReadLease
internal constructor(
    internal val snapshot: IdentitySnapshot,
    internal val intent: Long,
) {
    val selection: IdentitySelection
        get() = snapshot.selection
}

/**
 * Optional read capability for effects that depend on identity. A stale lease fails closed; readers
 * that only implement [IdentityReadPort] remain valid for callers that do not require this proof.
 */
interface IdentityReadLeasePort {
    suspend fun acquireReadyLease(): IdentityReadLease?

    suspend fun <T> withReadyLease(
        lease: IdentityReadLease,
        effect: suspend (IdentitySelection) -> T,
    ): T?

    suspend fun isReadyLeaseCurrent(lease: IdentityReadLease): Boolean

    suspend fun beginIngressForLease(lease: IdentityReadLease): IdentityIngressAttempt?
}

interface IdentityProjectionPermit {
    suspend fun commit(effect: suspend () -> Unit): Boolean
}

/**
 * A coordinator-issued ingress attempt. External modules can carry the attempt but cannot mint or
 * rewrite its intent or expected generation.
 */
class IdentityIngressAttempt
internal constructor(
    internal val intent: Long,
    internal val expectedGeneration: Long,
)

/**
 * Setup ingress operations use the coordinator's existing intent sequence and transition mutex. The
 * attempt is registered before isolation work starts; the conditional methods atomically prove that
 * both the attempt and the expected persisted generation are still current.
 */
interface IdentityConditionalWritePort {
    suspend fun beginIngress(): IdentityIngressAttempt

    suspend fun isIngressCurrent(attempt: IdentityIngressAttempt): Boolean

    suspend fun selectServerIfGeneration(
        attempt: IdentityIngressAttempt,
        selection: IdentitySelection,
        projection: IdentityProjection = IdentityProjection.None,
    ): IdentityTransitionResult

    suspend fun selectAddressIfGeneration(
        attempt: IdentityIngressAttempt,
        selection: IdentitySelection,
        projection: IdentityProjection = IdentityProjection.None,
    ): IdentityTransitionResult

    suspend fun selectSavedUserIfGeneration(
        attempt: IdentityIngressAttempt,
        selection: IdentitySelection,
        projection: IdentityProjection = IdentityProjection.None,
    ): IdentityTransitionResult

    suspend fun selectAuthenticatedUserIfGeneration(
        attempt: IdentityIngressAttempt,
        selection: IdentitySelection,
        projection: IdentityProjection = IdentityProjection.None,
    ): IdentityTransitionResult

    suspend fun deleteSelectionIfGeneration(
        attempt: IdentityIngressAttempt,
        selection: IdentitySelection,
        projection: IdentityProjection = IdentityProjection.None,
    ): IdentityTransitionResult
}

fun interface IdentityProjection {
    suspend fun project(snapshot: IdentitySnapshot, permit: IdentityProjectionPermit)

    companion object {
        val None = IdentityProjection { _, _ -> }
    }
}

interface IdentityWritePort {
    suspend fun selectServer(
        selection: IdentitySelection,
        projection: IdentityProjection = IdentityProjection.None,
    ): IdentityTransitionResult

    suspend fun selectAddress(
        selection: IdentitySelection,
        projection: IdentityProjection = IdentityProjection.None,
    ): IdentityTransitionResult

    suspend fun selectSavedUser(
        selection: IdentitySelection,
        projection: IdentityProjection = IdentityProjection.None,
    ): IdentityTransitionResult

    suspend fun selectAuthenticatedUser(
        selection: IdentitySelection,
        projection: IdentityProjection = IdentityProjection.None,
    ): IdentityTransitionResult

    suspend fun selectOfflineMode(
        enabled: Boolean,
        projection: IdentityProjection = IdentityProjection.None,
    ): IdentityTransitionResult

    suspend fun deleteSelection(
        selection: IdentitySelection,
        projection: IdentityProjection = IdentityProjection.None,
    ): IdentityTransitionResult
}

enum class IdentityTransitionResult {
    APPLIED,
    NO_OP,
    STALE_DISCARDED,
    RECOVERING,
    FAILED,
}

/**
 * Read-only proof that the current coroutine is inside an active coordinator permit commit. The
 * context element and authorization are private to :data and become inert as soon as the commit
 * effect returns, so an external implementation of the public permit interface cannot mint a usable
 * projection scope.
 */
object IdentityProjectionScope {
    suspend fun currentSnapshot(): IdentitySnapshot? =
        currentCoroutineContext()[IdentityProjectionContextElement.Key]?.authorization?.snapshot()
}

internal suspend fun <T> withIdentityProjectionAuthorization(
    snapshot: IdentitySnapshot,
    block: suspend () -> T,
): T {
    val authorization = IdentityProjectionAuthorization(snapshot)
    return try {
        withContext(IdentityProjectionContextElement(authorization)) { block() }
    } finally {
        authorization.close()
    }
}

private class IdentityProjectionAuthorization(private val snapshot: IdentitySnapshot) {
    @Volatile private var active = true

    fun snapshot(): IdentitySnapshot? = snapshot.takeIf { active }

    fun close() {
        active = false
    }
}

private class IdentityProjectionContextElement(val authorization: IdentityProjectionAuthorization) :
    AbstractCoroutineContextElement(Key) {
    companion object {
        val Key = object : CoroutineContext.Key<IdentityProjectionContextElement> {}
    }
}
