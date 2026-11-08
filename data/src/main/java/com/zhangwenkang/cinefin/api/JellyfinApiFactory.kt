package com.zhangwenkang.cinefin.api

import java.util.UUID

data class JellyfinApiCandidate(
    val api: JellyfinApi,
    val baseUrl: String?,
    val accessToken: String?,
    val userId: UUID?,
)

/**
 * Creates isolated API clients for setup probes and publishes only an accepted candidate to the
 * process-global client from inside an identity projection permit.
 */
class JellyfinApiFactory(
    private val globalApi: JellyfinApi,
    private val candidateApiFactory: () -> JellyfinApi,
) {
    fun create(
        baseUrl: String? = null,
        accessToken: String? = null,
        userId: UUID? = null,
    ): JellyfinApiCandidate {
        val api = candidateApiFactory()
        check(api !== globalApi) { "Candidate API must be isolated from the global API" }
        if (baseUrl != null) {
            api.api.update(baseUrl = baseUrl, accessToken = accessToken)
        } else if (accessToken != null) {
            api.api.update(accessToken = accessToken)
        }
        api.userId = userId
        return JellyfinApiCandidate(
            api = api,
            baseUrl = baseUrl,
            accessToken = accessToken,
            userId = userId,
        )
    }

    fun publish(candidate: JellyfinApiCandidate) {
        val baseUrl = requireNotNull(candidate.baseUrl) { "Published identity requires a baseUrl" }
        globalApi.api.update(baseUrl = baseUrl, accessToken = candidate.accessToken)
        globalApi.userId = candidate.userId
        check(globalApi.api.baseUrl == baseUrl) { "Published identity baseUrl mismatch" }
        check(globalApi.api.accessToken == candidate.accessToken) {
            "Published identity accessToken mismatch"
        }
        check(globalApi.userId == candidate.userId) { "Published identity userId mismatch" }
    }
}
