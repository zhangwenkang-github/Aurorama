package com.zhangwenkang.cinefin.network

/**
 * 证书信任键：`host:port`（host 统一小写、去掉 IPv6 的方括号）。
 *
 * 指纹信任按「地址」记账而不是只按主机名——同一台服务器可以在 443 / 8920 等不同端口上呈现不同证书， 任何一条都不应该被另一条自动授权。
 */
fun trustKeyOf(host: String, port: Int): String = "${normalizeHost(host)}:$port"

/** 从信任键中还原主机名（IPv6 保持去掉方括号后的原样）。 */
fun hostOfTrustKey(trustKey: String): String = trustKey.substringBeforeLast(':')

/** 从信任键中还原端口；解析失败返回 -1（不会与任何合法端口相等）。 */
fun portOfTrustKey(trustKey: String): Int = trustKey.substringAfterLast(':').toIntOrNull() ?: -1

internal fun normalizeHost(host: String): String =
    host.trim().removeSurrounding("[", "]").lowercase()
