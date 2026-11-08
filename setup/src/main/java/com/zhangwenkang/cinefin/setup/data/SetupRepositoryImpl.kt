package com.zhangwenkang.cinefin.setup.data

import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.api.JellyfinApiCandidate
import com.zhangwenkang.cinefin.api.JellyfinApiFactory
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.identity.IdentityConditionalWritePort
import com.zhangwenkang.cinefin.identity.IdentityIngressAttempt
import com.zhangwenkang.cinefin.identity.IdentityProjectionScope
import com.zhangwenkang.cinefin.identity.IdentityReadLease
import com.zhangwenkang.cinefin.identity.IdentityReadLeasePort
import com.zhangwenkang.cinefin.identity.IdentityReadPort
import com.zhangwenkang.cinefin.identity.IdentitySelection
import com.zhangwenkang.cinefin.identity.IdentitySnapshot
import com.zhangwenkang.cinefin.identity.IdentityStateStore
import com.zhangwenkang.cinefin.identity.IdentityTransitionResult
import com.zhangwenkang.cinefin.identity.IdentityWriteCoordinator
import com.zhangwenkang.cinefin.identity.IdentityWritePort
import com.zhangwenkang.cinefin.models.ExceptionUiText
import com.zhangwenkang.cinefin.models.ExceptionUiTexts
import com.zhangwenkang.cinefin.models.Server
import com.zhangwenkang.cinefin.models.ServerAddress
import com.zhangwenkang.cinefin.models.ServerWithAddresses
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.models.User
import com.zhangwenkang.cinefin.network.CertificateTrustRequiredException
import com.zhangwenkang.cinefin.network.TrustedCertificate
import com.zhangwenkang.cinefin.network.fingerprintsMatch
import com.zhangwenkang.cinefin.network.httpsTargetOf
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.setup.R as SetupR
import com.zhangwenkang.cinefin.setup.domain.IdentityAttemptStaleException
import com.zhangwenkang.cinefin.setup.domain.IdentityTransitionFailedException
import com.zhangwenkang.cinefin.setup.domain.SavedUserActivationFailure
import com.zhangwenkang.cinefin.setup.domain.SavedUserActivationResult
import com.zhangwenkang.cinefin.setup.domain.SavedUserAuthenticationException
import com.zhangwenkang.cinefin.setup.domain.SetupRepository
import com.zhangwenkang.cinefin.setup.domain.classifySavedUserAuthenticationError
import com.zhangwenkang.cinefin.setup.domain.nextCurrentUserIdAfterDelete
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.discovery.RecommendedServerIssue
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import timber.log.Timber

sealed interface ServerBootstrapResult {
    data class Explicit(val systemInfo: PublicSystemInfo, val address: String) :
        ServerBootstrapResult

    data class Discovered(val servers: Collection<RecommendedServerInfo>) : ServerBootstrapResult
}

suspend fun resolveServerBootstrap(
    address: String,
    candidates: Collection<String>,
    loadPublicInfo: suspend (baseUrl: String) -> PublicSystemInfo,
    loadRecommendedServers: suspend () -> Collection<RecommendedServerInfo>,
): ServerBootstrapResult {
    val explicitBaseUrl =
        if (
            address.trim().startsWith("http://", ignoreCase = true) ||
                address.trim().startsWith("https://", ignoreCase = true)
        ) {
            candidates.firstOrNull()
        } else {
            null
        }

    if (explicitBaseUrl != null) {
        suspend fun loadPublicInfoOrNull(): PublicSystemInfo? =
            try {
                loadPublicInfo(explicitBaseUrl)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        // Pad 5 的首次直连 socket 偶发停留在 SYN-SENT；同一快速路径重试可避开旧连接，
        // 仍失败时才进入原有候选发现。
        val systemInfo = loadPublicInfoOrNull() ?: loadPublicInfoOrNull()
        if (
            systemInfo?.productName?.contains("Jellyfin", ignoreCase = true) == true &&
                systemInfo.version?.isNotBlank() == true &&
                systemInfo.id?.isNotBlank() == true
        ) {
            return ServerBootstrapResult.Explicit(systemInfo, explicitBaseUrl)
        }
    }

    return ServerBootstrapResult.Discovered(loadRecommendedServers())
}

class SetupRepositoryImpl(
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val apiFactory: JellyfinApiFactory =
        JellyfinApiFactory(jellyfinApi) {
            error("An isolated candidate API factory is required for setup identity ingress")
        },
    private val identityReadPort: IdentityReadPort? = null,
    private val identityReadLeasePort: IdentityReadLeasePort? = null,
    private val identityWritePort: IdentityWritePort? = null,
    private val identityConditionalWritePort: IdentityConditionalWritePort? = null,
) : SetupRepository {
    private val fallbackCoordinator by
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            IdentityWriteCoordinator(IdentityStateStore(appPreferences))
        }

    private data class ServerContext(
        val server: Server,
        val address: ServerAddress,
        val user: User?,
    )

    private data class SavedUserContext(
        val context: ServerContext,
        val user: User,
    )

    private data class LeasedRead<T>(
        val port: IdentityReadLeasePort,
        val lease: IdentityReadLease,
        val value: T,
    )

    private data class PersistedServer(
        val server: Server,
        val address: ServerAddress,
    )

    private data class ServerPersistencePlan(
        val server: Server,
        val address: ServerAddress,
        val insertServer: Boolean,
        val insertAddress: Boolean,
        val updateServer: Boolean,
    )

    override fun discoverServers(): Flow<ServerDiscoveryInfo> =
        apiFactory.create().api.jellyfin.discovery.discoverLocalServers()

    override suspend fun getServers(): List<ServerWithAddresses> =
        database.getServersWithAddresses()

    override suspend fun getCurrentServer(): Server? = withReadyLease { selection ->
        selection.serverId?.let { selectedServerId -> database.getServer(selectedServerId) }
    }

    override suspend fun deleteServer(serverId: String) {
        database.deleteServer(serverId)
    }

    override suspend fun getIsQuickConnectEnabled(): Boolean =
        withContext(Dispatchers.IO) {
            val outcome =
                withVerifiedReadyContext { context ->
                    val candidate = apiFactory.create(baseUrl = context.address.address)
                    try {
                        Result.success(
                            candidate.api.quickConnectApi.getQuickConnectEnabled().content
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                } ?: return@withContext false
            outcome.exceptionOrNull()?.let { error ->
                throw certificateTrustRequiredForCurrentServer() ?: error
            }
            outcome.getOrThrow()
        }

    override suspend fun initiateQuickConnect(): QuickConnectResult =
        withContext(Dispatchers.IO) {
            val outcome =
                withVerifiedReadyContext { context ->
                    val candidate = apiFactory.create(baseUrl = context.address.address)
                    try {
                        Result.success(candidate.api.quickConnectApi.initiateQuickConnect().content)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                } ?: recoveringIdentityFailure()
            outcome.exceptionOrNull()?.let { error ->
                throw certificateTrustRequiredForCurrentServer() ?: error
            }
            outcome.getOrThrow()
        }

    override suspend fun getQuickConnectState(secret: String): QuickConnectResult =
        withContext(Dispatchers.IO) {
            withVerifiedReadyContext { context ->
                val candidate = apiFactory.create(baseUrl = context.address.address)
                candidate.api.quickConnectApi.getQuickConnectState(secret).content
            } ?: recoveringIdentityFailure()
        }

    override suspend fun setCurrentServer(serverId: String) {
        val activeSnapshot = IdentityProjectionScope.currentSnapshot()
        if (activeSnapshot != null) {
            applyServerProjection(serverId, activeSnapshot)
            return
        }

        val read = readUnderReadyLease { it } ?: recoveringIdentityFailure()
        check(identityConditionalWritePort == null) {
            "Selecting a server requires an identity projection permit commit"
        }
        val attempt = read.port.beginIngressForLease(read.lease) ?: recoveringIdentityFailure()
        val relation =
            database.getServerWithAddressAndUser(serverId)
                ?: throw IllegalStateException("Cannot select server $serverId: server is missing")
        val address =
            relation.address
                ?: throw IllegalStateException(
                    "Cannot select server $serverId: current address is missing"
                )
        ensureIngressCurrent(attempt)
        val selection =
            IdentitySelection(
                serverId = relation.server.id,
                userId = relation.user?.id?.toString(),
                addressId = address.id.toString(),
                offlineMode = read.value.offlineMode,
            )
        commitServerSelection(attempt, selection) { snapshot ->
            applyServerProjection(serverId, snapshot)
            relation.server
        }
    }

    override suspend fun addServer(address: String): Server {
        if (address.isBlank()) {
            throw ExceptionUiText(
                UiText.StringResource(SetupR.string.add_server_error_empty_address)
            )
        }

        val read = readUnderReadyLease { it } ?: recoveringIdentityFailure()
        val attempt = read.port.beginIngressForLease(read.lease) ?: recoveringIdentityFailure()
        val currentSelection = read.value
        val candidate = apiFactory.create()
        val candidates =
            try {
                candidate.api.jellyfin.discovery.getAddressCandidates(address)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                rethrowIfStale(attempt, error)
                throw error
            }
        val bootstrap =
            resolveServerBootstrap(
                address = address,
                candidates = candidates,
                loadPublicInfo = { baseUrl ->
                    withContext(Dispatchers.IO) {
                        val probe = apiFactory.create(baseUrl = baseUrl)
                        val systemInfo by probe.api.systemApi.getPublicSystemInfo()
                        systemInfo
                    }
                },
                loadRecommendedServers = {
                    try {
                        candidate.api.jellyfin.discovery.getRecommendedServers(
                            candidates,
                            RecommendedServerInfoScore.OK,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        rethrowIfStale(attempt, error)
                        throw certificateTrustRequiredForAttempt(attempt, candidates) ?: error
                    }
                },
            )
        ensureIngressCurrent(attempt)

        if (bootstrap is ServerBootstrapResult.Explicit) {
            return persistAndCommitServer(
                attempt,
                bootstrap.systemInfo,
                bootstrap.address,
                currentSelection.offlineMode,
            )
        }
        val recommended = (bootstrap as ServerBootstrapResult.Discovered).servers
        val goodServers = mutableListOf<RecommendedServerInfo>()
        val okServers = mutableListOf<RecommendedServerInfo>()

        for (recommendedServerInfo in recommended) {
            when (recommendedServerInfo.score) {
                RecommendedServerInfoScore.GREAT -> {
                    return persistAndCommitServer(
                        attempt,
                        recommendedServerInfo,
                        currentSelection.offlineMode,
                    )
                }
                RecommendedServerInfoScore.GOOD -> goodServers.add(recommendedServerInfo)
                RecommendedServerInfoScore.OK -> okServers.add(recommendedServerInfo)
                RecommendedServerInfoScore.BAD -> Unit
            }
        }

        when {
            goodServers.isNotEmpty() -> {
                return persistAndCommitServer(
                    attempt,
                    goodServers.first(),
                    currentSelection.offlineMode,
                )
            }
            okServers.isNotEmpty() -> {
                val okServer = okServers.first()
                throw ExceptionUiTexts(createIssuesString(okServer))
            }
            else -> {
                throw (certificateTrustRequiredForAttempt(attempt, candidates)
                    ?: ExceptionUiText(
                        UiText.StringResource(SetupR.string.add_server_error_not_found)
                    ))
            }
        }
    }

    private suspend fun persistAndCommitServer(
        attempt: IdentityIngressAttempt,
        recommendedServerInfo: RecommendedServerInfo,
        offlineMode: Boolean,
    ): Server {
        val serverInfo =
            recommendedServerInfo.systemInfo.getOrNull()
                ?: throw ExceptionUiText(
                    UiText.StringResource(SetupR.string.add_server_error_no_id)
                )
        return persistAndCommitServer(
            attempt,
            serverInfo,
            recommendedServerInfo.address,
            offlineMode,
        )
    }

    private suspend fun persistAndCommitServer(
        attempt: IdentityIngressAttempt,
        serverInfo: PublicSystemInfo,
        address: String,
        offlineMode: Boolean,
    ): Server {
        val serverId = requireNotNull(serverInfo.id)
        val addressId = stableAddressId(serverId, address)
        val initialPlan = planServerPersistence(serverInfo, address, addressId)
        val selectionUser = currentUserFor(initialPlan.server)
        val selection =
            IdentitySelection(
                serverId = initialPlan.server.id,
                userId = selectionUser?.id?.toString(),
                addressId = initialPlan.address.id.toString(),
                offlineMode = offlineMode,
            )

        return commitServerSelection(attempt, selection) { snapshot ->
            val latestPlan = planServerPersistence(serverInfo, address, addressId)
            val latestUser = currentUserFor(latestPlan.server)
            requireProjectionSelection(
                snapshot = snapshot,
                serverId = latestPlan.server.id,
                addressId = latestPlan.address.id,
                userId = latestUser?.id,
            )
            val persisted = applyServerPersistence(latestPlan)
            publishGlobalIdentity(
                baseUrl = persisted.address.address,
                accessToken = latestUser?.accessToken,
                userId = latestUser?.id,
            )
            persisted.server
        }
    }

    private suspend fun commitServerSelection(
        attempt: IdentityIngressAttempt,
        selection: IdentitySelection,
        effect: suspend (IdentitySnapshot) -> Server,
    ): Server {
        var projectionFailure: Exception? = null
        var persisted: Server? = null
        val result =
            requireConditionalWriter().selectServerIfGeneration(attempt, selection) {
                snapshot,
                permit ->
                if (
                    !permit.commit {
                        try {
                            persisted = effect(snapshot)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            projectionFailure = error
                            throw error
                        }
                    }
                ) {
                    throw IdentityAttemptStaleException()
                }
            }

        projectionFailure?.let { throw it }
        requireApplied(result)
        return requireNotNull(persisted)
    }

    private suspend fun planServerPersistence(
        serverInfo: PublicSystemInfo,
        address: String,
        addressId: UUID,
    ): ServerPersistencePlan {
        Timber.d("Connecting to server: ${serverInfo.serverName}")
        val serverId = requireNotNull(serverInfo.id)
        val serverInDatabase = database.getServer(serverId)

        if (serverInDatabase != null) {
            val addresses = database.getServerWithAddresses(serverId).addresses
            val existingAddress = addresses.firstOrNull { it.address == address }
            val selectedAddress =
                existingAddress
                    ?: ServerAddress(
                        id = addressId,
                        serverId = serverId,
                        address = address,
                    )
            return ServerPersistencePlan(
                server = serverInDatabase.copy(currentServerAddressId = selectedAddress.id),
                address = selectedAddress,
                insertServer = false,
                insertAddress = existingAddress == null,
                updateServer = true,
            )
        }

        val serverAddress =
            ServerAddress(
                id = addressId,
                serverId = serverId,
                address = address,
            )
        val server =
            Server(
                id = serverId,
                name = requireNotNull(serverInfo.serverName),
                currentServerAddressId = serverAddress.id,
                currentUserId = null,
            )
        return ServerPersistencePlan(
            server = server,
            address = serverAddress,
            insertServer = true,
            insertAddress = true,
            updateServer = false,
        )
    }

    private suspend fun applyServerPersistence(plan: ServerPersistencePlan): PersistedServer {
        if (plan.insertServer) {
            database.insertServer(plan.server)
        }
        if (plan.insertAddress) {
            database.insertServerAddress(plan.address)
        }
        if (plan.updateServer) {
            database.updateServer(plan.server)
        }
        return PersistedServer(server = plan.server, address = plan.address)
    }

    private fun createIssuesString(server: RecommendedServerInfo): Collection<UiText> =
        server.issues.map {
            when (it) {
                is RecommendedServerIssue.OutdatedServerVersion -> {
                    UiText.StringResource(SetupR.string.add_server_error_outdated, it.version)
                }
                is RecommendedServerIssue.InvalidProductName -> {
                    UiText.StringResource(
                        SetupR.string.add_server_error_not_jellyfin,
                        it.productName ?: "",
                    )
                }
                is RecommendedServerIssue.UnsupportedServerVersion -> {
                    UiText.StringResource(SetupR.string.add_server_error_version, it.version)
                }
                is RecommendedServerIssue.SlowResponse -> {
                    UiText.StringResource(SetupR.string.add_server_error_slow, it.responseTime)
                }
                else -> UiText.StringResource(CoreR.string.unknown_error)
            }
        }

    override suspend fun loadDisclaimer(): String? =
        withContext(Dispatchers.IO) {
            withVerifiedReadyContext { context ->
                val candidate = apiFactory.create(baseUrl = context.address.address)
                candidate.api.brandingApi.getBrandingOptions().content.loginDisclaimer
            }
        }

    override suspend fun login(username: String, password: String) {
        withContext(Dispatchers.IO) {
            val read =
                readUnderReadyLease { selection -> loadCurrentServerContext(selection) }
                    ?: recoveringIdentityFailure()
            val context = read.value ?: recoveringIdentityFailure()
            val attempt = read.port.beginIngressForLease(read.lease) ?: recoveringIdentityFailure()
            val candidate = apiFactory.create(baseUrl = context.address.address)
            val authenticationResult =
                try {
                    candidate.api.userApi
                        .authenticateUserByName(
                            AuthenticateUserByName(username = username, pw = password)
                        )
                        .content
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    rethrowIfStale(attempt, error)
                    throw certificateTrustRequiredForCurrentServer() ?: error
                }

            commitAuthenticatedUser(
                attempt = attempt,
                context = context,
                authenticationResult = authenticationResult,
            )
        }
    }

    override suspend fun loginWithSecret(secret: String) {
        withContext(Dispatchers.IO) {
            val read =
                readUnderReadyLease { selection -> loadCurrentServerContext(selection) }
                    ?: recoveringIdentityFailure()
            val context = read.value ?: recoveringIdentityFailure()
            val attempt = read.port.beginIngressForLease(read.lease) ?: recoveringIdentityFailure()
            val candidate = apiFactory.create(baseUrl = context.address.address)
            val authenticationResult =
                try {
                    candidate.api.userApi
                        .authenticateWithQuickConnect(QuickConnectDto(secret = secret))
                        .content
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    rethrowIfStale(attempt, error)
                    throw certificateTrustRequiredForCurrentServer() ?: error
                }

            commitAuthenticatedUser(
                attempt = attempt,
                context = context,
                authenticationResult = authenticationResult,
            )
        }
    }

    private suspend fun commitAuthenticatedUser(
        attempt: IdentityIngressAttempt,
        context: ServerContext,
        authenticationResult: AuthenticationResult,
    ) {
        val authenticatedUser =
            authenticationResult.user
                ?: throw SavedUserAuthenticationException(
                    SavedUserActivationFailure.USER_UNAVAILABLE
                )
        val serverId =
            authenticationResult.serverId
                ?: throw SavedUserAuthenticationException(
                    SavedUserActivationFailure.USER_UNAVAILABLE
                )
        require(serverId == context.server.id) { "Authenticated server does not match selection" }
        val user =
            User(
                id = authenticatedUser.id,
                name = requireNotNull(authenticatedUser.name),
                serverId = serverId,
                accessToken = requireNotNull(authenticationResult.accessToken),
            )
        val selection =
            IdentitySelection(
                serverId = user.serverId,
                userId = user.id.toString(),
                addressId = context.address.id.toString(),
                offlineMode = false,
            )
        var projectionFailure: Exception? = null
        val result =
            requireConditionalWriter().selectAuthenticatedUserIfGeneration(attempt, selection) {
                snapshot,
                permit ->
                if (
                    !permit.commit {
                        try {
                            requireProjectionSelection(
                                snapshot = snapshot,
                                serverId = serverId,
                                addressId = context.address.id,
                                userId = user.id,
                            )
                            database.insertUser(user)
                            database.updateServerCurrentUser(serverId, user.id)
                            publishGlobalIdentity(
                                baseUrl = context.address.address,
                                accessToken = user.accessToken,
                                userId = user.id,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            projectionFailure = error
                            throw error
                        }
                    }
                ) {
                    throw IdentityAttemptStaleException()
                }
            }
        projectionFailure?.let { throw it }
        requireApplied(result)
    }

    override suspend fun getUsers(serverId: String): List<User> = database.getUsers(serverId)

    override suspend fun getPublicUsers(serverId: String): List<User> =
        withContext(Dispatchers.IO) {
            withVerifiedReadyContext(serverId) { context ->
                val candidate = apiFactory.create(baseUrl = context.address.address)
                candidate.api.userApi.getPublicUsers().content.mapNotNull {
                    User(
                        id = it.id,
                        name = it.name ?: return@mapNotNull null,
                        serverId = serverId,
                    )
                }
            } ?: emptyList()
        }

    override suspend fun getCurrentUser(): User? = withReadyLease { selection ->
        selection.serverId?.let { database.getServerCurrentUser(it) }
    }

    override suspend fun authenticate(userId: UUID): User =
        withContext(Dispatchers.IO) {
            val read =
                readUnderReadyLease { canonicalSelection ->
                    loadSavedUserContext(canonicalSelection, userId)
                } ?: recoveringIdentityFailure()
            val saved = read.value ?: recoveringIdentityFailure()
            val attempt = read.port.beginIngressForLease(read.lease) ?: recoveringIdentityFailure()
            authenticateCandidate(attempt, saved.context, saved.user)
            saved.user
        }

    override suspend fun activateSavedUser(userId: UUID): SavedUserActivationResult =
        withContext(Dispatchers.IO) {
            val read =
                try {
                    readUnderReadyLease { canonicalSelection ->
                        loadSavedUserContext(canonicalSelection, userId)
                    } ?: recoveringIdentityFailure()
                } catch (error: SavedUserAuthenticationException) {
                    return@withContext SavedUserActivationResult.Rejected(error.failure)
                }
            val (context, user) =
                read.value
                    ?: return@withContext SavedUserActivationResult.Rejected(
                        SavedUserActivationFailure.USER_UNAVAILABLE
                    )
            val attempt = read.port.beginIngressForLease(read.lease) ?: recoveringIdentityFailure()

            try {
                authenticateCandidate(attempt, context, user)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SavedUserAuthenticationException) {
                return@withContext SavedUserActivationResult.Rejected(error.failure)
            } catch (error: Exception) {
                rethrowIfStale(attempt, error)
                return@withContext SavedUserActivationResult.Rejected(
                    classifySavedUserAuthenticationError(error)
                )
            }

            val selection =
                IdentitySelection(
                    serverId = user.serverId,
                    userId = user.id.toString(),
                    addressId = context.address.id.toString(),
                    offlineMode = false,
                )
            var projectionFailure: Exception? = null
            val result =
                requireConditionalWriter().selectSavedUserIfGeneration(attempt, selection) {
                    snapshot,
                    permit ->
                    if (
                        !permit.commit {
                            try {
                                requireProjectionSelection(
                                    snapshot = snapshot,
                                    serverId = user.serverId,
                                    addressId = context.address.id,
                                    userId = user.id,
                                )
                                database.updateServerCurrentUser(user.serverId, user.id)
                                publishGlobalIdentity(
                                    baseUrl = context.address.address,
                                    accessToken = user.accessToken,
                                    userId = user.id,
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                projectionFailure = error
                                throw error
                            }
                        }
                    ) {
                        throw IdentityAttemptStaleException()
                    }
                }
            projectionFailure?.let { throw it }
            when (result) {
                IdentityTransitionResult.APPLIED,
                IdentityTransitionResult.NO_OP -> SavedUserActivationResult.Activated(user)
                IdentityTransitionResult.STALE_DISCARDED -> throw IdentityAttemptStaleException()
                IdentityTransitionResult.RECOVERING,
                IdentityTransitionResult.FAILED -> throw IdentityTransitionFailedException(result)
            }
        }

    private suspend fun authenticateCandidate(
        attempt: IdentityIngressAttempt,
        context: ServerContext,
        user: User,
    ) {
        val accessToken =
            user.accessToken?.takeIf { it.isNotBlank() }
                ?: throw SavedUserAuthenticationException(SavedUserActivationFailure.TOKEN_EXPIRED)
        val candidate =
            apiFactory.create(
                baseUrl = context.address.address,
                accessToken = accessToken,
                userId = user.id,
            )
        val remoteUser =
            try {
                candidate.api.userApi.getCurrentUser().content
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                rethrowIfStale(attempt, error)
                throw error
            }
        ensureIngressCurrent(attempt)
        if (remoteUser.id != user.id) {
            throw SavedUserAuthenticationException(SavedUserActivationFailure.USER_UNAVAILABLE)
        }
    }

    override suspend fun deleteUser(userId: UUID) {
        val readPort = resolveReadLeasePort()
        val lease = readPort?.acquireReadyLease() ?: return
        val attempt = readPort.beginIngressForLease(lease) ?: return
        val currentSelection = lease.selection
        val user = database.getUser(userId) ?: return
        val server = database.getServer(user.serverId)
        val context =
            server
                ?.takeIf { currentSelection.serverId == it.id }
                ?.let { loadCurrentServerContext(currentSelection, it.id) }
        val nextUser =
            if (
                server != null &&
                    currentSelection.serverId == server.id &&
                    server.currentUserId == userId
            ) {
                val remainingUsers = database.getUsers(server.id)
                nextCurrentUserIdAfterDelete(
                        remainingUsers,
                        userId,
                        server.currentUserId,
                    )
                    ?.let { database.getUser(it) }
            } else {
                null
            }
        ensureIngressCurrent(attempt)
        if (server == null) {
            val result =
                requireConditionalWriter().deleteSelectionIfGeneration(attempt, currentSelection) {
                    _,
                    permit ->
                    permit.commit { database.deleteUser(userId) }
                }
            requireApplied(result)
            return
        }

        if (server.currentUserId != userId) {
            val result =
                requireConditionalWriter().deleteSelectionIfGeneration(attempt, currentSelection) {
                    _,
                    permit ->
                    permit.commit { database.deleteUser(userId) }
                }
            requireApplied(result)
            return
        }

        if (currentSelection.serverId != server.id) {
            val result =
                requireConditionalWriter().deleteSelectionIfGeneration(attempt, currentSelection) {
                    _,
                    permit ->
                    permit.commit {
                        server.currentUserId = null
                        database.updateServer(server)
                        database.deleteUser(userId)
                    }
                }
            requireApplied(result)
            return
        }

        val currentContext = context ?: return
        val selection =
            IdentitySelection(
                serverId = server.id,
                userId = nextUser?.id?.toString(),
                addressId = currentContext.address.id.toString(),
                offlineMode = currentSelection.offlineMode,
            )
        var projectionFailure: Exception? = null
        val result =
            requireConditionalWriter().deleteSelectionIfGeneration(attempt, selection) {
                snapshot,
                permit ->
                if (
                    !permit.commit {
                        try {
                            requireProjectionSelection(
                                snapshot = snapshot,
                                serverId = server.id,
                                addressId = currentContext.address.id,
                                userId = nextUser?.id,
                            )
                            database.deleteUser(userId)
                            server.currentUserId = nextUser?.id
                            database.updateServer(server)
                            publishGlobalIdentity(
                                baseUrl = currentContext.address.address,
                                accessToken = nextUser?.accessToken,
                                userId = nextUser?.id,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            projectionFailure = error
                            throw error
                        }
                    }
                ) {
                    throw IdentityAttemptStaleException()
                }
            }
        projectionFailure?.let { throw it }
        requireApplied(result)
    }

    override suspend fun setCurrentUser(userId: UUID) {
        when (val result = activateSavedUser(userId)) {
            is SavedUserActivationResult.Activated -> Unit
            is SavedUserActivationResult.Rejected -> {
                throw SavedUserAuthenticationException(result.failure)
            }
        }
    }

    override suspend fun setCurrentAddress(addressId: UUID) {
        val read =
            readUnderReadyLease { currentSelection ->
                val context =
                    loadCurrentServerContext(currentSelection) ?: return@readUnderReadyLease null
                val address =
                    runCatching { database.getAddress(addressId) }.getOrNull()
                        ?: return@readUnderReadyLease null
                if (address.serverId != context.server.id) return@readUnderReadyLease null
                Triple(currentSelection, context, address)
            } ?: return
        val (currentSelection, context, address) = read.value ?: return
        val attempt = read.port.beginIngressForLease(read.lease) ?: return
        val selection =
            IdentitySelection(
                serverId = context.server.id,
                userId = context.user?.id?.toString(),
                addressId = address.id.toString(),
                offlineMode = currentSelection.offlineMode,
            )
        var projectionFailure: Exception? = null
        val result =
            requireConditionalWriter().selectAddressIfGeneration(attempt, selection) {
                snapshot,
                permit ->
                if (
                    !permit.commit {
                        try {
                            requireProjectionSelection(
                                snapshot = snapshot,
                                serverId = context.server.id,
                                addressId = address.id,
                                userId = context.user?.id,
                            )
                            context.server.currentServerAddressId = address.id
                            database.updateServer(context.server)
                            publishGlobalIdentity(
                                baseUrl = address.address,
                                accessToken = context.user?.accessToken,
                                userId = context.user?.id,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            projectionFailure = error
                            throw error
                        }
                    }
                ) {
                    throw IdentityAttemptStaleException()
                }
            }
        projectionFailure?.let { throw it }
        requireApplied(result)
    }

    override suspend fun trustCertificate(trustKey: String, fingerprint: String) {
        jellyfinApi.certificateTrustStore.trust(trustKey, fingerprint)
    }

    override suspend fun getTrustedCertificates(): List<TrustedCertificate> =
        withReadyLease { jellyfinApi.certificateTrustStore.trustedCertificates() } ?: emptyList()

    override suspend fun clearTrustedCertificate(trustKey: String) {
        jellyfinApi.certificateTrustStore.clear(trustKey)
    }

    private suspend fun ensureIngressCurrent(attempt: IdentityIngressAttempt) {
        if (!requireConditionalWriter().isIngressCurrent(attempt)) {
            throw IdentityAttemptStaleException()
        }
    }

    private suspend fun rethrowIfStale(
        attempt: IdentityIngressAttempt,
        cause: Throwable,
    ) {
        try {
            ensureIngressCurrent(attempt)
        } catch (stale: IdentityAttemptStaleException) {
            stale.initCause(cause)
            throw stale
        }
    }

    private suspend fun certificateTrustRequiredForAttempt(
        attempt: IdentityIngressAttempt,
        candidates: Collection<String>,
    ): CertificateTrustRequiredException? {
        val certificateTrust =
            try {
                certificateTrustRequired(candidates)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                rethrowIfStale(attempt, error)
                throw error
            }
        ensureIngressCurrent(attempt)
        return certificateTrust
    }

    private suspend fun currentUserFor(server: Server): User? =
        server.currentUserId?.let { database.getUser(it) }
            ?: database.getServerCurrentUser(server.id)

    private suspend fun loadCurrentServerContext(
        canonicalSelection: IdentitySelection,
        serverId: String? = null,
    ): ServerContext? {
        val selectedServerId = serverId ?: canonicalSelection.serverId ?: return null
        val server =
            database.getServer(selectedServerId)
                ?: throw IllegalStateException(
                    "Cannot resolve setup identity: server $selectedServerId is missing"
                )
        val canonicalAddress =
            canonicalSelection
                .takeIf { serverId == null && it.serverId == server.id }
                ?.addressId
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?.let { runCatching { database.getAddress(it) }.getOrNull() }
                ?.takeIf { it.serverId == server.id }
        val address =
            canonicalAddress
                ?: server.currentServerAddressId?.let {
                    runCatching { database.getAddress(it) }.getOrNull()
                }
                ?: throw IllegalStateException(
                    "Cannot resolve setup identity: current address missing for ${server.id}"
                )
        return ServerContext(
            server = server,
            address = address,
            user = currentUserFor(server),
        )
    }

    private suspend fun loadSavedUserContext(
        canonicalSelection: IdentitySelection,
        userId: UUID,
    ): SavedUserContext? {
        val context = loadCurrentServerContext(canonicalSelection) ?: return null
        val user =
            database.getUser(userId)
                ?: throw SavedUserAuthenticationException(
                    SavedUserActivationFailure.USER_UNAVAILABLE
                )
        if (user.serverId != context.server.id) {
            throw SavedUserAuthenticationException(SavedUserActivationFailure.USER_UNAVAILABLE)
        }
        return SavedUserContext(context = context, user = user)
    }

    private suspend fun <T> withReadyLease(effect: suspend (IdentitySelection) -> T): T? {
        val port = resolveReadLeasePort() ?: return null
        val lease = port.acquireReadyLease() ?: return null
        return port.withReadyLease(lease, effect)
    }

    private suspend fun <T> readUnderReadyLease(
        effect: suspend (IdentitySelection) -> T
    ): LeasedRead<T>? {
        val port = resolveReadLeasePort() ?: return null
        val lease = port.acquireReadyLease() ?: return null
        val value = port.withReadyLease(lease, effect) ?: return null
        return LeasedRead(port = port, lease = lease, value = value)
    }

    private suspend fun <T> withVerifiedReadyContext(
        serverId: String? = null,
        effect: suspend (ServerContext) -> T,
    ): T? {
        val read =
            readUnderReadyLease { canonicalSelection ->
                loadCurrentServerContext(canonicalSelection, serverId)
            } ?: return null
        val context = read.value ?: return null
        val result =
            try {
                effect(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!read.port.isReadyLeaseCurrent(read.lease)) {
                    throw IdentityAttemptStaleException().apply { initCause(error) }
                }
                throw error
            }
        return result.takeIf { read.port.isReadyLeaseCurrent(read.lease) }
    }

    private fun recoveringIdentityFailure(): Nothing =
        throw IdentityTransitionFailedException(IdentityTransitionResult.RECOVERING)

    private suspend fun applyServerProjection(
        serverId: String,
        snapshot: IdentitySnapshot,
    ): Unit =
        withContext(Dispatchers.IO) {
            val relation =
                database.getServerWithAddressAndUser(serverId)
                    ?: throw IllegalStateException(
                        "Cannot select server $serverId: server is missing"
                    )
            val address =
                relation.address
                    ?: throw IllegalStateException(
                        "Cannot select server $serverId: current address is missing"
                    )
            val user = relation.user
            requireProjectionSelection(
                snapshot = snapshot,
                serverId = serverId,
                addressId = address.id,
                userId = user?.id,
            )
            publishGlobalIdentity(
                baseUrl = address.address,
                accessToken = user?.accessToken,
                userId = user?.id,
            )
        }

    private fun requireProjectionSelection(
        snapshot: IdentitySnapshot,
        serverId: String,
        addressId: UUID,
        userId: UUID?,
    ) {
        check(snapshot.selection.serverId == serverId) {
            "Projection permit server does not match server $serverId"
        }
        check(snapshot.selection.addressId == addressId.toString()) {
            "Projection permit address does not match address $addressId"
        }
        check(snapshot.selection.userId == userId?.toString()) {
            "Projection permit user does not match user $userId"
        }
    }

    private fun publishGlobalIdentity(baseUrl: String, accessToken: String?, userId: UUID?) {
        apiFactory.publish(
            JellyfinApiCandidate(
                api = jellyfinApi,
                baseUrl = baseUrl,
                accessToken = accessToken,
                userId = userId,
            )
        )
    }

    private fun resolveReadLeasePort(): IdentityReadLeasePort? =
        when {
            identityReadLeasePort != null -> identityReadLeasePort
            identityReadPort != null -> identityReadPort as? IdentityReadLeasePort
            identityConditionalWritePort != null ->
                identityConditionalWritePort as? IdentityReadLeasePort
            else -> fallbackCoordinator
        }

    private fun requireConditionalWriter(): IdentityConditionalWritePort =
        identityConditionalWritePort ?: fallbackCoordinator

    private fun requireApplied(result: IdentityTransitionResult) {
        when (result) {
            IdentityTransitionResult.APPLIED,
            IdentityTransitionResult.NO_OP -> Unit
            IdentityTransitionResult.STALE_DISCARDED -> throw IdentityAttemptStaleException()
            IdentityTransitionResult.RECOVERING,
            IdentityTransitionResult.FAILED -> throw IdentityTransitionFailedException(result)
        }
    }

    private fun stableAddressId(serverId: String, address: String): UUID =
        UUID.nameUUIDFromBytes("$serverId\u0000$address".toByteArray(StandardCharsets.UTF_8))

    private suspend fun certificateTrustRequired(
        candidates: Collection<String>
    ): CertificateTrustRequiredException? =
        withContext(Dispatchers.IO) {
            candidates
                .asSequence()
                .mapNotNull { httpsTargetOf(it) }
                .distinct()
                .mapNotNull { (host, port) ->
                    val info =
                        jellyfinApi.certificateProbe.probe(host, port) ?: return@mapNotNull null
                    if (info.systemTrusted) return@mapNotNull null

                    val previous =
                        jellyfinApi.certificateTrustStore.trustedFingerprint(info.trustKey)
                    if (previous != null && fingerprintsMatch(previous, info.fingerprint)) {
                        return@mapNotNull null
                    }

                    CertificateTrustRequiredException(
                        host = info.host,
                        port = info.port,
                        fingerprint = info.fingerprint,
                        previousFingerprint = previous,
                    )
                }
                .firstOrNull()
        }

    private suspend fun certificateTrustRequiredForCurrentServer():
        CertificateTrustRequiredException? {
        val read =
            readUnderReadyLease { selection ->
                selection.serverId?.let { serverId ->
                    runCatching {
                        database.getServerWithAddressAndUser(serverId)?.address?.address
                    }
                        .getOrNull()
                }
            } ?: return null
        val address = read.value ?: return null
        return certificateTrustRequired(listOf(address)).takeIf {
            read.port.isReadyLeaseCurrent(read.lease)
        }
    }
}
