package com.zhangwenkang.cinefin.repository

import java.io.IOException
import java.net.SocketTimeoutException
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W8-T1：写失败分类（V2-WRITE-03/09）——403/401 不可重试，网络与 5xx 可重试。 */
class UserDataWriteFailureClassificationTest {
    @Test
    fun `403 归为权限拒绝且不重试`() {
        val failure = InvalidStatusException(403).toUserDataWriteFailure()

        assertTrue(failure is UserDataWriteFailure.PermissionDenied)
        assertEquals(403, (failure as UserDataWriteFailure.PermissionDenied).status)
        assertFalse(failure.retryable)
    }

    @Test
    fun `401 归为权限拒绝且不重试`() {
        val failure = InvalidStatusException(401).toUserDataWriteFailure()

        assertTrue(failure is UserDataWriteFailure.PermissionDenied)
        assertEquals(401, (failure as UserDataWriteFailure.PermissionDenied).status)
        assertFalse(failure.retryable)
    }

    @Test
    fun `IO 异常归为网络失败且可重试`() {
        val failure = IOException("connection reset").toUserDataWriteFailure()

        assertEquals(UserDataWriteFailure.Network("connection reset"), failure)
        assertTrue(failure.retryable)
    }

    @Test
    fun `超时归为网络失败且可重试`() {
        val failure = SocketTimeoutException("timeout").toUserDataWriteFailure()

        assertEquals(UserDataWriteFailure.Network("timeout"), failure)
        assertTrue(failure.retryable)
    }

    @Test
    fun `5xx 归为服务端错误且可重试`() {
        val failure = InvalidStatusException(503).toUserDataWriteFailure()

        assertTrue(failure is UserDataWriteFailure.ServerError)
        assertEquals(503, (failure as UserDataWriteFailure.ServerError).status)
        assertTrue(failure.retryable)
    }

    @Test
    fun `异常被 ApiClientException 包裹时沿 cause 链解析状态码`() {
        val wrapped = ApiClientException("wrapped", InvalidStatusException(403))

        val failure = wrapped.toUserDataWriteFailure()
        assertTrue(failure is UserDataWriteFailure.PermissionDenied)
        assertEquals(403, (failure as UserDataWriteFailure.PermissionDenied).status)
        assertFalse(failure.retryable)
    }

    @Test
    fun `包裹层更深时同样解析到权限拒绝`() {
        val wrapped =
            ApiClientException("outer", ApiClientException("inner", InvalidStatusException(401)))

        val failure = wrapped.toUserDataWriteFailure()
        assertTrue(failure is UserDataWriteFailure.PermissionDenied)
        assertEquals(401, (failure as UserDataWriteFailure.PermissionDenied).status)
        assertFalse(failure.retryable)
    }

    @Test
    fun `4xx 非权限状态归为未知失败且不重试`() {
        val failure = InvalidStatusException(404).toUserDataWriteFailure()

        assertTrue(failure is UserDataWriteFailure.Unknown)
        assertEquals(404, (failure as UserDataWriteFailure.Unknown).status)
        assertFalse(failure.retryable)
    }

    @Test
    fun `无状态码的普通异常归为未知失败且不重试`() {
        val failure = IllegalStateException("boom").toUserDataWriteFailure()

        assertEquals(UserDataWriteFailure.Unknown(null, "boom"), failure)
        assertFalse(failure.retryable)
    }
}
