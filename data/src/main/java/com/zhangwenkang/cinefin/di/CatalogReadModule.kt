package com.zhangwenkang.cinefin.di

import com.zhangwenkang.cinefin.identity.IdentityReadPort
import com.zhangwenkang.cinefin.identity.IdentitySnapshot
import com.zhangwenkang.cinefin.identity.IdentityStateStatus
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.JellyfinRepositoryImpl
import com.zhangwenkang.cinefin.repository.JellyfinRepositoryOfflineImpl
import com.zhangwenkang.cinefin.repository.catalog.CatalogPrincipalContext
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadOutcome
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadPort
import com.zhangwenkang.cinefin.repository.catalog.CatalogScope
import com.zhangwenkang.cinefin.repository.catalog.CatalogSearchQuery
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.UUID

@Module
@InstallIn(SingletonComponent::class)
object CatalogReadModule {
    @Provides
    fun provideCatalogReadPort(
        online: JellyfinRepositoryImpl,
        offline: JellyfinRepositoryOfflineImpl,
        identityReadPort: IdentityReadPort,
    ): CatalogReadPort = dynamicCatalogReadPort(online, offline, identityReadPort)

    internal fun dynamicCatalogReadPort(
        online: CatalogReadPort,
        offline: CatalogReadPort,
        identityReadPort: IdentityReadPort,
    ): CatalogReadPort =
        DynamicCatalogReadPort(
            online = online,
            offline = offline,
            identityReadPort = identityReadPort,
        )
}

private class DynamicCatalogReadPort(
    private val online: CatalogReadPort,
    private val offline: CatalogReadPort,
    private val identityReadPort: IdentityReadPort,
) : CatalogReadPort {
    override suspend fun currentScope(): CatalogScope? = resolveCurrent()?.scope

    override suspend fun search(query: CatalogSearchQuery): CatalogReadOutcome<List<FindroidItem>> {
        val before = resolveCurrent() ?: return CatalogReadOutcome.IdentityUnavailable
        if (query.frozenScope != before.scope) {
            return CatalogReadOutcome.IdentityUnavailable
        }
        val outcome = before.port.search(query)
        val after = resolveCurrent() ?: return CatalogReadOutcome.IdentityUnavailable
        if (after.scope != before.scope) {
            return CatalogReadOutcome.IdentityUnavailable
        }
        return when (outcome) {
            CatalogReadOutcome.IdentityUnavailable -> CatalogReadOutcome.IdentityUnavailable
            is CatalogReadOutcome.Available ->
                if (outcome.snapshot.scope == before.scope) {
                    outcome
                } else {
                    CatalogReadOutcome.IdentityUnavailable
                }
        }
    }

    private suspend fun resolveCurrent(): Resolution? {
        val before = canonicalSnapshot() ?: return null
        val port = if (before.selection.offlineMode) offline else online
        val scope = runCatching { port.currentScope() }.getOrNull() ?: return null
        val after = canonicalSnapshot() ?: return null
        if (before != after || !scope.matches(before)) return null
        return Resolution(scope = scope, port = port)
    }

    private suspend fun canonicalSnapshot(): IdentitySnapshot? = runCatching {
        identityReadPort.current()
    }
        .getOrNull()
        ?.takeIf { snapshot ->
            val selection = snapshot.selection
            snapshot.status == IdentityStateStatus.READY &&
                snapshot.identityGeneration > 0L &&
                !selection.serverId.isNullOrBlank() &&
                selection.userId?.let { value ->
                    runCatching { UUID.fromString(value) }.isSuccess
                } == true &&
                selection.addressId?.let { value ->
                    runCatching { UUID.fromString(value) }.isSuccess
                } == true
        }

    private fun CatalogScope.matches(snapshot: IdentitySnapshot): Boolean {
        val selection = snapshot.selection
        val expectedContext =
            if (selection.offlineMode) {
                CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT
            } else {
                CatalogPrincipalContext.ONLINE_ACCOUNT
            }
        return identityGeneration == snapshot.identityGeneration &&
            principalContext == expectedContext &&
            origin.serverId == selection.serverId &&
            origin.userId.toString() == selection.userId
    }

    private data class Resolution(val scope: CatalogScope, val port: CatalogReadPort)
}
