package com.zhangwenkang.cinefin.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val UPDATE_REPOSITORY_OWNER = "zhangwenkang-github"
const val UPDATE_REPOSITORY_NAME = "Aurorama"
const val GITHUB_LATEST_RELEASE_API_URL =
    "https://api.github.com/repos/$UPDATE_REPOSITORY_OWNER/$UPDATE_REPOSITORY_NAME/releases/latest"

enum class AssetArchitecture(val assetSuffix: String) {
    ARM64("arm64-v8a"),
    UNIVERSAL("universal"),
}

fun releaseAssetName(
    versionName: String,
    architecture: AssetArchitecture,
): String = "Aurorama-$versionName-${architecture.assetSuffix}.apk"

fun releaseAssetUrl(
    versionName: String,
    assetName: String,
): String =
    "https://github.com/$UPDATE_REPOSITORY_OWNER/$UPDATE_REPOSITORY_NAME/" +
        "releases/download/v$versionName/$assetName"

data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<AppVersion> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0)
    }

    val versionName: String
        get() = "$major.$minor.$patch"

    val tagName: String
        get() = "v$versionName"

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    companion object {
        private val releaseTagPattern =
            Regex("^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
        private val currentVersionPattern =
            Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")

        fun parseReleaseTag(value: String): AppVersion? = parse(value, releaseTagPattern)

        fun parseCurrentVersionName(value: String): AppVersion? =
            parse(value, currentVersionPattern)

        private fun parse(
            value: String,
            pattern: Regex,
        ): AppVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            return AppVersion(major, minor, patch)
        }
    }
}

@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<GitHubReleaseAssetDto>,
)

@Serializable
data class GitHubReleaseAssetDto(
    val name: String,
    val size: Long,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val digest: String? = null,
)

data class SelectedReleaseAsset(
    val name: String,
    val size: Long,
    val browserDownloadUrl: String,
    val digest: String?,
    val architecture: AssetArchitecture,
)

sealed interface ReleaseParseResult {
    data class Success(val release: GitHubReleaseDto) : ReleaseParseResult

    data class Failure(val reason: String) : ReleaseParseResult
}

sealed interface UpdateDecision {
    data class UpdateAvailable(
        val version: AppVersion,
        val asset: SelectedReleaseAsset,
    ) : UpdateDecision

    data object UpToDate : UpdateDecision

    data object UnsupportedRelease : UpdateDecision

    data object InvalidInstalledVersion : UpdateDecision

    data object NoCompatibleAsset : UpdateDecision
}

data class GitHubReleaseRequest(
    val url: String,
    val accept: String,
    val userAgent: String,
)

data class GitHubReleaseHttpResponse(
    val statusCode: Int,
    val body: String,
)

fun interface GitHubReleaseFetcher {
    fun fetch(request: GitHubReleaseRequest): GitHubReleaseHttpResponse
}

sealed interface ReleaseFetchResult {
    data class Success(val release: GitHubReleaseDto) : ReleaseFetchResult

    data class Failure(
        val reason: ReleaseFetchFailure,
        val message: String,
    ) : ReleaseFetchResult
}

enum class ReleaseFetchFailure {
    TRANSPORT,
    HTTP_STATUS,
    INVALID_RESPONSE,
}
