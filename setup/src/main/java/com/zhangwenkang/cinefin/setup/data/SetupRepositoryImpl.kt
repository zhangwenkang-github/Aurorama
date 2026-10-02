package com.zhangwenkang.cinefin.setup.data

import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
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
import com.zhangwenkang.cinefin.setup.domain.SetupRepository
import com.zhangwenkang.cinefin.setup.domain.nextCurrentUserIdAfterDelete
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.discovery.RecommendedServerIssue
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import timber.log.Timber

class SetupRepositoryImpl(
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
) : SetupRepository {
    override fun discoverServers(): Flow<ServerDiscoveryInfo> {
        return jellyfinApi.jellyfin.discovery.discoverLocalServers()
    }

    override suspend fun getServers(): List<ServerWithAddresses> {
        return database.getServersWithAddresses()
    }

    override suspend fun getCurrentServer(): Server? {
        return appPreferences.getValue(appPreferences.currentServer)?.let { id ->
            database.getServer(id)
        }
    }

    override suspend fun deleteServer(serverId: String) {
        database.deleteServer(serverId)
    }

    override suspend fun getIsQuickConnectEnabled(): Boolean =
        withContext(Dispatchers.IO) { jellyfinApi.quickConnectApi.getQuickConnectEnabled().content }

    override suspend fun initiateQuickConnect(): QuickConnectResult =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.quickConnectApi.initiateQuickConnect().content
            } catch (e: Exception) {
                throw certificateTrustRequiredForCurrentServer() ?: e
            }
        }

    override suspend fun getQuickConnectState(secret: String): QuickConnectResult =
        withContext(Dispatchers.IO) {
            jellyfinApi.quickConnectApi.getQuickConnectState(secret).content
        }

    override suspend fun setCurrentServer(serverId: String) {
        val serverWithAddressAndUser = database.getServerWithAddressAndUser(serverId) ?: return
        val serverAddress = serverWithAddressAndUser.address ?: return
        val user = serverWithAddressAndUser.user

        jellyfinApi.apply {
            api.update(baseUrl = serverAddress.address, accessToken = user?.accessToken)
            userId = user?.id
        }
    }

    override suspend fun addServer(address: String): Server {
        // Check if address is not blank
        if (address.isBlank()) {
            throw ExceptionUiText(
                UiText.StringResource(SetupR.string.add_server_error_empty_address)
            )
        }

        val candidates = jellyfinApi.jellyfin.discovery.getAddressCandidates(address)
        val recommended =
            try {
                jellyfinApi.jellyfin.discovery.getRecommendedServers(
                    candidates,
                    RecommendedServerInfoScore.OK,
                )
            } catch (e: Exception) {
                throw certificateTrustRequired(candidates) ?: e
            }
        val goodServers = mutableListOf<RecommendedServerInfo>()
        val okServers = mutableListOf<RecommendedServerInfo>()

        for (recommendedServerInfo in recommended) {
            when (recommendedServerInfo.score) {
                RecommendedServerInfoScore.GREAT -> {
                    return saveServerInDatabase(recommendedServerInfo)
                }
                RecommendedServerInfoScore.GOOD -> goodServers.add(recommendedServerInfo)
                RecommendedServerInfoScore.OK -> okServers.add(recommendedServerInfo)
                RecommendedServerInfoScore.BAD -> Unit
            }
        }

        when {
            goodServers.isNotEmpty() -> {
                return saveServerInDatabase(goodServers.first())
            }
            okServers.isNotEmpty() -> {
                val okServer = okServers.first()
                throw ExceptionUiTexts(createIssuesString(okServer))
            }
            else -> {
                throw (certificateTrustRequired(candidates)
                    ?: ExceptionUiText(
                        UiText.StringResource(SetupR.string.add_server_error_not_found)
                    ))
            }
        }
    }

    private suspend fun saveServerInDatabase(recommendedServerInfo: RecommendedServerInfo): Server {
        val serverInfo =
            recommendedServerInfo.systemInfo.getOrNull()
                ?: throw ExceptionUiText(
                    UiText.StringResource(SetupR.string.add_server_error_no_id)
                )

        Timber.d("Connecting to server: ${serverInfo.serverName}")

        val serverInDatabase = database.getServer(serverInfo.id!!)

        // Check if server is already in the database
        // If so only add a new address to that server if it's different
        val server =
            if (serverInDatabase != null) {
                val addresses = database.getServerWithAddresses(serverInDatabase.id).addresses
                // If address is not in database, add it
                if (addresses.none { it.address == recommendedServerInfo.address }) {
                    val serverAddress =
                        ServerAddress(
                            id = UUID.randomUUID(),
                            serverId = serverInDatabase.id,
                            address = recommendedServerInfo.address,
                        )

                    database.insertServerAddress(serverAddress)
                }
                serverInDatabase
            } else {
                val serverAddress =
                    ServerAddress(
                        id = UUID.randomUUID(),
                        serverId = serverInfo.id!!,
                        address = recommendedServerInfo.address,
                    )

                val server =
                    Server(
                        id = serverInfo.id!!,
                        name = serverInfo.serverName!!,
                        currentServerAddressId = serverAddress.id,
                        currentUserId = null,
                    )

                database.insertServer(server)
                database.insertServerAddress(serverAddress)
                server
            }

        jellyfinApi.apply {
            api.update(baseUrl = recommendedServerInfo.address, accessToken = null)
        }

        return server
    }

    /**
     * Create a presentable string of issues with a server
     *
     * @param server The server with issues
     * @return A presentable string of issues separated with \n
     */
    private fun createIssuesString(server: RecommendedServerInfo): Collection<UiText> {
        return server.issues.map {
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
                else -> {
                    UiText.StringResource(CoreR.string.unknown_error)
                }
            }
        }
    }

    override suspend fun loadDisclaimer(): String? =
        withContext(Dispatchers.IO) {
            jellyfinApi.brandingApi.getBrandingOptions().content.loginDisclaimer
        }

    override suspend fun login(username: String, password: String) {
        withContext(Dispatchers.IO) {
            val authenticationResult =
                try {
                    val result by
                        jellyfinApi.userApi.authenticateUserByName(
                            data = AuthenticateUserByName(username = username, pw = password)
                        )
                    result
                } catch (e: Exception) {
                    throw certificateTrustRequiredForCurrentServer() ?: e
                }

            saveAuthenticationResult(authenticationResult)
        }
    }

    override suspend fun loginWithSecret(secret: String) {
        withContext(Dispatchers.IO) {
            val authenticationResult =
                try {
                    val result by
                        jellyfinApi.userApi.authenticateWithQuickConnect(
                            data = QuickConnectDto(secret = secret)
                        )
                    result
                } catch (e: Exception) {
                    throw certificateTrustRequiredForCurrentServer() ?: e
                }

            saveAuthenticationResult(authenticationResult)
        }
    }

    private suspend fun saveAuthenticationResult(authenticationResult: AuthenticationResult) {
        val user =
            User(
                id = authenticationResult.user!!.id,
                name = authenticationResult.user!!.name!!,
                serverId = authenticationResult.serverId!!,
                accessToken = authenticationResult.accessToken!!,
            )

        database.insertUser(user)
        database.updateServerCurrentUser(authenticationResult.serverId!!, user.id)

        jellyfinApi.apply {
            api.update(accessToken = authenticationResult.accessToken)
            userId = authenticationResult.user?.id
        }
    }

    override suspend fun getUsers(serverId: String): List<User> {
        return database.getUsers(serverId)
    }

    override suspend fun getPublicUsers(serverId: String): List<User> =
        withContext(Dispatchers.IO) {
            jellyfinApi.userApi.getPublicUsers().content.mapNotNull {
                User(id = it.id, name = it.name ?: return@mapNotNull null, serverId = serverId)
            }
        }

    override suspend fun getCurrentUser(): User? {
        val currentServer = getCurrentServer() ?: return null
        return database.getServerCurrentUser(currentServer.id)
    }

    override suspend fun deleteUser(userId: UUID) {
        val user = database.getUser(userId) ?: return
        val server = database.getServer(user.serverId)
        database.deleteUser(userId)

        // 删除的就是当前用户时不能让 servers.currentUserId 悬空：有其它用户就顺延到第一个，
        // 没有就清空令牌（用户页会回到「添加用户」状态，而不是下次启动拿空令牌卡住）。
        if (server?.currentUserId != userId) return

        val remainingUsers = database.getUsers(server.id)
        val nextUser =
            nextCurrentUserIdAfterDelete(remainingUsers, userId, server.currentUserId)?.let {
                database.getUser(it)
            }

        server.currentUserId = nextUser?.id
        database.updateServer(server)

        if (appPreferences.getValue(appPreferences.currentServer) == server.id) {
            jellyfinApi.apply {
                api.update(accessToken = nextUser?.accessToken)
                this.userId = nextUser?.id
            }
        }
    }

    override suspend fun setCurrentUser(userId: UUID) {
        val server = getCurrentServer() ?: return
        val user = database.getUser(userId) ?: return
        if (user.serverId != server.id) return
        server.currentUserId = user.id
        database.updateServer(server)

        jellyfinApi.apply {
            api.update(accessToken = user.accessToken)
            this.userId = user.id
        }
    }

    override suspend fun setCurrentAddress(addressId: UUID) {
        val server = getCurrentServer() ?: return
        val address = database.getAddress(id = addressId)
        if (address.serverId != server.id) {
            return
        }
        server.currentServerAddressId = address.id
        database.updateServer(server)
        jellyfinApi.apply { api.update(baseUrl = address.address) }
    }

    override suspend fun trustCertificate(trustKey: String, fingerprint: String) {
        jellyfinApi.certificateTrustStore.trust(trustKey, fingerprint)
    }

    override suspend fun getTrustedCertificates(): List<TrustedCertificate> =
        jellyfinApi.certificateTrustStore.trustedCertificates()

    override suspend fun clearTrustedCertificate(trustKey: String) {
        jellyfinApi.certificateTrustStore.clear(trustKey)
    }

    /**
     * 发现流程失败时判断原因是否是「证书不受系统信任」：
     *
     * 只读探测候选地址里的 https 端点（不做任何应用层请求），命中一张系统不信任的证书就返回可让用户确认指纹的异常； 否则返回 null，由调用方按原错误处理（服务器离线、不是
     * Jellyfin 等）。
     */
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
                        // 指纹已信任还连不上：问题不在证书，交给调用方按原错误处理。
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

    /** 登录等「已保存服务器」的连接失败时，用当前服务器地址做同样的证书探测。 */
    private suspend fun certificateTrustRequiredForCurrentServer():
        CertificateTrustRequiredException? {
        val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return null
        val address =
            database.getServerWithAddressAndUser(serverId)?.address?.address ?: return null
        return certificateTrustRequired(listOf(address))
    }
}
