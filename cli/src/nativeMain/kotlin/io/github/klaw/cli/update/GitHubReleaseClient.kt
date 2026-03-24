package io.github.klaw.cli.update

import io.github.klaw.cli.BuildConfig
import io.github.klaw.cli.util.CliLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.Json
import platform.posix.fread
import platform.posix.pclose
import platform.posix.popen

internal interface GitHubReleaseClient {
    suspend fun fetchLatest(): GitHubRelease?

    suspend fun fetchByTag(tag: String): GitHubRelease?
}

@OptIn(ExperimentalForeignApi::class)
internal class GitHubReleaseClientImpl(
    private val owner: String = BuildConfig.GITHUB_OWNER,
    private val repo: String = BuildConfig.GITHUB_REPO,
) : GitHubReleaseClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://api.github.com/repos/$owner/$repo/releases"

    override suspend fun fetchLatest(): GitHubRelease? = fetchRelease("$baseUrl/latest")

    override suspend fun fetchByTag(tag: String): GitHubRelease? = fetchRelease("$baseUrl/tags/$tag")

    private fun fetchRelease(url: String): GitHubRelease? {
        CliLogger.debug { "fetchRelease: GET $url" }
        val cmd =
            "curl -fsSL " +
                "-H 'Accept: application/vnd.github+json' " +
                "-H 'User-Agent: klaw-cli/${BuildConfig.VERSION}' " +
                "'$url' 2>/dev/null"
        val body = runCommand(cmd)
        if (body.isNullOrBlank()) {
            CliLogger.warn { "fetchRelease: curl returned empty response for $url" }
            return null
        }
        return try {
            val release = json.decodeFromString(GitHubRelease.serializer(), body)
            CliLogger.debug { "fetchRelease: parsed release ${release.tagName}" }
            release
        } catch (e: kotlinx.serialization.SerializationException) {
            CliLogger.warn { "fetchRelease: failed to parse response: ${e::class.simpleName}" }
            null
        } catch (e: IllegalArgumentException) {
            CliLogger.warn { "fetchRelease: failed to parse response: ${e::class.simpleName}" }
            null
        }
    }

    private fun runCommand(cmd: String): String? {
        val pipe = popen(cmd, "r") ?: return null
        val sb = StringBuilder()
        val buf = ByteArray(BUF_SIZE)
        buf.usePinned { pinned ->
            var n: Int
            do {
                n = fread(pinned.addressOf(0), 1.convert(), buf.size.convert(), pipe).toInt()
                if (n > 0) sb.append(buf.decodeToString(0, n))
            } while (n > 0)
        }
        pclose(pipe)
        return sb.toString()
    }

    private companion object {
        const val BUF_SIZE = 8192
    }
}
