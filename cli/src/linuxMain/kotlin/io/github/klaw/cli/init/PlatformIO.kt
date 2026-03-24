package io.github.klaw.cli.init

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.chmod
import platform.posix.errno
import platform.posix.mkdir
import platform.posix.symlink
import platform.posix.unlink

@OptIn(ExperimentalForeignApi::class)
internal actual fun mkdirMode755(path: String) {
    mkdir(path, 0x1EDu) // 0755 as UInt (mode_t on Linux)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun chmodReadWrite(path: String) {
    chmod(path, 0x180u) // 0600 as UInt (mode_t on Linux)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun chmodWorldRwx(path: String) {
    chmod(path, 0x1FFu) // 0777 as UInt (mode_t on Linux)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun chmodExecutable(path: String) {
    chmod(path, 0x1EDu) // 0755 as UInt (mode_t on Linux)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun createSymlink(
    target: String,
    link: String,
) {
    unlink(link)
    if (symlink(target, link) != 0) {
        error("symlink($target, $link) failed: errno=$errno")
    }
}
