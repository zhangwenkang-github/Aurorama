package com.zhangwenkang.cinefin.network

import java.net.URI

/** 从地址候选 / 已保存地址里解析 https 目标（host, port）；非 https 或无法解析时返回 null。 */
fun httpsTargetOf(url: String): Pair<String, Int>? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    val host = uri.host?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() } ?: return null
    return host to if (uri.port != -1) uri.port else 443
}
