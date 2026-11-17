package com.zhangwenkang.cinefin.repository

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

enum class MediaCatalogAccessMode {
    Online,
    Offline,
}

/** 媒体目录读取的唯一作用域：服务器、已认证用户与访问模式。 */
data class MediaCatalogScope(
    val serverId: String,
    val userId: UUID,
    val accessMode: MediaCatalogAccessMode,
    val revision: Long,
)

/**
 * 目录页共享的作用域入口。
 *
 * revision 只负责通知“身份提交已改变”，不是第二身份真源；每次读取仍从当前偏好、当前仓库和认证 user 重算 [MediaCatalogScope]。
 */
class MediaCatalogSession
@Inject
constructor(
    private val repositoryProvider: Provider<JellyfinRepository>,
    private val appPreferences: AppPreferences,
) {
    fun repository(): JellyfinRepository = repositoryProvider.get()

    fun currentScope(): MediaCatalogScope? {
        val serverId =
            appPreferences.getValue(appPreferences.currentServer)?.takeIf { it.isNotBlank() }
                ?: return null
        val userId = runCatching { repository().getUserId() }.getOrNull() ?: return null
        return MediaCatalogScope(
            serverId = serverId,
            userId = userId,
            accessMode =
                if (appPreferences.getValue(appPreferences.offlineMode)) {
                    MediaCatalogAccessMode.Offline
                } else {
                    MediaCatalogAccessMode.Online
                },
            revision = appPreferences.getValue(appPreferences.mediaCatalogScopeRevision),
        )
    }

    fun isCurrent(scope: MediaCatalogScope?): Boolean = scope != null && currentScope() == scope

    fun observeChanges(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (
                key == appPreferences.currentServer.backendName ||
                    key == appPreferences.offlineMode.backendName ||
                    key == appPreferences.mediaCatalogScopeRevision.backendName
            ) {
                trySend(Unit)
            }
        }
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose {
            appPreferences.sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
}
