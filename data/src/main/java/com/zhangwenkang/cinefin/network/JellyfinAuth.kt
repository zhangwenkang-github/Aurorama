package com.zhangwenkang.cinefin.network

/**
 * Jellyfin **自定义 HTTP 调用**（不经 SDK 的 OkHttp / HttpURLConnection 请求）的单一凭据头构造点。
 *
 * 服务器 `Jellyfin 12.2.0` 只接受 `Authorization: MediaBrowser Token="…"`：`X-Emby-Token` 头、
 * `X-Emby-Authorization` 头、`?api_key=` 查询参数、`Bearer` 一律 **401**（2026-10-17 只读探针逐形态复核： 带引号与不带引号均
 * 206，其余形态 401）。SDK 自己的调用已由 SDK 注入同形头，这里只收敛自定义调用， 避免"个别链路能下、个别不能"的碎片化。
 */
object JellyfinAuth {
    /** 唯一被服务器接受的鉴权头名。 */
    const val HEADER: String = "Authorization"

    /** 历史头名（服务器已拒绝）：只在跨主机重定向时兜底剥离，防旧代码残留的凭据外泄。 */
    const val LEGACY_HEADER: String = "X-Emby-Token"

    /** `Authorization` 头值；统一带引号（与探针 206 的形态一致）。 */
    fun headerValue(token: String): String = "MediaBrowser Token=\"$token\""

    /** 单值请求头表；令牌为空 → 空表（匿名请求不附鉴权头）。 */
    fun headers(token: String?): Map<String, String> =
        token?.takeIf { it.isNotBlank() }?.let { mapOf(HEADER to headerValue(it)) } ?: emptyMap()

    /** 多值请求头表（Readium `HttpClient` 的 `Map<String, List<String>>` 形态）。 */
    fun headerMap(token: String?): Map<String, List<String>> =
        headers(token).mapValues { (_, value) -> listOf(value) }

    /**
     * 把鉴权头并入既有头表：大小写不敏感地替换同名头，并清掉历史 `X-Emby-Token`（防新旧并存时服务器挑到被拒的头）。
     *
     * 令牌为空时原表原样返回（不修改入参）。
     */
    fun merge(
        headers: Map<String, List<String>>,
        token: String?,
    ): Map<String, List<String>> {
        val value = token?.takeIf { it.isNotBlank() }?.let { headerValue(it) } ?: return headers
        val merged = LinkedHashMap<String, List<String>>(headers.size + 1)
        headers.forEach { (name, values) ->
            if (
                !name.equals(HEADER, ignoreCase = true) &&
                    !name.equals(LEGACY_HEADER, ignoreCase = true)
            ) {
                merged[name] = values
            }
        }
        merged[HEADER] = listOf(value)
        return merged
    }
}
