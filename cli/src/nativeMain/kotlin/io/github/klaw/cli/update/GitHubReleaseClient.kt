package io.github.klaw.cli.update

import io.github.klaw.cli.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

internal interface GitHubReleaseClient {
    suspend fun fetchLatest(): GitHubRelease?

    suspend fun fetchByTag(tag: String): GitHubRelease?
}

internal class GitHubReleaseClientImpl(
    private val owner: String = BuildConfig.GITHUB_OWNER,
    private val repo: String = BuildConfig.GITHUB_REPO,
) : GitHubReleaseClient {
    private val client = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://api.github.com/repos/$owner/$repo/releases"

    override suspend fun fetchLatest(): GitHubRelease? = fetchRelease("$baseUrl/latest")

    override suspend fun fetchByTag(tag: String): GitHubRelease? = fetchRelease("$baseUrl/tags/$tag")

    private suspend fun fetchRelease(url: String): GitHubRelease? =
        try {
            val response =
                client.get(url) {
                    headers {
                        append("Accept", "application/vnd.github+json")
                        append("User-Agent", "klaw-cli/${BuildConfig.VERSION}")
                    }
                }
            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                json.decodeFromString(GitHubRelease.serializer(), body)
            } else {
                null
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // CancellationException is not swallowed since we use try-catch (not runCatching)
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
}
