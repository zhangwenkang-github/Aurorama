package com.zhangwenkang.cinefin.setup

import com.zhangwenkang.cinefin.models.User
import com.zhangwenkang.cinefin.setup.domain.IdentityPresentation
import com.zhangwenkang.cinefin.setup.domain.OfflineExitDestination
import com.zhangwenkang.cinefin.setup.domain.OfflineIdentityCoordinator
import com.zhangwenkang.cinefin.setup.domain.SavedUserActivationFailure
import com.zhangwenkang.cinefin.setup.domain.SavedUserActivationResult
import com.zhangwenkang.cinefin.setup.domain.SavedUserAuthenticationException
import com.zhangwenkang.cinefin.setup.domain.classifySavedUserAuthenticationError
import com.zhangwenkang.cinefin.setup.domain.resolveIdentityPresentation
import com.zhangwenkang.cinefin.setup.domain.resolveOfflineExitPlan
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineIdentityCoordinatorTest {
    private val userId = UUID.randomUUID()
    private val user =
        User(id = userId, name = "Alice", serverId = "server-1", accessToken = "token")

    @Test
    fun `离线模式优先于记住的 Jellyfin 用户`() {
        assertEquals(IdentityPresentation.OFFLINE, resolveIdentityPresentation(true, userId))
        assertEquals(IdentityPresentation.USER, resolveIdentityPresentation(false, userId))
        assertEquals(
            IdentityPresentation.NOT_SET,
            resolveIdentityPresentation(false, null as UUID?),
        )
        assertEquals(IdentityPresentation.OFFLINE, resolveIdentityPresentation(true, true))
        assertEquals(IdentityPresentation.USER, resolveIdentityPresentation(false, true))
    }

    @Test
    fun `无用户退出离线生成不带登录预填的登录计划`() {
        val plan =
            resolveOfflineExitPlan(
                hasServers = true,
                hasCurrentServer = true,
                hasCurrentUser = false,
            )

        assertEquals(OfflineExitDestination.LOGIN, plan.destination)
        assertTrue(plan.clearOfflineMode)
        assertNull(plan.loginUsername)
    }

    @Test
    fun `有当前用户退出离线回到在线首页`() {
        val plan =
            resolveOfflineExitPlan(
                hasServers = true,
                hasCurrentServer = true,
                hasCurrentUser = true,
            )

        assertEquals(OfflineExitDestination.HOME, plan.destination)
        assertTrue(plan.clearOfflineMode)
    }

    @Test
    fun `保存用户认证成功后才清离线标志`() = runBlocking {
        val events = mutableListOf<String>()
        var offlineMode = true
        val coordinator =
            OfflineIdentityCoordinator(
                authenticator = { requestedId ->
                    assertEquals(userId, requestedId)
                    events += "authenticated:${user.name}"
                    user
                },
                offlineModeController = { enabled ->
                    offlineMode = enabled
                    events += "offline:$enabled"
                },
            )

        val result = coordinator.activateSavedUser(userId)

        assertEquals(SavedUserActivationResult.Activated(user), result)
        assertFalse(offlineMode)
        assertEquals(listOf("authenticated:Alice", "offline:false"), events)
    }

    @Test
    fun `令牌 401 时保持离线并返回失效原因`() = runBlocking {
        var offlineMode = true
        var writes = 0
        val coordinator =
            OfflineIdentityCoordinator(
                authenticator = {
                    throw SavedUserAuthenticationException(SavedUserActivationFailure.TOKEN_EXPIRED)
                },
                offlineModeController = {
                    writes++
                    offlineMode = it
                },
            )

        val result = coordinator.activateSavedUser(userId)

        assertEquals(
            SavedUserActivationResult.Rejected(SavedUserActivationFailure.TOKEN_EXPIRED),
            result,
        )
        assertTrue(offlineMode)
        assertEquals(0, writes)
    }

    @Test
    fun `网络不可用时保持离线并返回连接原因`() = runBlocking {
        var offlineMode = true
        val coordinator =
            OfflineIdentityCoordinator(
                authenticator = {
                    throw SavedUserAuthenticationException(
                        SavedUserActivationFailure.SERVER_UNAVAILABLE
                    )
                },
                offlineModeController = { offlineMode = it },
            )

        val result = coordinator.activateSavedUser(userId)

        assertEquals(
            SavedUserActivationResult.Rejected(SavedUserActivationFailure.SERVER_UNAVAILABLE),
            result,
        )
        assertTrue(offlineMode)
    }

    @Test
    fun `401 与 403 归类为登录失效`() {
        assertEquals(
            SavedUserActivationFailure.TOKEN_EXPIRED,
            classifySavedUserAuthenticationError(InvalidStatusException(401, null)),
        )
        assertEquals(
            SavedUserActivationFailure.TOKEN_EXPIRED,
            classifySavedUserAuthenticationError(InvalidStatusException(403, null)),
        )
    }

    @Test
    fun `网络异常归类为服务器不可用`() {
        assertEquals(
            SavedUserActivationFailure.SERVER_UNAVAILABLE,
            classifySavedUserAuthenticationError(IOException("offline")),
        )
    }

    @Test
    fun `SDK 包装的网络异常沿完整 cause 链归类为服务器不可用`() {
        assertEquals(
            SavedUserActivationFailure.SERVER_UNAVAILABLE,
            classifySavedUserAuthenticationError(
                ApiClientException(
                    "wrapped timeout",
                    TimeoutException("request timeout", UnknownHostException("offline")),
                )
            ),
        )
        assertEquals(
            SavedUserActivationFailure.SERVER_UNAVAILABLE,
            classifySavedUserAuthenticationError(
                ApiClientException("wrapped connect", ConnectException("refused"))
            ),
        )
        assertEquals(
            SavedUserActivationFailure.SERVER_UNAVAILABLE,
            classifySavedUserAuthenticationError(
                TimeoutException("wrapped io", IOException("socket closed"))
            ),
        )
    }

    @Test
    fun `包装的 401 与 403 仍归类为登录失效`() {
        assertEquals(
            SavedUserActivationFailure.TOKEN_EXPIRED,
            classifySavedUserAuthenticationError(
                ApiClientException("wrapped 401", InvalidStatusException(401, null))
            ),
        )
        assertEquals(
            SavedUserActivationFailure.TOKEN_EXPIRED,
            classifySavedUserAuthenticationError(
                ApiClientException("wrapped 403", InvalidStatusException(403, null))
            ),
        )
    }

    @Test
    fun `未知包装异常仍归类为未知`() {
        assertEquals(
            SavedUserActivationFailure.UNKNOWN,
            classifySavedUserAuthenticationError(
                ApiClientException("business failure", IllegalArgumentException("invalid"))
            ),
        )
    }
}
