package com.zhangwenkang.cinefin.update

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdatePackageVerifierTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun missingDigestIsRejected() {
        assertEquals(
            UpdateDigestParseResult.Invalid(UpdateVerificationFailure.DIGEST_MISSING),
            parseUpdateDigest(null),
        )
        assertEquals(
            UpdateDigestParseResult.Invalid(UpdateVerificationFailure.DIGEST_MISSING),
            parseUpdateDigest("   "),
        )
    }

    @Test
    fun digestRequiresPrefixAndExactlySixtyFourHexCharacters() {
        val malformed =
            listOf(
                "abc",
                "sha256:",
                "sha256:abc",
                "md5:" + "a".repeat(64),
                "sha256:" + "g".repeat(64),
                "sha256:" + "a".repeat(63),
                "sha256:" + "a".repeat(65),
            )

        malformed.forEach {
            assertEquals(
                UpdateDigestParseResult.Invalid(UpdateVerificationFailure.DIGEST_MALFORMED),
                parseUpdateDigest(it),
            )
        }
    }

    @Test
    fun digestIsNormalizedToLowercaseAfterStrictParsing() {
        val uppercase = "ABCDEF0123456789".repeat(4)

        assertEquals(
            UpdateDigestParseResult.Valid(uppercase.lowercase()),
            parseUpdateDigest("sha256:$uppercase"),
        )
    }

    @Test
    fun streamingSha256MatchesHandDerivedAbcDigestWithoutChangingFile() {
        val file = temporaryFolder.newFile("abc.apk").apply { writeText("abc") }

        val digest = calculateSha256(file, bufferSize = 1)

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            digest,
        )
        assertEquals("abc", file.readText())
    }

    @Test
    fun digestVerificationRejectsSameLengthTampering() {
        val file = temporaryFolder.newFile("tampered.apk").apply { writeText("abd") }

        val result =
            verifyUpdatePackageFile(
                file = file,
                expectedSize = 3L,
                expectedDigest =
                    "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                readCandidateMetadata = { error("metadata must not run after digest failure") },
                installed = installedPackage(),
                deviceSdk = 36,
            )

        assertEquals(
            UpdatePackageVerificationResult.Failure(UpdateVerificationFailure.DIGEST_MISMATCH),
            result,
        )
    }

    @Test
    fun digestVerificationRejectsTruncatedAndOversizedFilesBeforeMetadataRead() {
        val complete = temporaryFolder.newFile("complete.apk").apply { writeText("abc") }
        val truncated = temporaryFolder.newFile("truncated.apk").apply { writeText("ab") }
        val oversized = temporaryFolder.newFile("oversized.apk").apply { writeText("abcd") }
        val digest = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

        listOf(truncated, oversized).forEach { file ->
            val result =
                verifyUpdatePackageFile(
                    file = file,
                    expectedSize = complete.length(),
                    expectedDigest = digest,
                    readCandidateMetadata = { error("metadata must not run after size failure") },
                    installed = installedPackage(),
                    deviceSdk = 36,
                )

            assertEquals(
                UpdatePackageVerificationResult.Failure(
                    UpdateVerificationFailure.FILE_SIZE_MISMATCH
                ),
                result,
            )
        }
    }

    @Test
    fun packageNameMustMatchInstalledApplication() {
        val result =
            verifyUpdatePackageMetadata(
                installed = installedPackage(),
                candidate = candidateMetadata(packageName = "io.github.example.evil"),
                deviceSdk = 36,
            )

        assertEquals(
            UpdatePackageVerificationResult.Failure(UpdateVerificationFailure.PACKAGE_MISMATCH),
            result,
        )
    }

    @Test
    fun versionCodeMustStrictlyIncrease() {
        listOf(4L, 3L).forEach { versionCode ->
            val result =
                verifyUpdatePackageMetadata(
                    installed = installedPackage(),
                    candidate = candidateMetadata(versionCode = versionCode),
                    deviceSdk = 36,
                )

            assertEquals(
                UpdatePackageVerificationResult.Failure(
                    UpdateVerificationFailure.VERSION_CODE_NOT_INCREMENTED
                ),
                result,
            )
        }
    }

    @Test
    fun versionNameMustNotDowngradeOrUseInvalidSyntax() {
        val downgraded =
            verifyUpdatePackageMetadata(
                installed = installedPackage(),
                candidate = candidateMetadata(versionName = "1.2.9"),
                deviceSdk = 36,
            )
        val invalidCandidate =
            verifyUpdatePackageMetadata(
                installed = installedPackage(),
                candidate = candidateMetadata(versionName = "1.4"),
                deviceSdk = 36,
            )
        val invalidInstalled =
            verifyUpdatePackageMetadata(
                installed = installedPackage().copy(versionName = "dev-build"),
                candidate = candidateMetadata(),
                deviceSdk = 36,
            )

        assertEquals(
            UpdatePackageVerificationResult.Failure(
                UpdateVerificationFailure.VERSION_NAME_DOWNGRADE
            ),
            downgraded,
        )
        assertEquals(
            UpdatePackageVerificationResult.Failure(UpdateVerificationFailure.INVALID_VERSION_NAME),
            invalidCandidate,
        )
        assertEquals(
            UpdatePackageVerificationResult.Failure(UpdateVerificationFailure.INVALID_VERSION_NAME),
            invalidInstalled,
        )
    }

    @Test
    fun candidateMinSdkMustBeRunnableOnCurrentDevice() {
        val unsupported =
            verifyUpdatePackageMetadata(
                installed = installedPackage(),
                candidate = candidateMetadata(minSdk = 37),
                deviceSdk = 36,
            )
        val supported =
            verifyUpdatePackageMetadata(
                installed = installedPackage(),
                candidate = candidateMetadata(minSdk = 36),
                deviceSdk = 36,
            )

        assertEquals(
            UpdatePackageVerificationResult.Failure(UpdateVerificationFailure.MIN_SDK_UNSUPPORTED),
            unsupported,
        )
        assertEquals(
            UpdatePackageVerificationResult.Success(candidateMetadata(minSdk = 36)),
            supported,
        )
    }

    @Test
    fun signerCertificateSetsMustMatchExactlyAndCannotBeEmpty() {
        val installed = installedPackage().copy(signerCertificateDigests = setOf("a", "b"))
        val equalMultiSigner =
            verifyUpdatePackageMetadata(
                installed = installed,
                candidate = candidateMetadata(signerCertificateDigests = setOf("b", "a")),
                deviceSdk = 36,
            )
        val missingCertificate =
            verifyUpdatePackageMetadata(
                installed = installed,
                candidate = candidateMetadata(signerCertificateDigests = setOf("a")),
                deviceSdk = 36,
            )
        val extraCertificate =
            verifyUpdatePackageMetadata(
                installed = installed,
                candidate = candidateMetadata(signerCertificateDigests = setOf("a", "b", "c")),
                deviceSdk = 36,
            )
        val emptySet =
            verifyUpdatePackageMetadata(
                installed = installed,
                candidate = candidateMetadata(signerCertificateDigests = emptySet()),
                deviceSdk = 36,
            )

        assertEquals(
            UpdatePackageVerificationResult.Success(
                candidateMetadata(signerCertificateDigests = setOf("a", "b"))
            ),
            equalMultiSigner,
        )
        listOf(missingCertificate, extraCertificate, emptySet).forEach {
            assertEquals(
                UpdatePackageVerificationResult.Failure(
                    UpdateVerificationFailure.SIGNER_SET_MISMATCH
                ),
                it,
            )
        }
    }

    @Test
    fun signerCertificatesUseStableSha256Identity() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            updateSignerCertificateDigest("abc".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun validDigestProceedsToMetadataPolicy() {
        val file = temporaryFolder.newFile("valid.apk").apply { writeText("abc") }
        var metadataReads = 0

        val result =
            verifyUpdatePackageFile(
                file = file,
                expectedSize = 3L,
                expectedDigest =
                    "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                readCandidateMetadata = {
                    metadataReads++
                    candidateMetadata()
                },
                installed = installedPackage(),
                deviceSdk = 36,
            )

        assertEquals(1, metadataReads)
        assertEquals(UpdatePackageVerificationResult.Success(candidateMetadata()), result)
    }

    private fun installedPackage() =
        InstalledAppMetadata(
            packageName = "io.github.zhangwenkang.aurorama.debug",
            versionCode = 4L,
            versionName = "1.3.0",
            signerCertificateDigests = setOf("installed-signer"),
        )

    private fun candidateMetadata(
        packageName: String = "io.github.zhangwenkang.aurorama.debug",
        versionCode: Long = 5L,
        versionName: String = "1.4.0",
        minSdk: Int = 28,
        signerCertificateDigests: Set<String> = setOf("installed-signer"),
    ) =
        CandidateApkMetadata(
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
            minSdk = minSdk,
            signerCertificateDigests = signerCertificateDigests,
        )
}
