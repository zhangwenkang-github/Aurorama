package com.zhangwenkang.cinefin.syncplay

import java.util.UUID

/**
 * W4-T8a：SyncPlay 域错误。
 *
 * 网关把 SDK 的 `InvalidStatusException` / `ApiClientException` / IO 异常统一翻到这里， 调用方（T8c 状态机、T8e
 * 面板）按类型分支，不再感知 HTTP 细节。
 */
sealed interface SyncPlayError {
    /** 便于日志与调试的原始描述；不含令牌等敏感信息。 */
    val detail: String?

    /** `GET/POST /SyncPlay/…` 对具体组返回 404：组已解散或 id 失效。 */
    data class GroupDoesNotExist(
        val groupId: UUID? = null,
        override val detail: String? = null,
    ) : SyncPlayError

    /** 403：组内正在播放的条目所属媒体库当前用户无权访问。 */
    data class LibraryAccessDenied(
        val groupId: UUID? = null,
        override val detail: String? = null,
    ) : SyncPlayError

    /** 404 但没有组上下文：服务器没有 SyncPlay 端点（版本过低）或路径不匹配。 */
    data class EndpointUnavailable(override val detail: String? = null) : SyncPlayError

    /** 401：令牌失效。 */
    data class Unauthorized(override val detail: String? = null) : SyncPlayError

    /** 其它 4xx。 */
    data class HttpError(
        val status: Int,
        override val detail: String? = null,
    ) : SyncPlayError

    /** 5xx。 */
    data class ServerError(
        val status: Int,
        override val detail: String? = null,
    ) : SyncPlayError

    data class Timeout(override val detail: String? = null) : SyncPlayError

    data class Network(override val detail: String? = null) : SyncPlayError

    /** 服务器返回 204/空体，但该调用需要内容（组快照、组列表）。 */
    data class EmptyResponse(override val detail: String? = null) : SyncPlayError

    data class Unknown(override val detail: String? = null) : SyncPlayError
}

/**
 * 网关统一的返回形态。动作类调用成功时 [SyncPlayResult.Success.value] 为 [Unit]。
 *
 * 用 sealed 类而不是 `Result<T>`，是为了让错误在编译期穷尽（与 data 模块既有 `*Result` 惯例一致）。
 */
sealed interface SyncPlayResult<out T> {
    data class Success<T>(val value: T) : SyncPlayResult<T>

    data class Failure(val error: SyncPlayError) : SyncPlayResult<Nothing>
}

fun <T> SyncPlayResult<T>.getOrNull(): T? =
    when (this) {
        is SyncPlayResult.Success -> value
        is SyncPlayResult.Failure -> null
    }

fun SyncPlayResult<*>.errorOrNull(): SyncPlayError? =
    when (this) {
        is SyncPlayResult.Success -> null
        is SyncPlayResult.Failure -> error
    }

fun <T> SyncPlayResult<T>.isSuccess(): Boolean = this is SyncPlayResult.Success<*>
