package com.zhangwenkang.cinefin.identity

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

fun interface IdentityProjection {
    suspend fun project(snapshot: IdentitySnapshot)

    companion object {
        val None = IdentityProjection {}
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
