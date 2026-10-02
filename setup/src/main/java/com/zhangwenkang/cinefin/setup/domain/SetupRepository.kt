package com.zhangwenkang.cinefin.setup.domain

import com.zhangwenkang.cinefin.models.Server
import com.zhangwenkang.cinefin.models.ServerWithAddresses
import com.zhangwenkang.cinefin.models.User
import com.zhangwenkang.cinefin.network.TrustedCertificate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo

interface SetupRepository {
    fun discoverServers(): Flow<ServerDiscoveryInfo>

    suspend fun getServers(): List<ServerWithAddresses>

    suspend fun getCurrentServer(): Server?

    suspend fun deleteServer(serverId: String)

    suspend fun getIsQuickConnectEnabled(): Boolean

    suspend fun initiateQuickConnect(): QuickConnectResult

    suspend fun getQuickConnectState(secret: String): QuickConnectResult

    suspend fun setCurrentServer(serverId: String)

    suspend fun addServer(address: String): Server

    suspend fun loadDisclaimer(): String?

    suspend fun login(username: String, password: String)

    suspend fun loginWithSecret(secret: String)

    suspend fun getUsers(serverId: String): List<User>

    suspend fun getPublicUsers(serverId: String): List<User>

    suspend fun getCurrentUser(): User?

    suspend fun deleteUser(userId: UUID)

    suspend fun setCurrentUser(userId: UUID)

    suspend fun setCurrentAddress(addressId: UUID)

    /** 用户确认信任某地址的证书指纹（TOFU）。 */
    suspend fun trustCertificate(trustKey: String, fingerprint: String)

    /** 已信任的自签证书列表（服务器管理界面展示 / 清除）。 */
    suspend fun getTrustedCertificates(): List<TrustedCertificate>

    /** 清除某地址的信任；清除后该地址立即回到默认证书校验。 */
    suspend fun clearTrustedCertificate(trustKey: String)
}
