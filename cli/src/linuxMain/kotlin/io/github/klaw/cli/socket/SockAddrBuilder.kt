package io.github.klaw.cli.socket

// Linux sockaddr_in: { uint16_t sin_family (LE), uint16_t sin_port (BE), in_addr_t sin_addr (4B), char[8] zero }
// Total: 16 bytes
internal actual fun buildTcpSockAddrBytes(
    port: Int,
    ipBytes: ByteArray,
): ByteArray {
    require(ipBytes.size == 4) { "IPv4 address must be 4 bytes" }
    val result = ByteArray(16)
    result[0] = 2 // sin_family low byte: AF_INET = 2 (little-endian uint16)
    result[1] = 0 // sin_family high byte
    result[2] = (port shr 8).toByte() // sin_port high byte (big-endian)
    result[3] = (port and 0xFF).toByte() // sin_port low byte
    ipBytes.copyInto(result, destinationOffset = 4)
    return result
}
