package com.zhangwenkang.cinefin.update

object AbiAssetSelector {
    fun select(
        version: AppVersion,
        assets: List<GitHubReleaseAssetDto>,
        supportedAbis: List<String>,
    ): SelectedReleaseAsset? {
        val architecture =
            if ("arm64-v8a" in supportedAbis) {
                AssetArchitecture.ARM64
            } else {
                AssetArchitecture.UNIVERSAL
            }

        val preferredName = releaseAssetName(version.versionName, architecture)
        val preferredMatches = assets.filter { it.name == preferredName }
        if (preferredMatches.isNotEmpty()) {
            return selectExact(version, preferredMatches, architecture)
        }
        if (architecture == AssetArchitecture.ARM64) {
            val universalName = releaseAssetName(version.versionName, AssetArchitecture.UNIVERSAL)
            return selectExact(
                version = version,
                matches = assets.filter { it.name == universalName },
                architecture = AssetArchitecture.UNIVERSAL,
            )
        }
        return null
    }

    private fun selectExact(
        version: AppVersion,
        matches: List<GitHubReleaseAssetDto>,
        architecture: AssetArchitecture,
    ): SelectedReleaseAsset? {
        val expectedName = releaseAssetName(version.versionName, architecture)
        if (matches.size != 1) {
            return null
        }

        val asset = matches.single()
        if (asset.size <= 0) {
            return null
        }
        if (asset.browserDownloadUrl != releaseAssetUrl(version.versionName, expectedName)) {
            return null
        }
        return SelectedReleaseAsset(
            name = asset.name,
            size = asset.size,
            browserDownloadUrl = asset.browserDownloadUrl,
            digest = asset.digest,
            architecture = architecture,
        )
    }
}
