package io.github.klaw.cli.init

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.chmod
import platform.posix.mkdir

@OptIn(ExperimentalForeignApi::class)
internal actual fun mkdirMode755(path: String) {
    mkdir(path, 0x1EDu.toUShort()) // 0755 as UShort (mode_t on macOS)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun chmodReadWrite(path: String) {
    chmod(path, 0x180u.toUShort()) // 0600 as UShort (mode_t on macOS)
}
