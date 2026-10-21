package com.zhangwenkang.cinefin.setup.domain

import com.zhangwenkang.cinefin.models.User
import com.zhangwenkang.cinefin.network.CertificateTrustRequiredException
import java.io.IOException
import java.util.UUID
import org.jellyfin.sdk.api.client.exception.InvalidStatusException

/** 设置页身份真源：离线访问模式优先于数据库中记住的用户。 */
enum class IdentityPresentation {
    OFFLINE,
    USER,
    NOT_SET,
}

fun resolveIdentityPresentation(
    isOfflineMode: Boolean,
    currentUserId: UUID?,
): IdentityPresentation =
    when {
        isOfflineMode -> IdentityPresentation.OFFLINE
        currentUserId != null -> IdentityPresentation.USER
        else -> IdentityPresentation.NOT_SET
    }

fun resolveIdentityPresentation(
    isOfflineMode: Boolean,
    hasCurrentUser: Boolean,
): IdentityPresentation =
    when {
        isOfflineMode -> IdentityPresentation.OFFLINE
        hasCurrentUser -> IdentityPresentation.USER
        else -> IdentityPresentation.NOT_SET
    }

enum class OfflineExitDestination {
    HOME,
    LOGIN,
    SERVERS,
    WELCOME,
}

data class OfflineExitPlan(
    val destination: OfflineExitDestination,
    val loginUsername: String?,
    val clearOfflineMode: Boolean,
)

/** 退出离线时的目的地；登录入口从不携带任何用户预填值。 */
fun resolveOfflineExitPlan(
    hasServers: Boolean,
    hasCurrentServer: Boolean,
    hasCurrentUser: Boolean,
): OfflineExitPlan =
    OfflineExitPlan(
        destination =
            when {
                hasCurrentUser -> OfflineExitDestination.HOME
                hasCurrentServer -> OfflineExitDestination.LOGIN
                hasServers -> OfflineExitDestination.SERVERS
                else -> OfflineExitDestination.WELCOME
            },
        loginUsername = null,
        clearOfflineMode = true,
    )

enum class SavedUserActivationFailure {
    TOKEN_EXPIRED,
    SERVER_UNAVAILABLE,
    USER_UNAVAILABLE,
    UNKNOWN,
}

sealed interface SavedUserActivationResult {
    data class Activated(val user: User) : SavedUserActivationResult

    data class Rejected(val failure: SavedUserActivationFailure) : SavedUserActivationResult
}

class SavedUserAuthenticationException(
    val failure: SavedUserActivationFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)

fun classifySavedUserAuthenticationError(error: Throwable): SavedUserActivationFailure =
    when {
        error.hasCauseMatching { it is InvalidStatusException && it.status in setOf(401, 403) } ->
            SavedUserActivationFailure.TOKEN_EXPIRED
        error.hasCauseMatching { it is IOException || it is CertificateTrustRequiredException } ->
            SavedUserActivationFailure.SERVER_UNAVAILABLE
        else -> SavedUserActivationFailure.UNKNOWN
    }

private fun Throwable.hasCauseMatching(predicate: (Throwable) -> Boolean): Boolean {
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        if (predicate(current)) return true
        current = current.cause
    }
    return false
}

fun interface SavedUserAuthenticator {
    suspend fun authenticate(userId: UUID): User
}

fun interface OfflineModeController {
    fun setOfflineMode(enabled: Boolean)
}

/**
 * 已保存用户的在线激活协调器。
 *
 * 认证必须先完成；只有认证成功才允许清除离线标志。失败时保持调用前的模式不变，由调用方展示原因。
 */
class OfflineIdentityCoordinator(
    private val authenticator: SavedUserAuthenticator,
    private val offlineModeController: OfflineModeController,
) {
    suspend fun activateSavedUser(userId: UUID): SavedUserActivationResult =
        try {
            val user = authenticator.authenticate(userId)
            offlineModeController.setOfflineMode(false)
            SavedUserActivationResult.Activated(user)
        } catch (error: SavedUserAuthenticationException) {
            SavedUserActivationResult.Rejected(error.failure)
        } catch (_: Exception) {
            SavedUserActivationResult.Rejected(SavedUserActivationFailure.UNKNOWN)
        }
}
