package com.zhangwenkang.cinefin.film.presentation.detail

import kotlinx.coroutines.CancellationException

internal suspend fun <T> readOptionalDetail(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Result.failure(error)
    }
