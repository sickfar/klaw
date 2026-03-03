package io.github.klaw.cli.socket

// Linux sockaddr_in: { uint16_t sin_family (LE), uint16_t sin_port (BE), in_addr_t sin_addr (4B), char[8] zero }
// Total: 16 bytes
private const val SOCKADDR_SIZE = 16
private const val IPV4_BYTES = 4
private const val AF_INET_LOW: Byte = 2 // AF_INET = 2, little-endian low byte
private const val AF_INET_HIGH: Byte = 0 // AF_INET = 2, little-endian high byte
private const val SIN_FAMILY_LO_OFFSET = 0
private const val SIN_FAMILY_HI_OFFSET = 1
private const val SIN_PORT_HI_OFFSET = 2
private const val SIN_PORT_LO_OFFSET = 3
private const val SIN_ADDR_OFFSET = 4
private const val PORT_HIGH_BYTE_SHIFT = 8
private const val PORT_LOW_BYTE_MASK = 0xFF

internal actual fun buildTcpSockAddrBytes(
    port: Int,
    ipBytes: ByteArray,
): ByteArray {
    require(ipBytes.size == IPV4_BYTES) { "IPv4 address must be 4 bytes" }
    val result = ByteArray(SOCKADDR_SIZE)
    result[SIN_FAMILY_LO_OFFSET] = AF_INET_LOW // sin_family low byte (LE)
    result[SIN_FAMILY_HI_OFFSET] = AF_INET_HIGH // sin_family high byte (LE)
    result[SIN_PORT_HI_OFFSET] = (port shr PORT_HIGH_BYTE_SHIFT).toByte() // sin_port high byte (big-endian)
    result[SIN_PORT_LO_OFFSET] = (port and PORT_LOW_BYTE_MASK).toByte() // sin_port low byte
    ipBytes.copyInto(result, destinationOffset = SIN_ADDR_OFFSET)
    return result
}
