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
    fun `zero timeout means infinite wait - awaits until response`() =
        runTest {
            val service = ApprovalService(sender)

            val result =
                async {
                    service.requestApproval("chat_1", "cmd", 5, timeoutMin = 0)
                }

            delay(10)
            val sent = sentMessages[0] as ApprovalRequestMessage
            service.handleResponse(ApprovalResponseMessage(sent.id, approved = true))

            assertTrue(result.await())
        }

    @Test
    fun `negative timeout means infinite wait - awaits until response`() =
        runTest {
            val service = ApprovalService(sender)

            val result =
                async {
                    service.requestApproval("chat_1", "cmd", 5, timeoutMin = -1)
                }

            delay(10)
            val sent = sentMessages[0] as ApprovalRequestMessage
            service.handleResponse(ApprovalResponseMessage(sent.id, approved = false))

            assertFalse(result.await())
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

    @Test
    fun `denyPendingForChatId completes pending with false and returns denied ids`() =
        runTest {
            val service = ApprovalService(sender)

            val result =
                async {
                    service.requestApproval("chat_1", "apt upgrade", 8, timeoutMin = 0)
                }

            delay(10)
            assertEquals(1, sentMessages.size)
            val sent = sentMessages[0] as ApprovalRequestMessage

            val deniedIds = service.denyPendingForChatId("chat_1")
            assertEquals(1, deniedIds.size)
            assertEquals(sent.id, deniedIds[0])
            assertFalse(result.await())
        }

    @Test
    fun `denyPendingForChatId ignores other chatIds`() =
        runTest {
            val service = ApprovalService(sender)

            val result1 =
                async {
                    service.requestApproval("chat_1", "cmd1", 5, timeoutMin = 0)
                }
            val result2 =
                async {
                    service.requestApproval("chat_2", "cmd2", 5, timeoutMin = 0)
                }

            delay(10)
            assertEquals(2, sentMessages.size)

            val deniedIds = service.denyPendingForChatId("chat_1")
            assertEquals(1, deniedIds.size)
            assertFalse(result1.await())

            // chat_2 should still be pending — complete it manually
            val sent2 = sentMessages[1] as ApprovalRequestMessage
            service.handleResponse(ApprovalResponseMessage(sent2.id, approved = true))
            assertTrue(result2.await())
        }
}
