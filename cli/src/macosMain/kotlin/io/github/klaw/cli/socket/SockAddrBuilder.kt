package io.github.klaw.cli.socket

// macOS sockaddr_un: { uint8_t sun_len, uint8_t sun_family, char[104] sun_path }
// sun_family is AF_UNIX = 1 as uint8_t at byte 1; sun_len (byte 0) can be zero.
internal actual fun buildSockAddrBytes(pathBytes: ByteArray): ByteArray {
    require(pathBytes.size <= 103) { "Socket path too long for macOS (max 103 bytes): ${pathBytes.size}" }
    val result = ByteArray(106) // fixed size: 2 header + 104 sun_path (includes null terminator)
    result[0] = 0 // sun_len: ignored by kernel, set to 0
    result[1] = 1 // sun_family: AF_UNIX = 1 (uint8_t on macOS)
    pathBytes.copyInto(result, destinationOffset = 2)
    return result
}
