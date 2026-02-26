package io.github.klaw.cli.socket

import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.common.protocol.CliRequestMessage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.posix.AF_UNIX
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.close
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.socket
import platform.posix.timeval

@OptIn(ExperimentalForeignApi::class)
class EngineSocketClient(
    private val socketPath: String = KlawPaths.engineSocket,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    fun request(
        command: String,
        params: Map<String, String> = emptyMap(),
    ): String {
        val fd = socket(AF_UNIX, SOCK_STREAM, 0)
        if (fd < 0) throw EngineNotRunningException()

        val pathBytes = socketPath.encodeToByteArray()
        val addrBytes = buildSockAddrBytes(pathBytes)
        val connectResult =
            addrBytes.usePinned { pinned ->
                platform.posix.connect(fd, pinned.addressOf(0).reinterpret<sockaddr>(), addrBytes.size.convert())
            }
        if (connectResult < 0) {
            close(fd)
            throw EngineNotRunningException()
        }

        // Set 120-second receive timeout to prevent indefinite blocking on slow LLM responses
        memScoped {
            val tv = alloc<timeval>()
            tv.tv_sec = 120.convert()
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
        }

        val lineBytes = (json.encodeToString(CliRequestMessage(command, params)) + "\n").encodeToByteArray()
        var sent = 0
        lineBytes.usePinned { pinned ->
            while (sent < lineBytes.size) {
                val n = send(fd, pinned.addressOf(sent), (lineBytes.size - sent).convert(), 0).toInt()
                if (n <= 0) {
                    close(fd)
                    throw EngineNotRunningException()
                }
                sent += n
            }
        }

        val sb = StringBuilder()
        val recvBuf = ByteArray(4096)
        recvBuf.usePinned { pinned ->
            while (true) {
                val n = recv(fd, pinned.addressOf(0), 4095.convert(), 0).toInt()
                if (n <= 0) break
                sb.append(recvBuf.decodeToString(0, n))
                if ('\n' in sb) break
            }
        }

        close(fd)
        return sb.toString().trim()
    }
}
