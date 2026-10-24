package com.zhangwenkang.cinefin.update

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class UpdateTransportRequest(
    val url: String,
    val rangeStart: Long?,
    val ifRange: String?,
)

class UpdateTransportResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: InputStream,
    private val closeAction: () -> Unit = body::close,
    private val cancelAction: () -> Unit = {},
) : Closeable {
    fun header(name: String): String? =
        headers.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

    override fun close() {
        closeAction()
    }

    fun cancel() {
        cancelAction()
    }
}

interface UpdateTransport {
    suspend fun execute(request: UpdateTransportRequest): UpdateTransportResponse
}

sealed interface UpdateDownloadResult {
    data class ReadyForVerification(
        val updateId: String,
        val completeFile: File,
        val bytesWritten: Long,
    ) : UpdateDownloadResult

    data class Failed(
        val failureCode: UpdateFailureCode,
        val message: String,
    ) : UpdateDownloadResult
}

class UpdateHttpDownloader(
    private val store: UpdateStateStore,
    private val transport: UpdateTransport,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    private val ioExecutor: Executor = Dispatchers.IO.asExecutor(),
) {
    suspend fun download(
        release: UpdateReleaseIdentity,
        onProgress: (Long) -> Unit = {},
    ): UpdateDownloadResult {
        when (val begin = store.beginDownload(release)) {
            is UpdateStateWriteResult.Failure ->
                return UpdateDownloadResult.Failed(UpdateFailureCode.STATE_ERROR, begin.reason)
            UpdateStateWriteResult.Success -> Unit
        }

        var transfer = loadTransferOrFail() ?: return stateFailure("cannot load transfer state")
        var resetUsed = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val inspection = store.inspectTransferFiles(transfer)
            val offset =
                when (inspection) {
                    TransferFileInspection.AlreadyComplete -> return readyForVerification(transfer)
                    is TransferFileInspection.ResetRequired -> {
                        transfer =
                            resetForRetry(transfer) ?: return storageFailure("cannot reset part")
                        continue
                    }
                    is TransferFileInspection.Invalid -> return storageFailure(inspection.reason)
                    is TransferFileInspection.ReadyToResume -> inspection.offset
                }

            if (offset == transfer.assetSize) {
                currentCoroutineContext().ensureActive()
                return finalize(transfer)
            }

            val request =
                UpdateTransportRequest(
                    url = transfer.downloadUrl,
                    rangeStart = offset.takeIf { it > 0 },
                    ifRange = if (offset > 0) strongIfRange(transfer) else null,
                )
            val response =
                try {
                    transport.execute(request)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    return failed(
                        transfer = transfer,
                        code = UpdateFailureCode.NETWORK_ERROR,
                        message = error.message ?: "transport failed",
                    )
                }
            var retry = false
            try {
                currentCoroutineContext().ensureActive()
                when (response.statusCode) {
                    429 ->
                        return failed(
                            transfer,
                            UpdateFailureCode.RATE_LIMITED,
                            "download rate limited",
                        )
                    in 500..599 ->
                        return failed(
                            transfer,
                            UpdateFailureCode.HTTP_SERVER_ERROR,
                            "server returned HTTP ${response.statusCode}",
                        )
                    416 -> {
                        val total = parseUnsatisfiedTotal(response.header("Content-Range"))
                        if (total == null || total != transfer.assetSize) {
                            return failed(
                                transfer,
                                UpdateFailureCode.INVALID_RESPONSE,
                                "invalid unsatisfied Content-Range",
                            )
                        }
                        if (resetUsed) {
                            return failed(
                                transfer,
                                UpdateFailureCode.RANGE_NOT_SATISFIABLE,
                                "range still unsatisfied after reset",
                            )
                        }
                        transfer =
                            resetForRetry(transfer) ?: return storageFailure("cannot reset part")
                        resetUsed = true
                        retry = true
                    }
                    200 -> {
                        val contentLength = response.contentLengthOrNull()
                        if (contentLength != null && contentLength != transfer.assetSize) {
                            return failed(
                                transfer,
                                UpdateFailureCode.INVALID_RESPONSE,
                                "200 Content-Length does not match asset size",
                            )
                        }
                        val persisted =
                            persistValidators(
                                transfer = transfer,
                                etag = response.header("ETag"),
                                lastModified = response.header("Last-Modified"),
                                preserveMissing = false,
                            )
                        transfer = persisted.transfer ?: return persisted.failure!!
                        when (
                            val copied =
                                copyBodyCancellable(transfer, response, append = false, onProgress)
                        ) {
                            is CopyResult.Success -> Unit
                            is CopyResult.Failure -> {
                                currentCoroutineContext().ensureActive()
                                return failureFromCopy(transfer, copied)
                            }
                        }
                    }
                    206 -> {
                        val parsed = parseContentRange(response.header("Content-Range"))
                        val contentLength = response.contentLengthOrNull()
                        val rangeLength =
                            if (parsed == null) null else parsed.endInclusive - parsed.start + 1
                        val valid =
                            offset > 0 &&
                                parsed != null &&
                                parsed.start == offset &&
                                parsed.total == transfer.assetSize &&
                                parsed.endInclusive < parsed.total &&
                                (contentLength == null || contentLength == rangeLength)
                        if (!valid) {
                            if (resetUsed) {
                                return failed(
                                    transfer,
                                    UpdateFailureCode.INVALID_RESPONSE,
                                    "invalid Content-Range after reset",
                                )
                            }
                            transfer =
                                resetForRetry(transfer)
                                    ?: return storageFailure("cannot reset part")
                            resetUsed = true
                            retry = true
                        } else {
                            val persisted =
                                persistValidators(
                                    transfer = transfer,
                                    etag = response.header("ETag"),
                                    lastModified = response.header("Last-Modified"),
                                    preserveMissing = true,
                                )
                            transfer = persisted.transfer ?: return persisted.failure!!
                            when (
                                val copied =
                                    copyBodyCancellable(
                                        transfer,
                                        response,
                                        append = true,
                                        onProgress,
                                    )
                            ) {
                                is CopyResult.Success -> Unit
                                is CopyResult.Failure -> {
                                    currentCoroutineContext().ensureActive()
                                    return failureFromCopy(transfer, copied)
                                }
                            }
                        }
                    }
                    else ->
                        return failed(
                            transfer,
                            UpdateFailureCode.INVALID_RESPONSE,
                            "unexpected HTTP ${response.statusCode}",
                        )
                }
            } finally {
                runCatching { response.close() }
            }

            if (retry) {
                continue
            }
            currentCoroutineContext().ensureActive()
            val part = store.partFile(transfer)
            if (part.length() != transfer.assetSize) {
                return failed(
                    transfer,
                    UpdateFailureCode.NETWORK_ERROR,
                    "response ended before expected asset size",
                )
            }
            return finalize(transfer)
        }
    }

    private fun persistValidators(
        transfer: UpdateTransferState,
        etag: String?,
        lastModified: String?,
        preserveMissing: Boolean,
    ): ValidatorPersistence {
        val effectiveEtag =
            etag?.trim()?.takeIf(String::isNotEmpty) ?: transfer.etag.takeIf { preserveMissing }
        val effectiveLastModified =
            lastModified?.trim()?.takeIf(String::isNotEmpty)
                ?: transfer.lastModified.takeIf { preserveMissing }
        return when (store.recordValidators(transfer, effectiveEtag, effectiveLastModified)) {
            is UpdateStateWriteResult.Failure ->
                ValidatorPersistence(
                    transfer = null,
                    failure =
                        failed(
                            transfer,
                            UpdateFailureCode.STORAGE_ERROR,
                            "cannot persist response validators",
                        ),
                )
            UpdateStateWriteResult.Success ->
                ValidatorPersistence(
                    transfer = loadTransferOrFail(),
                    failure = null,
                )
        }
    }

    private fun copyBody(
        transfer: UpdateTransferState,
        input: InputStream,
        append: Boolean,
        onProgress: (Long) -> Unit,
    ): CopyResult {
        val part = store.partFile(transfer)
        var written = if (append) part.length() else 0L
        val output =
            try {
                FileOutputStream(part, append)
            } catch (error: IOException) {
                return CopyResult.Failure(
                    UpdateFailureCode.STORAGE_ERROR,
                    error.message ?: "cannot open part",
                )
            }
        output.use { stream ->
            val buffer = ByteArray(bufferSize)
            while (true) {
                val read =
                    try {
                        input.read(buffer)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: IOException) {
                        return CopyResult.Failure(
                            UpdateFailureCode.NETWORK_ERROR,
                            error.message ?: "network read failed",
                        )
                    }
                if (read < 0) {
                    break
                }
                if (read == 0) {
                    continue
                }
                if (written + read > transfer.assetSize) {
                    runCatching { stream.close() }
                    if (runCatching { store.resetPart(transfer) }.isFailure) {
                        return CopyResult.Failure(
                            UpdateFailureCode.STORAGE_ERROR,
                            "cannot reset part after oversized response",
                        )
                    }
                    return CopyResult.Failure(
                        UpdateFailureCode.INVALID_RESPONSE,
                        "response exceeds expected asset size",
                    )
                }
                try {
                    stream.write(buffer, 0, read)
                } catch (error: IOException) {
                    return CopyResult.Failure(
                        UpdateFailureCode.STORAGE_ERROR,
                        error.message ?: "disk write failed",
                    )
                }
                written += read
                onProgress(written)
            }
            try {
                stream.fd.sync()
            } catch (error: IOException) {
                return CopyResult.Failure(
                    UpdateFailureCode.STORAGE_ERROR,
                    error.message ?: "disk sync failed",
                )
            }
        }
        return if (written == transfer.assetSize) {
            CopyResult.Success(written)
        } else {
            CopyResult.Failure(
                UpdateFailureCode.NETWORK_ERROR,
                "response ended before expected asset size",
            )
        }
    }

    private suspend fun copyBodyCancellable(
        transfer: UpdateTransferState,
        response: UpdateTransportResponse,
        append: Boolean,
        onProgress: (Long) -> Unit,
    ): CopyResult = suspendCancellableCoroutine { continuation ->
        val task = FutureTask {
            runCatching {
                copyBody(transfer, response.body, append, onProgress)
            }
                .getOrElse { error ->
                    CopyResult.Failure(
                        UpdateFailureCode.STORAGE_ERROR,
                        error.message ?: "download copy failed",
                    )
                }
                .also { result ->
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }
        }
        ioExecutor.execute(task)
        continuation.invokeOnCancellation {
            runCatching { response.cancel() }
            task.cancel(true)
        }
    }

    private fun failureFromCopy(
        transfer: UpdateTransferState,
        failure: CopyResult.Failure,
    ): UpdateDownloadResult = failed(transfer, failure.code, failure.message)

    private fun resetForRetry(transfer: UpdateTransferState): UpdateTransferState? {
        return runCatching {
            store.resetPart(transfer)
            when (store.recordValidators(transfer, etag = null, lastModified = null)) {
                is UpdateStateWriteResult.Failure -> null
                UpdateStateWriteResult.Success -> loadTransferOrFail()
            }
        }
            .getOrNull()
    }

    private fun finalize(transfer: UpdateTransferState): UpdateDownloadResult {
        val complete = store.completeFile(transfer)
        if (complete.isFile && complete.length() == transfer.assetSize) {
            return readyForVerification(transfer)
        }
        return when (val result = store.finalizePart(transfer)) {
            is UpdateStateWriteResult.Failure ->
                UpdateDownloadResult.Failed(UpdateFailureCode.STORAGE_ERROR, result.reason)
            UpdateStateWriteResult.Success -> readyForVerification(transfer)
        }
    }

    private fun readyForVerification(transfer: UpdateTransferState): UpdateDownloadResult =
        UpdateDownloadResult.ReadyForVerification(
            updateId = transfer.updateId,
            completeFile = store.completeFile(transfer),
            bytesWritten = transfer.assetSize,
        )

    private fun loadTransferOrFail(): UpdateTransferState? =
        when (val loaded = store.loadTransfer()) {
            is UpdateStateReadResult.Present -> loaded.value
            UpdateStateReadResult.Absent,
            is UpdateStateReadResult.Invalid -> null
        }

    private fun failed(
        transfer: UpdateTransferState,
        code: UpdateFailureCode,
        message: String,
    ): UpdateDownloadResult {
        runCatching { store.markFailed(transfer, code) }
        return UpdateDownloadResult.Failed(code, message)
    }

    private fun stateFailure(message: String): UpdateDownloadResult =
        UpdateDownloadResult.Failed(UpdateFailureCode.STATE_ERROR, message)

    private fun storageFailure(message: String): UpdateDownloadResult =
        UpdateDownloadResult.Failed(UpdateFailureCode.STORAGE_ERROR, message)

    private fun UpdateTransportResponse.contentLengthOrNull(): Long? =
        header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }

    private fun strongIfRange(transfer: UpdateTransferState): String? {
        val etag = transfer.etag?.trim()
        if (etag != null && STRONG_ETAG.matches(etag)) {
            return etag
        }
        val lastModified = transfer.lastModified?.trim() ?: return null
        return runCatching {
            ZonedDateTime.parse(lastModified, DateTimeFormatter.RFC_1123_DATE_TIME)
            lastModified
        }
            .getOrElse { error -> if (error is DateTimeParseException) null else throw error }
    }

    private fun parseContentRange(value: String?): ParsedContentRange? {
        val match = value?.trim()?.let(CONTENT_RANGE::matchEntire) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (start < 0 || end < start || total <= end) {
            return null
        }
        return ParsedContentRange(start, end, total)
    }

    private fun parseUnsatisfiedTotal(value: String?): Long? {
        val match = value?.trim()?.let(UNSATISFIED_RANGE::matchEntire) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it > 0 }
    }

    private data class ParsedContentRange(
        val start: Long,
        val endInclusive: Long,
        val total: Long,
    )

    private sealed interface CopyResult {
        data class Success(val bytesWritten: Long) : CopyResult

        data class Failure(
            val code: UpdateFailureCode,
            val message: String,
        ) : CopyResult
    }

    private data class ValidatorPersistence(
        val transfer: UpdateTransferState?,
        val failure: UpdateDownloadResult?,
    )

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
        val STRONG_ETAG = Regex("^\"[^\"]+\"$")
        val CONTENT_RANGE = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$")
        val UNSATISFIED_RANGE = Regex("^bytes \\*/([0-9]+)$")
    }
}

class OkHttpUpdateTransport(private val client: OkHttpClient = OkHttpClient()) : UpdateTransport {
    override suspend fun execute(request: UpdateTransportRequest): UpdateTransportResponse =
        suspendCancellableCoroutine { continuation ->
            val builder =
                Request.Builder().url(request.url).header("User-Agent", "Aurorama-Android").get()
            request.rangeStart?.let { builder.header("Range", "bytes=$it-") }
            request.ifRange?.let { builder.header("If-Range", it) }
            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(e))
                        }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        if (!continuation.isActive) {
                            response.close()
                            return
                        }
                        val body = response.body
                        continuation.resume(
                            UpdateTransportResponse(
                                statusCode = response.code,
                                headers =
                                    response.headers.toMultimap().mapValues { it.value.first() },
                                body = body.byteStream(),
                                closeAction = response::close,
                                cancelAction = call::cancel,
                            )
                        )
                    }
                }
            )
        }
}
