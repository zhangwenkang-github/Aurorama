package com.zhangwenkang.cinefin.repository

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException

/**
 * W8-T1：用户数据写（收藏 / 已看 / 歌单条目）的统一结果。
 *
 * 失败必须对用户可见且区分可重试性（V2-WRITE-03/09）：权限拒绝不可重试，网络 / 5xx 走既有离线补偿。
 */
sealed interface UserDataWriteOutcome {
    /** 服务器已确认写入，本地库与服务端一致。 */
    data object Confirmed : UserDataWriteOutcome

    /** 网络 / 5xx 失败：本地库已落库并标记 `toBeSynced`，等后台同步补偿（V2-WRITE-08）。 */
    data object QueuedForSync : UserDataWriteOutcome

    /** 不可重试失败（如权限拒绝）：本地库**未**改动，调用方应把 [failure] 呈现给用户。 */
    data class Failed(val failure: UserDataWriteFailure) : UserDataWriteOutcome
}

/** 用户数据写失败的分类（V2-WRITE-03/09）。 */
sealed interface UserDataWriteFailure {
    /** 服务器拒绝：401 未授权 / 403 无权限。不可重试，不得伪装成本地已保存。 */
    data class PermissionDenied(val status: Int, val message: String?) : UserDataWriteFailure

    /** 网络不可达 / 超时 / 连接中断。可重试。 */
    data class Network(val message: String?) : UserDataWriteFailure

    /** 服务端 5xx。可重试。 */
    data class ServerError(val status: Int, val message: String?) : UserDataWriteFailure

    /** 其他未知失败（4xx 非权限、映射失败等）。不可重试。 */
    data class Unknown(val status: Int?, val message: String?) : UserDataWriteFailure
}

/** 只有网络 / 5xx 才走「本地落库 + 后台补同步」的补偿路径。 */
val UserDataWriteFailure.retryable: Boolean
    get() = this is UserDataWriteFailure.Network || this is UserDataWriteFailure.ServerError

/** 本地库是否已按用户意图改动；权限拒绝（[UserDataWriteOutcome.Failed]）时为 false，展示方不必刷新。 */
val UserDataWriteOutcome.localStateChanged: Boolean
    get() = this is UserDataWriteOutcome.Confirmed || this is UserDataWriteOutcome.QueuedForSync

/**
 * 沿 cause 链解析失败原因：SDK 常把 [InvalidStatusException] 包在 `ApiClientException` 里（同 `SyncPlayMapping.kt`
 * 的既有口径）。
 */
fun Throwable.toUserDataWriteFailure(): UserDataWriteFailure {
    var current: Throwable? = this
    while (current != null) {
        when (current) {
            is InvalidStatusException -> return current.toUserDataWriteFailure()
            is SocketTimeoutException -> return UserDataWriteFailure.Network(current.message)
            is InterruptedIOException -> return UserDataWriteFailure.Network(current.message)
            is IOException -> return UserDataWriteFailure.Network(current.message)
        }
        current = current.cause
    }
    return UserDataWriteFailure.Unknown(status = null, message = message)
}

private fun InvalidStatusException.toUserDataWriteFailure(): UserDataWriteFailure =
    when (val code = status) {
        401,
        403 -> UserDataWriteFailure.PermissionDenied(code, message)
        in 500..599 -> UserDataWriteFailure.ServerError(code, message)
        else -> UserDataWriteFailure.Unknown(code, message)
    }

/**
 * 统一写事务（V2-WRITE-05/09）。
 *
 * 先请求服务器、成功后才落本地——权限拒绝时本地库保持原样，不会出现「已收藏但服务器从未接受」的假状态。 网络 / 5xx 失败保留既有补偿语义：本地落库 + 标记待同步 + 调度一次补同步，返回
 * [UserDataWriteOutcome.QueuedForSync]。
 *
 * 成功路径同时清掉该行可能残留的 `toBeSynced`（先排队、后在线操作成功的脏状态，W8-T1r）。
 */
internal suspend fun runUserDataWrite(
    remote: suspend () -> Unit,
    persistLocal: suspend () -> Unit,
    markForSync: suspend () -> Unit,
    clearForSync: suspend () -> Unit,
    scheduleSync: () -> Unit,
): UserDataWriteOutcome {
    try {
        remote()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        val failure = error.toUserDataWriteFailure()
        if (!failure.retryable) return UserDataWriteOutcome.Failed(failure)
        persistLocal()
        markForSync()
        scheduleSync()
        return UserDataWriteOutcome.QueuedForSync
    }
    persistLocal()
    clearForSync()
    return UserDataWriteOutcome.Confirmed
}
