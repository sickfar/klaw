package io.github.klaw.cli.init

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.chmod
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.rename

@OptIn(ExperimentalForeignApi::class)
internal object EnvWriter {
    /**
     * Writes a .env file with `KEY=VALUE` lines, one per entry, with 0600 permissions.
     *
     * Uses a write-to-temp-then-rename strategy to avoid a race window where
     * the file exists with broad permissions before chmod takes effect.
     */
    fun write(
        path: String,
        entries: Map<String, String>,
    ) {
        val content = entries.entries.joinToString("\n") { (k, v) -> "$k=$v" } + "\n"
        val bytes = content.encodeToByteArray()
        val tmpPath = "$path.tmp"

        val file = fopen(tmpPath, "w") ?: error("Cannot open $tmpPath for writing")
        try {
            bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1.toULong(), bytes.size.toULong(), file)
            }
        } finally {
            fclose(file)
        }

        // chmod 0600 = 0x180 before rename so the final path is never visible with broad permissions
        chmod(tmpPath, 0x180u)
        rename(tmpPath, path)
    }
}
