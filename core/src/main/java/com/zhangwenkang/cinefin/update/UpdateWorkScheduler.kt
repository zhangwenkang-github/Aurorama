package com.zhangwenkang.cinefin.update

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.zhangwenkang.cinefin.work.UpdateCheckWorker
import com.zhangwenkang.cinefin.work.UpdateDownloadWorker
import com.zhangwenkang.cinefin.work.UpdateWorkData
import java.util.concurrent.TimeUnit
import javax.inject.Inject

internal sealed interface UpdateWorkCommand {
    data class AutomaticCheck(val initialDelayMs: Long) : UpdateWorkCommand

    data object ManualCheck : UpdateWorkCommand

    data class Download(val updateId: String) : UpdateWorkCommand

    data object CancelDownload : UpdateWorkCommand
}

internal interface UpdateWorkGateway {
    fun enqueue(command: UpdateWorkCommand)
}

/** UI / 启动入口依赖的最小调度能力；实现仍集中在唯一 Work 调度器。 */
interface UpdateWorkScheduling {
    fun scheduleAutomaticCheck()

    fun scheduleManualCheck()

    fun scheduleDownload(updateId: String)

    fun cancelDownload()
}

/**
 * WorkManager 只承担协调：两次检查各有一个唯一 Work，下载也有独立唯一 Work；全部要求联网，均使用 KEEP， 避免重复点击创建并行任务。动态 offset /
 * validator / 发现结果不进入 Work Data。
 */
class UpdateWorkScheduler
internal constructor(
    private val gateway: UpdateWorkGateway,
    private val automaticCheckDelayMs: () -> Long,
) : UpdateWorkScheduling {
    @Inject
    constructor(
        workManager: WorkManager,
        coordinator: UpdateCheckCoordinator,
    ) : this(
        gateway = WorkManagerUpdateWorkGateway(workManager),
        automaticCheckDelayMs = { coordinator.automaticCheckDelayMillis() },
    )

    override fun scheduleAutomaticCheck() {
        gateway.enqueue(UpdateWorkCommand.AutomaticCheck(automaticCheckDelayMs()))
    }

    override fun scheduleManualCheck() {
        gateway.enqueue(UpdateWorkCommand.ManualCheck)
    }

    override fun scheduleDownload(updateId: String) {
        require(UPDATE_ID_PATTERN.matches(updateId)) { "invalid updateId" }
        gateway.enqueue(UpdateWorkCommand.Download(updateId))
    }

    override fun cancelDownload() {
        gateway.enqueue(UpdateWorkCommand.CancelDownload)
    }

    private companion object {
        val UPDATE_ID_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

private class WorkManagerUpdateWorkGateway(private val workManager: WorkManager) :
    UpdateWorkGateway {
    override fun enqueue(command: UpdateWorkCommand) {
        when (command) {
            is UpdateWorkCommand.AutomaticCheck ->
                workManager.enqueueUniqueWork(
                    UPDATE_AUTO_CHECK_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    checkRequest(UpdateCheckMode.AUTO, command.initialDelayMs),
                )
            UpdateWorkCommand.ManualCheck ->
                workManager.enqueueUniqueWork(
                    UPDATE_MANUAL_CHECK_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    checkRequest(UpdateCheckMode.MANUAL, initialDelayMs = 0L),
                )
            is UpdateWorkCommand.Download ->
                workManager.enqueueUniqueWork(
                    UPDATE_DOWNLOAD_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    downloadRequest(command.updateId),
                )
            UpdateWorkCommand.CancelDownload ->
                workManager.cancelUniqueWork(UPDATE_DOWNLOAD_WORK_NAME)
        }
    }

    private fun checkRequest(
        mode: UpdateCheckMode,
        initialDelayMs: Long,
    ) =
        OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(CONNECTED_CONSTRAINTS)
            .setInputData(UpdateWorkData.checkInput(mode))
            .apply {
                if (initialDelayMs > 0L) {
                    setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                }
            }
            .build()

    private fun downloadRequest(updateId: String) =
        OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setConstraints(CONNECTED_CONSTRAINTS)
            .setInputData(UpdateWorkData.downloadInput(updateId))
            .build()

    private companion object {
        val CONNECTED_CONSTRAINTS =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}

const val UPDATE_AUTO_CHECK_WORK_NAME = "update_check_auto"
const val UPDATE_MANUAL_CHECK_WORK_NAME = "update_check_manual"
const val UPDATE_DOWNLOAD_WORK_NAME = "update_download"

/** 只读取 Worker 已公开的小终止枚举；不在 UI 侧泄露 Work Data 其它字段。 */
fun updateTerminalResult(workInfo: WorkInfo): UpdateTerminalResult? =
    when (workInfo.state) {
        WorkInfo.State.SUCCEEDED,
        WorkInfo.State.FAILED -> UpdateWorkData.parseResult(workInfo.outputData)
        else -> null
    }
