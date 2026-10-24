package com.zhangwenkang.cinefin.repository

import kotlinx.coroutines.CompletableDeferred

/** 元数据缓存身份。serverId 与 userId 任一缺失时，仓库不启用缓存。 */
data class MetadataNamespace(val serverId: String, val userId: String)

/** 一次内容代际。用户动作只递增当前 namespace 的 generation。 */
data class MetadataFence(
    val namespace: MetadataNamespace,
    val contentGeneration: Long,
)

/**
 * namespace + generation + logical key 维度的单飞登记表。
 *
 * 旧代的 finish 只有身份与 deferred 完全匹配时才移除登记，因此旧代取消或失败不会误删新代请求。
 */
internal class MetadataRequestCoordinator {
    private data class RequestIdentity(val fence: MetadataFence, val logicalKey: String)

    private val map = HashMap<RequestIdentity, CompletableDeferred<Any?>>()

    /** 返回 (deferred, true) = 本调用是 owner；false = 加入同一身份的在途请求。 */
    fun join(fence: MetadataFence, logicalKey: String): Pair<CompletableDeferred<Any?>, Boolean> =
        synchronized(map) {
            val identity = RequestIdentity(fence, logicalKey)
            map[identity]?.let { it to false }
                ?: CompletableDeferred<Any?>().also { map[identity] = it } to true
        }

    /** owner 收尾：身份与 deferred 都匹配时才移除，避免旧代误删新代登记。 */
    fun finish(fence: MetadataFence, logicalKey: String, deferred: CompletableDeferred<Any?>) {
        val identity = RequestIdentity(fence, logicalKey)
        synchronized(map) { if (map[identity] === deferred) map.remove(identity) }
    }

    /** 当前在途条数（单测 / 诊断用）。 */
    fun size(): Int = synchronized(map) { map.size }
}
