package com.zhangwenkang.cinefin.syncplay

import com.zhangwenkang.cinefin.api.JellyfinApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.operations.SyncPlayApi
import org.jellyfin.sdk.api.operations.TimeSyncApi
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.SendCommand
import org.jellyfin.sdk.model.api.SendCommandType
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import org.jellyfin.sdk.model.api.UtcTimeResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W4-T8a：网关契约测试。
 *
 * 用 mockk 模拟 SDK 的 `SyncPlayApi` / `TimeSyncApi` / `SocketApi`，钉死三件事：
 * 1. 204 空体与 4xx/5xx 异常的域映射；
 * 2. 能力探针的三条路径（全通过 / getGroups 失败 / getUtcTime 失败）都不抛裸异常；
 * 3. WS 消息确实被接线成域事件流。
 */
class SyncPlayGatewayContractTest {
    private val groupId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val itemId = UUID.fromString("22222222-2222-4222-8222-222222222222")

    private companion object {
        val GROUP_UPDATED_AT: LocalDateTime = LocalDateTime.of(2026, 11, 19, 20, 30, 0)
        val COMMAND_AT: LocalDateTime = LocalDateTime.of(2026, 11, 19, 20, 31, 0)
    }

    private val sdkSyncPlayApi = mockk<SyncPlayApi>()
    private val sdkTimeSyncApi = mockk<TimeSyncApi>()
    private val sdkSocketApi = mockk<SocketApi>(relaxed = true)

    private val api: JellyfinApi =
        mockk<JellyfinApi>(relaxed = true).also { jellyfinApi ->
            every { jellyfinApi.syncPlayApi } returns sdkSyncPlayApi
            every { jellyfinApi.timeSyncApi } returns sdkTimeSyncApi
            every { jellyfinApi.webSocket } returns sdkSocketApi
            every { sdkSocketApi.state } returns MutableStateFlow(SocketApiState.Connected)
        }

    private val gateway = SyncPlayGatewayImpl(api)

    private fun SyncPlayResult<*>.requireError(): SyncPlayError {
        assertTrue("expected a failure but was $this", this is SyncPlayResult.Failure)
        return (this as SyncPlayResult.Failure).error
    }

    private fun sdkGroup(): GroupInfoDto =
        GroupInfoDto(
            groupId = groupId,
            groupName = "客厅同步",
            state = GroupStateType.IDLE,
            participants = listOf("alice"),
            lastUpdatedAt = GROUP_UPDATED_AT,
        )

    private fun utcTime(): UtcTimeResponse =
        UtcTimeResponse(
            requestReceptionTime = LocalDateTime.of(2026, 11, 19, 12, 0, 0),
            responseTransmissionTime = LocalDateTime.of(2026, 11, 19, 12, 0, 0),
        )

    // —— 204 空体 ——

    @Test
    fun `动作调用收到 204 空体视为成功`() = runBlocking {
        coEvery { sdkSyncPlayApi.syncPlayPause() } returns Response(Unit, 204, emptyMap())

        assertEquals(SyncPlayResult.Success(Unit), gateway.pause())
    }

    @Test
    fun `组列表收到 204 空体返回 EmptyResponse`() = runBlocking {
        coEvery { sdkSyncPlayApi.syncPlayGetGroups() } returns
            Response(emptyList<GroupInfoDto>(), 204, emptyMap())

        val result = gateway.getGroups()

        assertTrue(result.requireError() is SyncPlayError.EmptyResponse)
    }

    // —— 4xx/5xx 映射 ——

    @Test
    fun `带组上下文的 404 映射为 GroupDoesNotExist`() = runBlocking {
        coEvery { sdkSyncPlayApi.syncPlayGetGroup(any()) } throws InvalidStatusException(404, null)

        val error = gateway.getGroup(groupId).requireError()

        assertTrue(error is SyncPlayError.GroupDoesNotExist)
        assertEquals(groupId, (error as SyncPlayError.GroupDoesNotExist).groupId)
    }

    @Test
    fun `无组上下文的 404 映射为 EndpointUnavailable`() = runBlocking {
        coEvery { sdkSyncPlayApi.syncPlayGetGroups() } throws InvalidStatusException(404, null)

        val error = gateway.getGroups().requireError()

        assertTrue(error is SyncPlayError.EndpointUnavailable)
    }

    @Test
    fun `403 映射为 LibraryAccessDenied`() = runBlocking {
        coEvery { sdkSyncPlayApi.syncPlayJoinGroup(any()) } throws InvalidStatusException(403, null)

        val error = gateway.joinGroup(groupId).requireError()

        assertTrue(error is SyncPlayError.LibraryAccessDenied)
        assertEquals(groupId, (error as SyncPlayError.LibraryAccessDenied).groupId)
    }

    @Test
    fun `401 与 5xx 分别映射为 Unauthorized 与 ServerError`() = runBlocking {
        coEvery { sdkSyncPlayApi.syncPlayLeaveGroup() } throws InvalidStatusException(401, null)
        coEvery { sdkSyncPlayApi.syncPlayStop() } throws InvalidStatusException(503, null)

        assertTrue(gateway.leaveGroup().requireError() is SyncPlayError.Unauthorized)
        val serverError = gateway.stop().requireError()
        assertTrue(serverError is SyncPlayError.ServerError)
        assertEquals(503, (serverError as SyncPlayError.ServerError).status)
    }

    @Test
    fun `SDK 包装的网络超时异常沿 cause 链映射为 Timeout`() = runBlocking {
        coEvery { sdkSyncPlayApi.syncPlayUnpause() } throws
            ApiClientException("wrapped", SocketTimeoutException("read timed out"))

        val result = gateway.unpause()

        assertTrue((result as SyncPlayResult.Failure).error is SyncPlayError.Timeout)
    }

    @Test
    fun `成功调用返回域组列表`() = runBlocking {
        coEvery { sdkSyncPlayApi.syncPlayGetGroups() } returns
            Response(listOf(sdkGroup()), 200, emptyMap())

        val result = gateway.getGroups()

        assertEquals(
            SyncPlayResult.Success(
                listOf(
                    SyncPlayGroup(
                        id = groupId,
                        name = "客厅同步",
                        state = SyncPlayGroupState.IDLE,
                        participants = listOf("alice"),
                        lastUpdatedAt = GROUP_UPDATED_AT.toInstantInSystemZone(),
                    )
                )
            ),
            result,
        )
    }

    // —— 能力探针（契约） ——

    @Test
    fun `探针全通过时返回可用并带组列表与服务器时间`() = runBlocking {
        coEvery { sdkSyncPlayApi.syncPlayGetGroups() } returns
            Response(listOf(sdkGroup()), 200, emptyMap())
        coEvery { sdkTimeSyncApi.getUtcTime() } returns Response(utcTime(), 200, emptyMap())

        val capability = gateway.probeCapability()

        assertTrue(capability is SyncPlayCapability.Available)
        val available = capability as SyncPlayCapability.Available
        assertEquals(listOf(groupId), available.groups.map { it.id })
        assertEquals(
            utcTime().requestReceptionTime.toInstantInSystemZone(),
            available.serverTime.requestReceptionTime,
        )
        coVerify { sdkSyncPlayApi.syncPlayGetGroups() }
        coVerify { sdkTimeSyncApi.getUtcTime() }
    }

    @Test
    fun `探针在组列表失败时返回不可用且不抛异常`() = runBlocking {
        coEvery { sdkSyncPlayApi.syncPlayGetGroups() } throws InvalidStatusException(404, null)

        val capability = gateway.probeCapability()

        val unavailable = capability as SyncPlayCapability.Unavailable
        assertEquals(SyncPlayUnavailableReason.GROUP_LIST_FAILED, unavailable.reason)
        assertTrue(unavailable.error is SyncPlayError.EndpointUnavailable)
    }

    @Test
    fun `探针在时间同步失败时返回不可用且不抛异常`() = runBlocking {
        coEvery { sdkSyncPlayApi.syncPlayGetGroups() } returns
            Response(emptyList<GroupInfoDto>(), 200, emptyMap())
        coEvery { sdkTimeSyncApi.getUtcTime() } throws InvalidStatusException(500, null)

        val capability = gateway.probeCapability()

        val unavailable = capability as SyncPlayCapability.Unavailable
        assertEquals(SyncPlayUnavailableReason.SERVER_TIME_FAILED, unavailable.reason)
        assertTrue(unavailable.error is SyncPlayError.ServerError)
        assertEquals(500, (unavailable.error as SyncPlayError.ServerError).status)
    }

    // —— WS 接线 ——

    @Test
    fun `WS 组更新消息被接线为域事件流`() = runBlocking {
        every { sdkSocketApi.subscribe(SyncPlayGroupUpdateMessage::class) } returns
            flowOf(
                SyncPlayGroupUpdateMessage(
                    data = SyncPlayGroupJoinedUpdate(groupId = groupId, data = sdkGroup()),
                    messageId = UUID.randomUUID(),
                )
            )

        val update = gateway.groupUpdates.first()

        assertTrue(update is SyncPlayGroupUpdate.GroupJoined)
        assertEquals(groupId, update.groupId)
    }

    @Test
    fun `WS 播放命令消息被接线为域命令流`() = runBlocking {
        every { sdkSocketApi.subscribe(SyncPlayCommandMessage::class) } returns
            flowOf(
                SyncPlayCommandMessage(
                    data =
                        SendCommand(
                            groupId = groupId,
                            playlistItemId = itemId,
                            `when` = COMMAND_AT,
                            positionTicks = 1_000_000L,
                            command = SendCommandType.PAUSE,
                            emittedAt = COMMAND_AT,
                        ),
                    messageId = UUID.randomUUID(),
                )
            )

        val command = gateway.commands.first()

        assertEquals(SyncPlayCommandType.PAUSE, command.type)
        assertEquals(1_000_000L, command.positionTicks)
        assertEquals(COMMAND_AT.toInstantInSystemZone(), command.whenAt)
    }
}
