package com.zhangwenkang.cinefin.repository

import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * W8-T2：当前用户 `UserPolicy` 的拉取与缓存（WRITE-04 的前置能力，T5/T6 写入口消费）。
 *
 * 缓存按 (serverId, userId) 键控：
 * - 键不匹配（换服 / 换账号后尚未刷新）→ 一律 fail-closed，绝不返回上一身份的 policy；
 * - `refresh()` 期间身份变化 → 丢弃结果，不把旧请求的 policy 写到新身份上；
 * - 拉取失败 → fail-closed（不做跨请求兜底；管理员入口的离线兜底仍走既有 prefs 缓存）。
 *
 * 刷新时机：① App 启动恢复会话（`BaseApplication` 身份 hydrate 成功后拉一次）；② 会话内换服 / 换账号后，T5/T6 写入口进入时调用
 * [ensureLoaded]（键不匹配会自动重拉）。未刷新期间 [current] 一律 fail-closed；需要显式失效时可调 [invalidate]。
 */
@Singleton
class UserPolicyStore
@Inject
constructor(
    private val jellyfinApi: JellyfinApi,
    private val appPreferences: AppPreferences,
) {
    private val mutex = Mutex()
    private val _access = MutableStateFlow(UserPolicyAccess.unknown(serverId = null, userId = null))

    /** UI 可观察的权限快照；未拉取 / 已过期时 policy 为 null（fail-closed）。 */
    val access: StateFlow<UserPolicyAccess> = _access.asStateFlow()

    fun currentKey(): UserPolicyKey =
        UserPolicyKey(
            serverId =
                appPreferences.getValue(appPreferences.currentServer)?.takeIf { it.isNotBlank() },
            userId = jellyfinApi.userId?.toString(),
        )

    /** 当前身份下已缓存的权限；键不匹配时返回 fail-closed 快照（不返回上一身份）。 */
    fun current(): UserPolicyAccess {
        val key = currentKey()
        val cached = _access.value
        return if (cached.matches(key)) cached
        else UserPolicyAccess.unknown(key.serverId, key.userId)
    }

    /** 有缓存就直接用；没有（或换了身份）才联网拉取。T5/T6 入口可用。 */
    suspend fun ensureLoaded(): UserPolicyAccess {
        val cached = current()
        return if (cached.isLoaded) cached else refresh()
    }

    /** 强制按当前身份重新拉取；期间切号丢弃结果。 */
    suspend fun refresh(): UserPolicyAccess = mutex.withLock {
        val key = currentKey()
        if (!key.isResolved) {
            _access.value = UserPolicyAccess.unknown(key.serverId, key.userId)
            return@withLock _access.value
        }
        val policy =
            withContext(Dispatchers.IO) {
                try {
                    jellyfinApi.userApi.getCurrentUser().content.policy
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        val after = currentKey()
        _access.value =
            if (after == key) {
                UserPolicyAccess(serverId = after.serverId, userId = after.userId, policy = policy)
            } else {
                // 拉取期间换服 / 换账号：结果属于旧身份，丢弃并保持 fail-closed。
                UserPolicyAccess.unknown(after.serverId, after.userId)
            }
        _access.value
    }

    /** 身份切换钩子：立刻让缓存失效，UI 不会继续拿上一账号的权限做门控。 */
    fun invalidate() {
        val key = currentKey()
        _access.value = UserPolicyAccess.unknown(key.serverId, key.userId)
    }
}
