package io.github.klaw.cli.init

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.chmod
import platform.posix.mkdir

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
