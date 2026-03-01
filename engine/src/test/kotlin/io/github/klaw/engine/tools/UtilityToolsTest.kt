package io.github.klaw.engine.tools

import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.engine.socket.EngineSocketServer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UtilityToolsTest {
    private val socketServer = mockk<EngineSocketServer>()
    private val tools = UtilityTools { socketServer }

    @Test
    fun `sendMessage calls pushToGateway`() =
        runTest {
            coEvery { socketServer.pushToGateway(any()) } returns Unit

            val result = tools.sendMessage("telegram", "123", "hello")
            assertTrue(result.startsWith("OK"))
            coVerify {
                socketServer.pushToGateway(
                    OutboundSocketMessage(channel = "telegram", chatId = "123", content = "hello"),
                )
            }
        }

    @Test
    fun `sendMessage returns error on exception`() =
        runTest {
            coEvery { socketServer.pushToGateway(any()) } throws RuntimeException("connection lost")

            val result = tools.sendMessage("telegram", "123", "hello")
            assertTrue(result.startsWith("Error"))
        }
}
