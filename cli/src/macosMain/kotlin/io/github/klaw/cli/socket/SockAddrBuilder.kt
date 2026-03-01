package io.github.klaw.cli.socket

// macOS sockaddr_in: { uint8_t sin_len, uint8_t sin_family, uint16_t sin_port (BE), in_addr_t sin_addr (4B), char[8] zero }
// Total: 16 bytes
internal actual fun buildTcpSockAddrBytes(
    port: Int,
    ipBytes: ByteArray,
): ByteArray {
    require(ipBytes.size == 4) { "IPv4 address must be 4 bytes" }
    val result = ByteArray(16)
    result[0] = 16 // sin_len
    result[1] = 2 // sin_family: AF_INET = 2
    result[2] = (port shr 8).toByte() // sin_port high byte (big-endian)
    result[3] = (port and 0xFF).toByte() // sin_port low byte
    ipBytes.copyInto(result, destinationOffset = 4)
    return result
}
