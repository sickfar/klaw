package io.github.klaw.cli.update

import io.github.klaw.cli.BuildConfig
import io.github.klaw.cli.http.createHttpClient
import io.github.klaw.cli.util.CliLogger
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
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://api.github.com/repos/$owner/$repo/releases"

    override suspend fun fetchLatest(): GitHubRelease? = fetchRelease("$baseUrl/latest")

    override suspend fun fetchByTag(tag: String): GitHubRelease? = fetchRelease("$baseUrl/tags/$tag")

    private suspend fun fetchRelease(url: String): GitHubRelease? {
        CliLogger.debug { "fetchRelease: GET $url" }
        val client = createHttpClient()
        return try {
            val result =
                runCatching {
                    val response =
                        client.get(url) {
                            headers {
                                append("Accept", "application/vnd.github+json")
                                append("User-Agent", "klaw-cli/${BuildConfig.VERSION}")
                            }
                        }
                    if (response.status.isSuccess()) {
                        CliLogger.debug { "fetchRelease: ${response.status}" }
                        json.decodeFromString(GitHubRelease.serializer(), response.bodyAsText())
                    } else {
                        CliLogger.warn { "fetchRelease: HTTP ${response.status}" }
                        null
                    }
                }
            result.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                CliLogger.warn { "fetchRelease failed: ${e::class.simpleName}" }
                null
            }
        } finally {
            client.close()
        }
    }
}
