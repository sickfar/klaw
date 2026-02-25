package io.github.klaw.cli.socket

// Linux sockaddr_un: { uint16_t sun_family (little-endian), char[108] sun_path }
// AF_UNIX = 1 as uint16_t LE: byte[0]=0x01, byte[1]=0x00
internal actual fun buildSockAddrBytes(pathBytes: ByteArray): ByteArray {
    require(pathBytes.size <= 107) { "Socket path too long for Linux (max 107 bytes): ${pathBytes.size}" }
    val result = ByteArray(110) // fixed size: 2 header + 108 sun_path (includes null terminator)
    result[0] = 1 // sun_family low byte: AF_UNIX = 1 (little-endian uint16)
    result[1] = 0 // sun_family high byte
    pathBytes.copyInto(result, destinationOffset = 2)
    return result
}
