package com.zhangwenkang.cinefin.repository.catalog

import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.identity.IdentityReadPort
import com.zhangwenkang.cinefin.identity.IdentitySnapshot
import com.zhangwenkang.cinefin.identity.IdentityStateStatus
import com.zhangwenkang.cinefin.identity.IdentityStateStore
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.JellyfinOrigin
import com.zhangwenkang.cinefin.repository.SearchScope
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.util.UUID

enum class CatalogPrincipalContext {
    ONLINE_ACCOUNT,
    SAVED_OFFLINE_ACCOUNT,
}

data class CatalogScope(
    val origin: JellyfinOrigin,
    val principalContext: CatalogPrincipalContext,
    val identityGeneration: Long,
)

data class CatalogSearchQuery(
    val term: String,
    val scope: SearchScope,
    val frozenScope: CatalogScope,
)

enum class CatalogFreshness {
    FRESH,
    STALE,
    LOCAL_QUERY,
}

data class CatalogSnapshot<T>(
    val scope: CatalogScope,
    val contentGeneration: Long,
    val freshness: CatalogFreshness,
    val observedAtEpochMs: Long,
    val value: T,
)

sealed interface CatalogReadOutcome<out T> {
    data class Available<T>(val snapshot: CatalogSnapshot<T>) : CatalogReadOutcome<T>

    data object IdentityUnavailable : CatalogReadOutcome<Nothing>
}

interface CatalogReadPort {
    suspend fun currentScope(): CatalogScope?

    suspend fun search(query: CatalogSearchQuery): CatalogReadOutcome<List<FindroidItem>>
}

internal class CatalogScopeResolver
private constructor(
    private val database: ServerDatabaseDao,
    private val principalContext: CatalogPrincipalContext,
    private val canonicalReader: IdentityReadPort,
) {
    internal constructor(
        appPreferences: AppPreferences,
        database: ServerDatabaseDao,
        principalContext: CatalogPrincipalContext,
    ) : this(
        database = database,
        principalContext = principalContext,
        canonicalReader = PersistedIdentityReadPort(appPreferences),
    )

    internal constructor(
        identityReadPort: IdentityReadPort,
        database: ServerDatabaseDao,
        principalContext: CatalogPrincipalContext,
    ) : this(
        database = database,
        principalContext = principalContext,
        canonicalReader = identityReadPort,
    )

    suspend fun resolve(): CatalogScope? {
        val snapshot = runCatching { canonicalReader.current() }.getOrNull() ?: return null
        if (snapshot.status != IdentityStateStatus.READY || snapshot.identityGeneration <= 0L) {
            return null
        }
        val selection = snapshot.selection
        if (
            selection.offlineMode !=
                (principalContext == CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT)
        ) {
            return null
        }
        val serverId =
            selection.serverId?.takeIf { it.isNotBlank() && '\u0000' !in it } ?: return null
        val userId =
            selection.userId?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
                ?: return null
        val addressId =
            selection.addressId?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
                ?: return null
        val user = database.getServerCurrentUser(serverId) ?: return null
        if (user.serverId != serverId || user.id != userId || user.accessToken.isNullOrBlank()) {
            return null
        }
        val address = database.getServerCurrentAddress(serverId) ?: return null
        if (address.serverId != serverId || address.id != addressId || address.address.isBlank()) {
            return null
        }
        return CatalogScope(
            origin = JellyfinOrigin(serverId = serverId, userId = userId),
            principalContext = principalContext,
            identityGeneration = snapshot.identityGeneration,
        )
    }
}

private class PersistedIdentityReadPort(appPreferences: AppPreferences) : IdentityReadPort {
    private val store = IdentityStateStore(appPreferences)

    override suspend fun hydrate(): IdentitySnapshot = store.read()

    override suspend fun current(): IdentitySnapshot = store.read()
}
