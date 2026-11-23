package com.zhangwenkang.cinefin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.User
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID

@HiltWorker
class SyncWorker
@AssistedInject
constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParams: WorkerParameters,
    val database: ServerDatabaseDao,
    val appPreferences: AppPreferences,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val jellyfinApi =
            JellyfinApi(
                androidContext = context.applicationContext,
                requestTimeout = appPreferences.getValue(appPreferences.requestTimeout),
                connectTimeout = appPreferences.getValue(appPreferences.connectTimeout),
                socketTimeout = appPreferences.getValue(appPreferences.socketTimeout),
            )

        val servers = database.getServers()

        for (server in servers) {
            val serverWithAddressesAndUsers =
                database.getServerWithAddressesAndUsers(server.id) ?: continue
            val serverAddress =
                serverWithAddressesAndUsers.addresses.firstOrNull {
                    it.id == server.currentServerAddressId
                } ?: continue
            for (user in serverWithAddressesAndUsers.users) {
                jellyfinApi.apply {
                    api.update(baseUrl = serverAddress.address, accessToken = user.accessToken)
                    userId = user.id
                }
                syncUserData(jellyfinApi, user, queuedItemIds(server.id, user))
            }
        }

        return Result.success()
    }

    /**
     * W9-T4：扫描集覆盖 movies / episodes / shows / seasons 四类投影行 + 该用户全部 `toBeSynced` 行。
     *
     * 季 / 剧有投影表但旧实现只扫电影 / 单集（WRITE-08 缺口）；音乐收藏没有投影表，只能靠队列行带进扫描集。
     */
    private suspend fun queuedItemIds(serverId: String, user: User): List<UUID> =
        userDataSyncItemIds(
            database.getMoviesByServerId(serverId).map { it.id },
            database.getEpisodesByServerId(serverId).map { it.id },
            database.getShowsByServerId(serverId).map { it.id },
            database.getSeasonsByServerId(serverId).map { it.id },
            database.getUserDataToBeSynced(user.id).map { it.itemId },
        )

    private suspend fun syncUserData(
        jellyfinApi: JellyfinApi,
        user: User,
        itemIds: List<UUID>,
    ) {
        pushQueuedUserData(
            itemIds = itemIds,
            loadQueued = { itemId -> database.getUserDataToBeSynced(user.id, itemId) },
            mirrored = { itemId -> database.hasUserDataMirror(itemId) },
            push = { userData, data ->
                jellyfinApi.itemsApi.updateItemUserData(
                    itemId = userData.itemId,
                    userId = user.id,
                    data = data,
                )
            },
            clear = { itemId -> database.setUserDataToBeSynced(user.id, itemId, false) },
        )
    }
}
