package io.github.klaw.engine.tools

import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.common.protocol.ApprovalResponseMessage
import io.github.klaw.common.protocol.SocketMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApprovalServiceTest {
    private val sentMessages = mutableListOf<SocketMessage>()
    private val sender: suspend (SocketMessage) -> Unit = { sentMessages.add(it) }

    @Test
    fun `requestApproval sends approval_request and waits for response`() =
        runTest {
            val service = ApprovalService(sender)

            val result =
                async {
                    service.requestApproval("chat_1", "apt upgrade", 8, timeoutMin = 5)
                }

            // Let the coroutine start and send the request
            delay(10)
            assertEquals(1, sentMessages.size)
            val sent = sentMessages[0] as ApprovalRequestMessage
            assertEquals("chat_1", sent.chatId)
            assertEquals("apt upgrade", sent.command)
            assertEquals(8, sent.riskScore)

            // Complete with approval
            service.handleResponse(ApprovalResponseMessage(sent.id, approved = true))

            assertTrue(result.await())
        }

    @Test
    fun `handleResponse completes pending deferred`() =
        runTest {
            val service = ApprovalService(sender)

            val result =
                async {
                    service.requestApproval("chat_1", "cmd", 5, timeoutMin = 5)
                }
            delay(10)

            val sent = sentMessages[0] as ApprovalRequestMessage
            service.handleResponse(ApprovalResponseMessage(sent.id, approved = false))

            assertFalse(result.await())
        }

    @Test
    fun `timeout completes with false`() =
        runTest {
            val service = ApprovalService(sender)

            // Use 0 timeout to immediately timeout
            val result = service.requestApproval("chat_1", "cmd", 5, timeoutMin = 0)

            assertFalse(result)
        }

    @Test
    fun `notify sends outbound message`() =
        runTest {
            val service = ApprovalService(sender)

            service.notify("chat_1", "telegram", "systemctl restart klaw-engine")

            assertEquals(1, sentMessages.size)
        }

    @Test
    fun `unknown approval response id is ignored`() =
        runTest {
            val service = ApprovalService(sender)

            // Should not throw
            service.handleResponse(ApprovalResponseMessage("unknown_id", approved = true))
        }
}
