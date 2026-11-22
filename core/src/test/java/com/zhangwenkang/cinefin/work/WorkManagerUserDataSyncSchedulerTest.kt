package com.zhangwenkang.cinefin.work

import org.junit.Assert.assertEquals
import org.junit.Test

/** W8-T1r：排队写补同步——每次调度都转给 Work 网关（唯一任务的建单在网关内，与冷启动同参数）。 */
class WorkManagerUserDataSyncSchedulerTest {
    @Test
    fun `调度转给 Work 网关一次`() {
        val gateway = RecordingUserDataSyncWorkGateway()
        val scheduler = WorkManagerUserDataSyncScheduler(gateway)

        scheduler.schedule()

        assertEquals(1, gateway.enqueued)
    }

    @Test
    fun `unique 名与冷启动保持一致`() {
        assertEquals("syncUserData", WorkManagerUserDataSyncScheduler.SYNC_USER_DATA_WORK_NAME)
    }
}

private class RecordingUserDataSyncWorkGateway : UserDataSyncWorkGateway {
    var enqueued: Int = 0
        private set

    override fun enqueueSync() {
        enqueued++
    }
}
