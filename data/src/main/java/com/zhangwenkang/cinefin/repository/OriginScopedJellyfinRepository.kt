package com.zhangwenkang.cinefin.repository

import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidSegment
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.FindroidSource
import java.util.UUID

/** 下载 sidecar 创建时冻结的完整 Jellyfin namespace。 */
data class JellyfinOrigin(
    val serverId: String,
    val userId: UUID,
) {
    init {
        require(serverId.isNotBlank()) { "serverId 不得为空" }
    }
}

data class JellyfinOriginCredentials(
    val baseUrl: String,
    val accessToken: String?,
)

/**
 * 只读 origin-scoped seam。
 *
 * 下载恢复不得通过当前 Jellyfin session 猜测历史来源；生产实现必须用冻结 origin 的服务器地址、用户和令牌建立独立 client。调用方只消费该 origin 返回的
 * item / source / credentials，不得把结果贴回 current namespace。
 */
interface OriginScopedJellyfinRepository {
    suspend fun getItemForOrigin(itemId: UUID, origin: JellyfinOrigin): FindroidItem?

    suspend fun getMediaSourcesForOrigin(
        itemId: UUID,
        origin: JellyfinOrigin,
    ): List<FindroidSource>

    suspend fun credentialsForOrigin(origin: JellyfinOrigin): JellyfinOriginCredentials

    suspend fun getSegmentsForOrigin(itemId: UUID, origin: JellyfinOrigin): List<FindroidSegment>

    suspend fun getTrickplayDataForOrigin(
        itemId: UUID,
        width: Int,
        index: Int,
        origin: JellyfinOrigin,
    ): ByteArray?

    suspend fun getShowForOrigin(itemId: UUID, origin: JellyfinOrigin): FindroidShow

    suspend fun getSeasonForOrigin(itemId: UUID, origin: JellyfinOrigin): FindroidSeason
}
