package com.zhangwenkang.cinefin.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableReleasePolicyTest {
    private val policy = StableReleasePolicy

    @Test
    fun rejectsDraftAndPrerelease() {
        val assets = listOf(asset())

        assertEquals(
            UpdateDecision.UnsupportedRelease,
            policy.evaluate(
                release(tag = "v1.4.0", draft = true, assets = assets),
                "1.3.0",
                arm64Abis,
            ),
        )
        assertEquals(
            UpdateDecision.UnsupportedRelease,
            policy.evaluate(
                release(tag = "v1.4.0", prerelease = true, assets = assets),
                "1.3.0",
                arm64Abis,
            ),
        )
    }

    @Test
    fun rejectsNonCanonicalAndOverflowingTags() {
        val invalidTags =
            listOf(
                "1.4.0",
                "v1.4",
                "v1.4.0-beta.1",
                "v1.4.0.1",
                "vv1.4.0",
                " v1.4.0",
                "v1.4.0 ",
                "v01.4.0",
                "v2147483648.0.0",
                "v1.4.999999999999999999999",
            )

        invalidTags.forEach { tag ->
            assertEquals(
                "$tag should be rejected",
                UpdateDecision.UnsupportedRelease,
                policy.evaluate(release(tag = tag), "1.3.0", arm64Abis),
            )
        }
    }

    @Test
    fun comparesRemoteAndInjectedCurrentVersion() {
        val higher = policy.evaluate(release(tag = "v1.4.0"), currentVersion = "1.3.0", arm64Abis)
        assertTrue(higher is UpdateDecision.UpdateAvailable)
        assertEquals(AppVersion(1, 4, 0), (higher as UpdateDecision.UpdateAvailable).version)
        assertEquals(AssetArchitecture.ARM64, higher.asset.architecture)

        assertEquals(
            UpdateDecision.UpToDate,
            policy.evaluate(release(tag = "v1.3.0"), currentVersion = "1.3.0", arm64Abis),
        )
        assertEquals(
            UpdateDecision.UpToDate,
            policy.evaluate(release(tag = "v1.2.0"), currentVersion = "1.3.0", arm64Abis),
        )
    }

    @Test
    fun fallsBackToUniversalAndFailsWhenNoCompatibleAssetExists() {
        val universalOnly =
            release(
                tag = "v1.4.0",
                assets = listOf(asset(architecture = AssetArchitecture.UNIVERSAL)),
            )
        val fallback = policy.evaluate(universalOnly, "1.3.0", arm64Abis)
        assertTrue(fallback is UpdateDecision.UpdateAvailable)
        assertEquals(
            AssetArchitecture.UNIVERSAL,
            (fallback as UpdateDecision.UpdateAvailable).asset.architecture,
        )

        assertEquals(
            UpdateDecision.NoCompatibleAsset,
            policy.evaluate(release(tag = "v1.4.0", assets = emptyList()), "1.3.0", arm64Abis),
        )
    }

    @Test
    fun rejectsInvalidInstalledVersion() {
        listOf("1.3", "v1.3.0", "1.3.0-beta", "1.2147483648.0").forEach { current ->
            assertEquals(
                UpdateDecision.InvalidInstalledVersion,
                policy.evaluate(release(tag = "v1.4.0"), current, arm64Abis),
            )
        }
    }

    @Test
    fun parsesRealV130FixtureAsStableAndUpToDate() {
        val parsed = policy.parseReleaseJson(realV130Fixture)
        assertTrue(parsed is ReleaseParseResult.Success)
        val release = (parsed as ReleaseParseResult.Success).release

        assertEquals("v1.3.0", release.tagName)
        assertEquals(
            UpdateDecision.UpToDate,
            policy.evaluate(release, currentVersion = "1.3.0", supportedAbis = arm64Abis),
        )
    }

    @Test
    fun rejectsMissingFieldsMalformedJsonAndWrongTypes() {
        val payloads =
            listOf(
                """{"draft":false,"prerelease":false,"assets":[]}""",
                """{"tag_name":"v1.3.0","prerelease":false,"assets":[]}""",
                """{"tag_name":"v1.3.0","draft":false,"assets":[]}""",
                """{"tag_name":"v1.3.0","draft":false,"prerelease":false}""",
                """{"tag_name":"v1.3.0","draft":"false","prerelease":false,"assets":[]}""",
                """{"tag_name":"v1.3.0","draft":false,"prerelease":false,"assets":[{"name":"Aurorama-1.3.0-universal.apk","size":1}]}""",
                "{not-json",
            )

        payloads.forEach { payload ->
            assertTrue(payload, policy.parseReleaseJson(payload) is ReleaseParseResult.Failure)
        }
    }

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<GitHubReleaseAssetDto> = defaultAssets(tag.removePrefix("v")),
    ) =
        GitHubReleaseDto(
            tagName = tag,
            draft = draft,
            prerelease = prerelease,
            assets = assets,
        )

    private fun asset(
        version: String = "1.4.0",
        architecture: AssetArchitecture = AssetArchitecture.ARM64,
        name: String = releaseAssetName(version, architecture),
        url: String = releaseAssetUrl(version, name),
    ) =
        GitHubReleaseAssetDto(
            name = name,
            size = 123L,
            browserDownloadUrl = url,
            digest = "sha256:abc",
        )

    private fun defaultAssets(version: String) =
        listOf(
            asset(version, AssetArchitecture.ARM64),
            asset(version, AssetArchitecture.UNIVERSAL),
        )

    private companion object {
        val arm64Abis = listOf("arm64-v8a", "armeabi-v7a")

        const val realV130Fixture =
            """
            {
              "tag_name": "v1.3.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "Aurorama-1.3.0-arm64-v8a.apk",
                  "size": 78000626,
                  "digest": "sha256:5f066205a1558ef3528b9674ed45a418c719f74ccbb0778dcad3cb38fe809c98",
                  "browser_download_url": "https://github.com/zhangwenkang-github/Aurorama/releases/download/v1.3.0/Aurorama-1.3.0-arm64-v8a.apk"
                },
                {
                  "name": "Aurorama-1.3.0-universal.apk",
                  "size": 171582604,
                  "digest": "sha256:db6ae7974eeb2909ff26dd959db2daa0afa72c497268fca4715ba94c99ae71f7",
                  "browser_download_url": "https://github.com/zhangwenkang-github/Aurorama/releases/download/v1.3.0/Aurorama-1.3.0-universal.apk"
                }
              ]
            }
        """
    }
}
