package io.github.klaw.gateway.channel

import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.common.protocol.ChatFrame
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LocalWsChannelApprovalTest {
    @TempDir
    lateinit var tempDir: File

    private val json = Json { ignoreUnknownKeys = true }

    private fun makeChannel(): LocalWsChannel = LocalWsChannel(ConversationJsonlWriter(tempDir.absolutePath))

    private fun mockSession(): DefaultWebSocketServerSession =
        mockk(relaxed = true) {
            coEvery { send(any<Frame>()) } returns Unit
        }

    private fun approvalRequest(
        id: String = "apr-1",
        command: String = "rm -rf /tmp/test",
        riskScore: Int = 7,
        timeout: Int = 30,
    ) = ApprovalRequestMessage(
        id = id,
        chatId = "local_ws_default",
        command = command,
        riskScore = riskScore,
        timeout = timeout,
    )

    @Test
    fun `sendApproval sends approval_request frame via WebSocket session`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()

            // Set active session
            channel.handleIncoming("trigger", session)

            channel.sendApproval("local_ws_default", approvalRequest()) { }

            coVerify {
                session.send(
                    match<Frame.Text> {
                        val text = it.readText()
                        val frame = json.decodeFromString<ChatFrame>(text)
                        frame.type == "approval_request" &&
                            frame.content == "rm -rf /tmp/test" &&
                            frame.approvalId == "apr-1" &&
                            frame.riskScore == 7 &&
                            frame.timeout == 30
                    },
                )
            }

            channel.stop()
        }

    @Test
    fun `sendApproval with no active session does nothing`() =
        runBlocking {
            val channel = makeChannel()

            // No handleIncoming, so no active session
            channel.sendApproval("local_ws_default", approvalRequest()) { }
            // Should not throw
        }

    @Test
    fun `resolveApproval invokes pending callback with approved=true`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()
            channel.handleIncoming("trigger", session)

            var callbackResult: Boolean? = null
            channel.sendApproval("local_ws_default", approvalRequest(id = "apr-2")) { approved ->
                callbackResult = approved
            }

            channel.resolveApproval("apr-2", true)

            assertEquals(true, callbackResult)
            channel.stop()
        }

    @Test
    fun `resolveApproval invokes pending callback with approved=false`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()
            channel.handleIncoming("trigger", session)

            var callbackResult: Boolean? = null
            channel.sendApproval("local_ws_default", approvalRequest(id = "apr-3")) { approved ->
                callbackResult = approved
            }

            channel.resolveApproval("apr-3", false)

            assertEquals(false, callbackResult)
            channel.stop()
        }

    @Test
    fun `resolveApproval with unknown id does nothing`() =
        runBlocking {
            val channel = makeChannel()

            // No pending approvals — should not throw
            channel.resolveApproval("unknown-id", true)
        }

    @Test
    fun `resolveApproval removes callback so second call is no-op`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()
            channel.handleIncoming("trigger", session)

            var callCount = 0
            channel.sendApproval("local_ws_default", approvalRequest(id = "apr-4")) {
                callCount++
            }

            channel.resolveApproval("apr-4", true)
            channel.resolveApproval("apr-4", true)

            assertEquals(1, callCount)
            channel.stop()
        }

    @Test
    fun `sendApproval cleans up pending approval on send failure`() =
        runBlocking {
            val channel = makeChannel()
            val failSession =
                mockk<DefaultWebSocketServerSession>(relaxed = true) {
                    coEvery { send(any<Frame>()) } throws RuntimeException("WS closed")
                }
            channel.handleIncoming("trigger", failSession)

            var callbackInvoked = false
            channel.sendApproval("local_ws_default", approvalRequest(id = "apr-5")) {
                callbackInvoked = true
            }

            // Callback should NOT have been called since send failed
            assertTrue(!callbackInvoked)

            // resolveApproval should be a no-op since the pending was cleaned up
            channel.resolveApproval("apr-5", true)
            assertTrue(!callbackInvoked)

            channel.stop()
        }
}
