package io.github.klaw.cli.update

import platform.posix.rename
import platform.posix.unlink

internal class Downloader(
    private val commandRunner: (String) -> Int,
) {
    fun download(
        url: String,
        destPath: String,
    ): Boolean {
        require(!url.contains('\'')) { "URL must not contain single quotes" }
        require(!destPath.contains('\'')) { "Path must not contain single quotes" }
        return commandRunner("curl -fsSL -o '$destPath' '$url'") == 0
    }

    fun downloadAndReplace(
        url: String,
        destPath: String,
    ): Boolean {
        val tmpPath = "$destPath.update.tmp"
        if (!download(url, tmpPath)) return false
        if (rename(tmpPath, destPath) != 0) {
            unlink(tmpPath)
            return false
        }
        return true
    }
}
