package io.github.klaw.cli.socket

/**
 * Builds raw sockaddr_in byte layout for TCP connection.
 *
 * Layout differs by platform:
 * - macOS: { uint8_t sin_len=16, uint8_t sin_family=AF_INET(2), uint16_t sin_port(BE), in_addr(4B), zero(8B) } = 16 bytes
 * - Linux: { uint16_t sin_family=AF_INET(2) LE, uint16_t sin_port(BE), in_addr(4B), zero(8B) } = 16 bytes
 */
internal expect fun buildTcpSockAddrBytes(
    port: Int,
    ipBytes: ByteArray,
): ByteArray

/**
 * Parses an IPv4 address string (e.g. "127.0.0.1") into a 4-byte array.
 */
internal fun parseIpv4(host: String): ByteArray {
    val parts = host.split(".")
    require(parts.size == 4) { "Invalid IPv4 address: $host" }
    return ByteArray(4) { i ->
        val octet = parts[i].toIntOrNull() ?: error("Invalid IPv4 octet '${parts[i]}' in: $host")
        require(octet in 0..255) { "IPv4 octet out of range (0-255): $octet in $host" }
        octet.toByte()
    }
}
