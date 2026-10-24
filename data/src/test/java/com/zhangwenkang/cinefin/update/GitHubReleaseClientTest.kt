package com.zhangwenkang.cinefin.update

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseClientTest {
    @Test
    fun fetchesOnlyTheFixedEndpointAndParsesFixture() {
        var request: GitHubReleaseRequest? = null
        val client =
            GitHubReleaseClient(
                fetcher =
                    GitHubReleaseFetcher { received ->
                        request = received
                        GitHubReleaseHttpResponse(statusCode = 200, body = fixture)
                    }
            )

        val result = client.fetchLatestRelease()

        assertEquals(
            "https://api.github.com/repos/zhangwenkang-github/Aurorama/releases/latest",
            request?.url,
        )
        assertTrue(request?.userAgent?.isNotBlank() == true)
        assertTrue(request?.accept?.contains("application/vnd.github+json") == true)
        assertTrue(result is ReleaseFetchResult.Success)
        assertEquals(
            "v1.3.0",
            (result as ReleaseFetchResult.Success).release.tagName,
        )
    }

    @Test
    fun failsClosedForHttpAndMalformedResponses() {
        val responses =
            listOf(
                GitHubReleaseHttpResponse(statusCode = 404, body = """{"message":"Not Found"}"""),
                GitHubReleaseHttpResponse(statusCode = 500, body = fixture),
                GitHubReleaseHttpResponse(statusCode = 200, body = ""),
                GitHubReleaseHttpResponse(statusCode = 200, body = """{"tag_name":"v1.3.0"}"""),
                GitHubReleaseHttpResponse(statusCode = 200, body = "{not-json"),
            )

        responses.forEach { response ->
            val client = GitHubReleaseClient(fetcher = GitHubReleaseFetcher { response })
            assertTrue(
                response.toString(),
                client.fetchLatestRelease() is ReleaseFetchResult.Failure,
            )
        }
    }

    @Test
    fun convertsTransportFailureToFailure() {
        val client =
            GitHubReleaseClient(fetcher = GitHubReleaseFetcher { throw IOException("offline") })

        assertTrue(client.fetchLatestRelease() is ReleaseFetchResult.Failure)
    }

    private companion object {
        const val fixture =
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
