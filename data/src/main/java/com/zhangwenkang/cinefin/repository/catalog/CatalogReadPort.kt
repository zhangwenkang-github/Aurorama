package com.zhangwenkang.cinefin.repository.catalog

import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.identity.IdentityReadPort
import com.zhangwenkang.cinefin.identity.IdentitySnapshot
import com.zhangwenkang.cinefin.identity.IdentityStateStatus
import com.zhangwenkang.cinefin.identity.IdentityStateStore
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder
import com.zhangwenkang.cinefin.repository.JellyfinOrigin
import com.zhangwenkang.cinefin.repository.SearchScope
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter

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

data class CatalogHomeQuery(
    val frozenScope: CatalogScope,
    val includeSuggestions: Boolean = true,
    val includeContinueWatching: Boolean = true,
    val includeContinueReading: Boolean = true,
    val includeContinueListening: Boolean = true,
    val includeNextUp: Boolean = true,
    val includeUserViews: Boolean = true,
    val forceRefresh: Boolean = false,
)

data class CatalogView(
    val id: UUID,
    val name: String,
    val type: CollectionType,
)

data class CatalogViewItems(
    val view: CatalogView,
    val latestMedia: List<FindroidItem>,
)

data class CatalogHomeData(
    val suggestions: List<FindroidItem> = emptyList(),
    val resumeWatching: List<FindroidItem> = emptyList(),
    val resumeReading: List<FindroidItem> = emptyList(),
    val resumeListening: List<FindroidItem> = emptyList(),
    val nextUp: List<FindroidItem> = emptyList(),
    val views: List<CatalogViewItems> = emptyList(),
)

data class CatalogLibrariesQuery(
    val frozenScope: CatalogScope,
    val forceRefresh: Boolean = false,
)

data class CatalogItemsPageQuery(
    val frozenScope: CatalogScope,
    val parentId: UUID?,
    val includeTypes: List<BaseItemKind>?,
    val recursive: Boolean,
    val sortBy: SortBy,
    val sortOrder: SortOrder,
    val startIndex: Int,
    val limit: Int,
    val filters: List<ItemFilter>?,
    val genres: List<String>?,
    val studios: List<String>?,
) {
    init {
        require(startIndex >= 0) { "startIndex must not be negative" }
        require(limit > 0) { "limit must be positive" }
    }
}

data class CatalogMovieQuery(
    val itemId: UUID,
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

    suspend fun home(query: CatalogHomeQuery): CatalogReadOutcome<CatalogHomeData> =
        CatalogReadOutcome.IdentityUnavailable

    suspend fun libraries(
        query: CatalogLibrariesQuery
    ): CatalogReadOutcome<List<FindroidCollection>> = CatalogReadOutcome.IdentityUnavailable

    suspend fun itemsPage(query: CatalogItemsPageQuery): CatalogReadOutcome<List<FindroidItem>> =
        CatalogReadOutcome.IdentityUnavailable

    suspend fun movie(query: CatalogMovieQuery): CatalogReadOutcome<FindroidMovie> =
        CatalogReadOutcome.IdentityUnavailable
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
        val snapshot =
            try {
                canonicalReader.current()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return null
            }
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
