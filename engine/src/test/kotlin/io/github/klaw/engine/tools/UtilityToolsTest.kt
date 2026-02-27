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
    fun `currentTime returns date and timezone`() =
        runTest {
            val result = tools.currentTime()
            // Should contain year, e.g. "2026"
            assertTrue(result.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} .*")))
        }

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
