package io.github.klaw.cli.update

import io.github.klaw.cli.util.CliLogger
import io.github.klaw.cli.util.readFileText

/**
 * Verifies file integrity using SHA-256 checksums.
 * Uses `sha256sum` with fallback to `shasum -a 256` (macOS).
 */
internal class ChecksumVerifier(
    private val commandOutput: (String) -> String?,
) {
    /**
     * Computes the SHA-256 hash of the file at [filePath] by invoking
     * `sha256sum` with a fallback to `shasum -a 256` (macOS).
     * Returns the hex digest string, or null if the command fails.
     */
    private fun computeSha256(filePath: String): String? {
        require(!filePath.contains('\'')) { "Path must not contain single quotes" }

        fun tryTool(cmd: String): String? {
            val out = commandOutput("$cmd '$filePath' 2>/dev/null") ?: return null
            val hash = out.trim().substringBefore(' ')
            return hash.ifEmpty { null }
        }
        return tryTool("sha256sum") ?: tryTool("shasum -a 256")
    }

    fun verify(
        filePath: String,
        expectedHash: String,
    ): Boolean {
        val actual = computeSha256(filePath) ?: return false
        return actual == expectedHash
    }

    companion object {
        const val CHECKSUMS_FILENAME = "checksums.sha256"

        private val WHITESPACE_REGEX = Regex("\\s+")

        fun parseChecksums(content: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            for (line in content.lines()) {
                if (line.isBlank()) continue
                val parts = line.trim().split(WHITESPACE_REGEX, limit = 2)
                if (parts.size != 2) continue
                result[parts[1]] = parts[0]
            }
            return result
        }

        fun downloadAndParse(
            checksumUrl: String,
            tmpPath: String,
            downloader: Downloader,
        ): Map<String, String> {
            if (!downloader.download(checksumUrl, tmpPath)) {
                CliLogger.debug { "Failed to download checksums file" }
                return emptyMap()
            }
            val content = readFileText(tmpPath)
            platform.posix.unlink(tmpPath)
            if (content == null) {
                CliLogger.warn { "Downloaded checksums file but failed to read it" }
                return emptyMap()
            }
            return parseChecksums(content)
        }
    }
}
