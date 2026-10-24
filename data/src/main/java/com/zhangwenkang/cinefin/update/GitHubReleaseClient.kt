package com.zhangwenkang.cinefin.update

import java.util.concurrent.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request

class GitHubReleaseClient(private val fetcher: GitHubReleaseFetcher) {
    fun fetchLatestRelease(): ReleaseFetchResult {
        val request =
            GitHubReleaseRequest(
                url = GITHUB_LATEST_RELEASE_API_URL,
                accept = "application/vnd.github+json",
                userAgent = "Aurorama-Android",
            )
        val response =
            try {
                fetcher.fetch(request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                return ReleaseFetchResult.Failure(
                    reason = ReleaseFetchFailure.TRANSPORT,
                    message = exception.message ?: "release request failed",
                )
            }

        if (response.statusCode !in 200..299) {
            return ReleaseFetchResult.Failure(
                reason = ReleaseFetchFailure.HTTP_STATUS,
                message = "unexpected HTTP status ${response.statusCode}",
            )
        }
        return when (val parsed = StableReleasePolicy.parseReleaseJson(response.body)) {
            is ReleaseParseResult.Success -> ReleaseFetchResult.Success(parsed.release)
            is ReleaseParseResult.Failure ->
                ReleaseFetchResult.Failure(
                    reason = ReleaseFetchFailure.INVALID_RESPONSE,
                    message = parsed.reason,
                )
        }
    }
}

class OkHttpGitHubReleaseFetcher(private val client: OkHttpClient = OkHttpClient()) :
    GitHubReleaseFetcher {
    override fun fetch(request: GitHubReleaseRequest): GitHubReleaseHttpResponse {
        val httpRequest =
            Request.Builder()
                .url(request.url)
                .header("Accept", request.accept)
                .header("User-Agent", request.userAgent)
                .get()
                .build()
        return client.newCall(httpRequest).execute().use { response ->
            GitHubReleaseHttpResponse(
                statusCode = response.code,
                body = response.body.string(),
            )
        }
    }
}
