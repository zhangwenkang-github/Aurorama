package com.zhangwenkang.cinefin.setup

import com.zhangwenkang.cinefin.setup.data.ServerBootstrapResult
import com.zhangwenkang.cinefin.setup.data.resolveServerBootstrap
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerBootstrapTest {
    private val explicitAddress = "https://media.example.test"
    private val normalizedAddress = "https://media.example.test/"
    private val validSystemInfo =
        PublicSystemInfo(
            serverName = "Home",
            version = "12.2.0",
            productName = "Jellyfin Server",
            id = "server-id",
        )

    @Test
    fun `显式 URL 公开信息成功时优先返回且不启动候选发现`() = runBlocking {
        var recommendedRequests = 0

        val result =
            resolveServerBootstrap(
                address = explicitAddress,
                candidates = listOf(normalizedAddress),
                loadPublicInfo = { baseUrl ->
                    assertEquals(normalizedAddress, baseUrl)
                    validSystemInfo
                },
                loadRecommendedServers = {
                    recommendedRequests++
                    emptyList()
                },
            )

        assertEquals(ServerBootstrapResult.Explicit(validSystemInfo, normalizedAddress), result)
        assertEquals(0, recommendedRequests)
    }

    @Test
    fun `候选发现失败不会拖累已成功的显式 URL`() = runBlocking {
        val result =
            resolveServerBootstrap(
                address = explicitAddress,
                candidates = listOf(normalizedAddress),
                loadPublicInfo = { validSystemInfo },
                loadRecommendedServers = { throw IOException("candidate discovery failed") },
            )

        assertEquals(ServerBootstrapResult.Explicit(validSystemInfo, normalizedAddress), result)
    }

    @Test
    fun `显式 URL 首次传输失败时在快速路径内重试`() = runBlocking {
        var publicInfoRequests = 0
        var recommendedRequests = 0

        val result =
            resolveServerBootstrap(
                address = explicitAddress,
                candidates = listOf(normalizedAddress),
                loadPublicInfo = {
                    publicInfoRequests++
                    if (publicInfoRequests == 1) throw IOException("transient connect failure")
                    validSystemInfo
                },
                loadRecommendedServers = {
                    recommendedRequests++
                    emptyList()
                },
            )

        assertEquals(ServerBootstrapResult.Explicit(validSystemInfo, normalizedAddress), result)
        assertEquals(2, publicInfoRequests)
        assertEquals(0, recommendedRequests)
    }

    @Test
    fun `显式 URL 返回无效公开信息时回退候选发现`() = runBlocking {
        var recommendedRequests = 0

        val result =
            resolveServerBootstrap(
                address = explicitAddress,
                candidates = listOf(normalizedAddress),
                loadPublicInfo = { PublicSystemInfo() },
                loadRecommendedServers = {
                    recommendedRequests++
                    emptyList()
                },
            )

        assertEquals(ServerBootstrapResult.Discovered(emptyList()), result)
        assertEquals(1, recommendedRequests)
    }
}
