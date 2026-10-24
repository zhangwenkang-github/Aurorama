package com.zhangwenkang.cinefin.update

import java.io.File
import java.security.MessageDigest

data class InstalledAppMetadata(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val signerCertificateDigests: Set<String>,
)

data class CandidateApkMetadata(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val signerCertificateDigests: Set<String>,
)

enum class UpdateVerificationFailure {
    DIGEST_MISSING,
    DIGEST_MALFORMED,
    FILE_MISSING,
    FILE_SIZE_MISMATCH,
    DIGEST_MISMATCH,
    APK_UNREADABLE,
    PACKAGE_MISMATCH,
    VERSION_CODE_NOT_INCREMENTED,
    VERSION_NAME_DOWNGRADE,
    INVALID_VERSION_NAME,
    MIN_SDK_UNSUPPORTED,
    SIGNER_SET_MISMATCH,
    FILE_READ_ERROR,
}

sealed interface UpdateDigestParseResult {
    data class Valid(val hex: String) : UpdateDigestParseResult

    data class Invalid(val failure: UpdateVerificationFailure) : UpdateDigestParseResult
}

sealed interface UpdatePackageVerificationResult {
    data class Success(val packageMetadata: CandidateApkMetadata) : UpdatePackageVerificationResult

    data class Failure(val failure: UpdateVerificationFailure) : UpdatePackageVerificationResult
}

private val SHA256_DIGEST_PATTERN = Regex("^sha256:([0-9a-fA-F]{64})$")

fun parseUpdateDigest(value: String?): UpdateDigestParseResult {
    if (value.isNullOrBlank()) {
        return UpdateDigestParseResult.Invalid(UpdateVerificationFailure.DIGEST_MISSING)
    }
    val match =
        SHA256_DIGEST_PATTERN.matchEntire(value)
            ?: return UpdateDigestParseResult.Invalid(UpdateVerificationFailure.DIGEST_MALFORMED)
    return UpdateDigestParseResult.Valid(match.groupValues[1].lowercase())
}

fun calculateSha256(
    file: File,
    bufferSize: Int = DEFAULT_BUFFER_SIZE,
): String {
    require(bufferSize > 0) { "bufferSize must be positive" }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered(bufferSize).use { input ->
        val buffer = ByteArray(bufferSize)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString(separator = "") { "%02x".format(it) }
}

fun updateSignerCertificateDigest(certificateBytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(certificateBytes).joinToString(separator = "") {
        "%02x".format(it)
    }

fun verifyUpdatePackageFile(
    file: File,
    expectedSize: Long,
    expectedDigest: String?,
    readCandidateMetadata: (File) -> CandidateApkMetadata,
    installed: InstalledAppMetadata,
    deviceSdk: Int,
): UpdatePackageVerificationResult {
    val expected =
        when (val parsed = parseUpdateDigest(expectedDigest)) {
            is UpdateDigestParseResult.Invalid ->
                return UpdatePackageVerificationResult.Failure(parsed.failure)
            is UpdateDigestParseResult.Valid -> parsed.hex
        }
    if (!file.isFile) {
        return UpdatePackageVerificationResult.Failure(UpdateVerificationFailure.FILE_MISSING)
    }
    if (file.length() != expectedSize) {
        return UpdatePackageVerificationResult.Failure(UpdateVerificationFailure.FILE_SIZE_MISMATCH)
    }
    val actual =
        try {
            calculateSha256(file)
        } catch (exception: Exception) {
            return UpdatePackageVerificationResult.Failure(
                UpdateVerificationFailure.FILE_READ_ERROR
            )
        }
    if (actual != expected) {
        return UpdatePackageVerificationResult.Failure(UpdateVerificationFailure.DIGEST_MISMATCH)
    }
    val metadata =
        try {
            readCandidateMetadata(file)
        } catch (exception: Exception) {
            return UpdatePackageVerificationResult.Failure(UpdateVerificationFailure.APK_UNREADABLE)
        }
    return verifyUpdatePackageMetadata(
        installed = installed,
        candidate = metadata,
        deviceSdk = deviceSdk,
    )
}

fun verifyUpdatePackageMetadata(
    installed: InstalledAppMetadata,
    candidate: CandidateApkMetadata,
    deviceSdk: Int,
): UpdatePackageVerificationResult {
    if (candidate.packageName != installed.packageName) {
        return UpdatePackageVerificationResult.Failure(UpdateVerificationFailure.PACKAGE_MISMATCH)
    }
    if (candidate.versionCode <= installed.versionCode) {
        return UpdatePackageVerificationResult.Failure(
            UpdateVerificationFailure.VERSION_CODE_NOT_INCREMENTED
        )
    }
    val installedVersion = AppVersion.parseCurrentVersionName(installed.versionName)
    val candidateVersion = AppVersion.parseCurrentVersionName(candidate.versionName)
    if (installedVersion == null || candidateVersion == null) {
        return UpdatePackageVerificationResult.Failure(
            UpdateVerificationFailure.INVALID_VERSION_NAME
        )
    }
    if (candidateVersion < installedVersion) {
        return UpdatePackageVerificationResult.Failure(
            UpdateVerificationFailure.VERSION_NAME_DOWNGRADE
        )
    }
    if (candidate.minSdk <= 0 || candidate.minSdk > deviceSdk) {
        return UpdatePackageVerificationResult.Failure(
            UpdateVerificationFailure.MIN_SDK_UNSUPPORTED
        )
    }
    if (
        installed.signerCertificateDigests.isEmpty() ||
            candidate.signerCertificateDigests.isEmpty() ||
            installed.signerCertificateDigests != candidate.signerCertificateDigests
    ) {
        return UpdatePackageVerificationResult.Failure(
            UpdateVerificationFailure.SIGNER_SET_MISMATCH
        )
    }
    return UpdatePackageVerificationResult.Success(candidate)
}
