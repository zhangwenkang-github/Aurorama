package com.zhangwenkang.cinefin.work

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.zhangwenkang.cinefin.repository.UserDataSyncScheduler

internal interface UserDataSyncWorkGateway {
    fun enqueueSync()
}

/**
 * W8-T1r：排队用户数据写的补同步调度（data 层只依赖 [UserDataSyncScheduler] 抽象）。
 *
 * 与冷启动入队（`BaseApplication`）共用同一个 unique work 名与约束：网络恢复即补同步，已存在未完成的任务时 KEEP 不重复入队。
 */
class WorkManagerUserDataSyncScheduler
internal constructor(private val gateway: UserDataSyncWorkGateway) : UserDataSyncScheduler {
    constructor(workManager: WorkManager) : this(WorkManagerUserDataSyncWorkGateway(workManager))

    override fun schedule() {
        gateway.enqueueSync()
    }

    companion object {
        /** 冷启动与排队写共用；改名会让两条路径各自入队。 */
        const val SYNC_USER_DATA_WORK_NAME: String = "syncUserData"
    }
}

private class WorkManagerUserDataSyncWorkGateway(private val workManager: WorkManager) :
    UserDataSyncWorkGateway {
    override fun enqueueSync() {
        workManager.enqueueUniqueWork(
            WorkManagerUserDataSyncScheduler.SYNC_USER_DATA_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build(),
        )
    }
}
