package io.github.klaw.cli.socket

/**
 * Builds the raw sockaddr_un byte layout for a given socket path.
 *
 * Layout differs by platform:
 * - macOS: { uint8_t sun_len, uint8_t sun_family, char[104] sun_path }  (sun_path at byte 2)
 * - Linux: { uint16_t sun_family (LE), char[108] sun_path }              (sun_path at byte 2)
 *
 * Both platforms: sun_path starts at offset 2.
 */
internal expect fun buildSockAddrBytes(pathBytes: ByteArray): ByteArray
