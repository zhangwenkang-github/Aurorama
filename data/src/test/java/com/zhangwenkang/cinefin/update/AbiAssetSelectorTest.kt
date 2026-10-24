package com.zhangwenkang.cinefin.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbiAssetSelectorTest {
    private val version = AppVersion(1, 4, 0)

    @Test
    fun prefersArm64WhenSupportedAndAvailable() {
        val selection =
            AbiAssetSelector.select(
                version = version,
                assets = listOf(arm64Asset(), universalAsset()),
                supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            )

        assertEquals(AssetArchitecture.ARM64, selection?.architecture)
        assertEquals(releaseAssetUrl("1.4.0", arm64Name), selection?.browserDownloadUrl)
    }

    @Test
    fun fallsBackToUniversalWhenArm64AssetIsMissing() {
        val selection =
            AbiAssetSelector.select(
                version = version,
                assets = listOf(universalAsset()),
                supportedAbis = listOf("arm64-v8a"),
            )

        assertEquals(AssetArchitecture.UNIVERSAL, selection?.architecture)
    }

    @Test
    fun usesUniversalForNonArm64Devices() {
        val selection =
            AbiAssetSelector.select(
                version = version,
                assets = listOf(arm64Asset(), universalAsset()),
                supportedAbis = listOf("x86_64", "x86"),
            )

        assertEquals(AssetArchitecture.UNIVERSAL, selection?.architecture)
    }

    @Test
    fun failsWhenNeitherCompatibleAssetExists() {
        assertNull(
            AbiAssetSelector.select(
                version = version,
                assets = emptyList(),
                supportedAbis = listOf("arm64-v8a"),
            )
        )
    }

    @Test
    fun rejectsUnknownAssetNamesUrlsAndHosts() {
        assertNull(
            AbiAssetSelector.select(
                version = version,
                assets =
                    listOf(
                        GitHubReleaseAssetDto(
                            name = "Aurorama-1.4.0-arm64.apk",
                            size = 123L,
                            browserDownloadUrl = releaseAssetUrl("1.4.0", arm64Name),
                            digest = null,
                        )
                    ),
                supportedAbis = listOf("arm64-v8a"),
            )
        )

        listOf(
                "https://example.com/zhangwenkang-github/Aurorama/releases/download/v1.4.0/$arm64Name",
                "http://github.com/zhangwenkang-github/Aurorama/releases/download/v1.4.0/$arm64Name",
                "https://github.com/other/Aurorama/releases/download/v1.4.0/$arm64Name",
                "https://github.com/zhangwenkang-github/Aurorama/releases/download/v1.3.0/$arm64Name",
                "https://github.com/zhangwenkang-github/Aurorama/releases/download/v1.4.0/$arm64Name?download=1",
            )
            .forEach { url ->
                assertNull(
                    url,
                    AbiAssetSelector.select(
                        version = version,
                        assets = listOf(arm64Asset(url = url), universalAsset()),
                        supportedAbis = listOf("arm64-v8a"),
                    ),
                )
            }
    }

    @Test
    fun rejectsZeroSizeOrDuplicateMatchingAssets() {
        assertNull(
            AbiAssetSelector.select(
                version = version,
                assets = listOf(arm64Asset(size = 0L)),
                supportedAbis = listOf("arm64-v8a"),
            )
        )
        assertNull(
            AbiAssetSelector.select(
                version = version,
                assets = listOf(arm64Asset(), arm64Asset()),
                supportedAbis = listOf("arm64-v8a"),
            )
        )
    }

    private fun arm64Asset(
        size: Long = 123L,
        url: String = releaseAssetUrl("1.4.0", arm64Name),
    ) =
        GitHubReleaseAssetDto(
            name = arm64Name,
            size = size,
            browserDownloadUrl = url,
            digest = "sha256:arm64",
        )

    private fun universalAsset() =
        GitHubReleaseAssetDto(
            name = universalName,
            size = 456L,
            browserDownloadUrl = releaseAssetUrl("1.4.0", universalName),
            digest = "sha256:universal",
        )

    private companion object {
        const val arm64Name = "Aurorama-1.4.0-arm64-v8a.apk"
        const val universalName = "Aurorama-1.4.0-universal.apk"
    }
}
