package com.zhangwenkang.cinefin.repository.catalog

import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.JellyfinOrigin
import com.zhangwenkang.cinefin.repository.SearchScope
import com.zhangwenkang.cinefin.settings.domain.AppPreferences

enum class CatalogPrincipalContext {
    ONLINE_ACCOUNT,
    SAVED_OFFLINE_ACCOUNT,
}

data class CatalogScope(
    val origin: JellyfinOrigin,
    val principalContext: CatalogPrincipalContext,
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

internal class CatalogScopeResolver(
    private val appPreferences: AppPreferences,
    private val database: ServerDatabaseDao,
    private val principalContext: CatalogPrincipalContext,
) {
    suspend fun resolve(): CatalogScope? {
        val serverId =
            appPreferences.getValue(appPreferences.currentServer)?.takeIf {
                it.isNotBlank() && '\u0000' !in it
            } ?: return null
        val user = database.getServerCurrentUser(serverId) ?: return null
        if (user.serverId != serverId) return null
        if (principalContext == CatalogPrincipalContext.ONLINE_ACCOUNT) {
            if (user.accessToken.isNullOrBlank()) return null
            val address = database.getServerCurrentAddress(serverId) ?: return null
            if (address.serverId != serverId || address.address.isBlank()) return null
        }
        return CatalogScope(
            origin = JellyfinOrigin(serverId = serverId, userId = user.id),
            principalContext = principalContext,
        )
    }
}
