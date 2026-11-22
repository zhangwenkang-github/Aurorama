package com.zhangwenkang.cinefin.repository

/**
 * W8-T1r：用户数据写产生排队（[UserDataWriteOutcome.QueuedForSync]）后的补同步调度。
 *
 * data 层不依赖 WorkManager：这里只保留最小调度抽象，WorkManager 实现由 app 层注入（core
 * `WorkManagerUserDataSyncScheduler`）。实现须幂等——重复调用不应堆叠多个任务（unique work + KEEP）。
 */
fun interface UserDataSyncScheduler {
    fun schedule()

    companion object {
        /** 无调度能力时的空实现（仅未接入 WorkManager 的测试 / 场景使用）。 */
        val NoOp: UserDataSyncScheduler = UserDataSyncScheduler {}
    }
}
