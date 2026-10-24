package com.zhangwenkang.cinefin.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

object StableReleasePolicy {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
    }

    fun parseReleaseJson(payload: String): ReleaseParseResult {
        if (payload.isBlank()) {
            return ReleaseParseResult.Failure("body is empty")
        }
        return try {
            validateRequiredTypes(json.parseToJsonElement(payload))
            ReleaseParseResult.Success(json.decodeFromString<GitHubReleaseDto>(payload))
        } catch (exception: Exception) {
            ReleaseParseResult.Failure(exception.message ?: "invalid release json")
        }
    }

    fun evaluate(
        release: GitHubReleaseDto,
        currentVersion: String,
        supportedAbis: List<String>,
    ): UpdateDecision {
        if (release.draft || release.prerelease) {
            return UpdateDecision.UnsupportedRelease
        }
        val remoteVersion =
            AppVersion.parseReleaseTag(release.tagName) ?: return UpdateDecision.UnsupportedRelease
        val installedVersion =
            AppVersion.parseCurrentVersionName(currentVersion)
                ?: return UpdateDecision.InvalidInstalledVersion

        if (remoteVersion <= installedVersion) {
            return UpdateDecision.UpToDate
        }

        val asset =
            AbiAssetSelector.select(
                version = remoteVersion,
                assets = release.assets,
                supportedAbis = supportedAbis,
            ) ?: return UpdateDecision.NoCompatibleAsset
        return UpdateDecision.UpdateAvailable(remoteVersion, asset)
    }

    private fun validateRequiredTypes(element: kotlinx.serialization.json.JsonElement) {
        require(element is JsonObject) { "release response must be an object" }
        requireString(element, "tag_name")
        requireBoolean(element, "draft")
        requireBoolean(element, "prerelease")
        val assets = element["assets"]
        require(assets is JsonArray) { "assets must be an array" }
        assets.forEach { asset ->
            require(asset is JsonObject) { "asset must be an object" }
            requireString(asset, "name")
            requireLong(asset, "size")
            requireString(asset, "browser_download_url")
            val digest = asset["digest"]
            if (digest != null && digest != JsonNull) {
                require(digest is JsonPrimitive && digest.isString) {
                    "asset digest must be a string or null"
                }
            }
        }
    }

    private fun requireString(
        objectValue: JsonObject,
        key: String,
    ) {
        val value = objectValue[key]
        require(value is JsonPrimitive && value.isString) { "$key must be a string" }
    }

    private fun requireBoolean(
        objectValue: JsonObject,
        key: String,
    ) {
        val value = objectValue[key]
        require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null) {
            "$key must be a boolean"
        }
    }

    private fun requireLong(
        objectValue: JsonObject,
        key: String,
    ) {
        val value = objectValue[key]
        require(value is JsonPrimitive && !value.isString && value.longOrNull != null) {
            "$key must be an integer"
        }
    }
}
