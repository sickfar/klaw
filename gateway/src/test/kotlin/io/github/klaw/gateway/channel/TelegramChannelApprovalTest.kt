package io.github.klaw.gateway.channel

import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramChannelApprovalTest {
    private fun makeChannel(): TelegramChannel {
        val config = GatewayConfig(channels = ChannelsConfig())
        val jsonlWriter = mockk<ConversationJsonlWriter>(relaxed = true)
        return TelegramChannel(config, jsonlWriter)
    }

    private fun makeApprovalRequest(id: String = "apr-1"): ApprovalRequestMessage =
        ApprovalRequestMessage(
            id = id,
            chatId = "telegram_123",
            command = "echo test",
            riskScore = 5,
            timeout = 30,
        )

    @Test
    fun `handleApprovalCallback answers callback query with Approved text`() =
        runTest {
            val channel = makeChannel()
            val answeredQueryId = AtomicReference<String?>(null)
            val answeredText = AtomicReference<String?>(null)

            channel.answerCallbackAction = { queryId, text ->
                answeredQueryId.set(queryId)
                answeredText.set(text)
            }
            channel.editMessageAction = { _, _ -> }

            val callbackInvoked = AtomicBoolean(false)
            channel.pendingApprovals["apr-1"] = { callbackInvoked.set(true) }

            channel.handleApprovalCallback("approval:apr-1:yes", "query-42")

            assertEquals("query-42", answeredQueryId.get())
            assertTrue(answeredText.get()!!.contains("Approved", ignoreCase = true))
            assertTrue(callbackInvoked.get())
            assertNull(channel.pendingApprovals["apr-1"])
        }

    @Test
    fun `handleApprovalCallback executes in correct order - answer then edit then callback`() =
        runTest {
            val channel = makeChannel()
            val order = CopyOnWriteArrayList<String>()

            channel.answerCallbackAction = { _, _ -> order.add("answer") }
            channel.editMessageAction = { _, _ -> order.add("edit") }

            channel.approvalMessageIds["apr-1"] = Pair(123L, 456L)
            channel.pendingApprovals["apr-1"] = { order.add("callback") }

            channel.handleApprovalCallback("approval:apr-1:yes", "q-1")

            assertEquals(listOf("answer", "edit", "callback"), order)
        }

    @Test
    fun `handleApprovalCallback edits message to remove keyboard on approve`() =
        runTest {
            val channel = makeChannel()
            val editedChatId = AtomicReference<Long?>(null)
            val editedMessageId = AtomicReference<Long?>(null)

            channel.answerCallbackAction = { _, _ -> }
            channel.editMessageAction = { chatId, messageId ->
                editedChatId.set(chatId)
                editedMessageId.set(messageId)
            }

            channel.approvalMessageIds["apr-1"] = Pair(123L, 456L)
            channel.pendingApprovals["apr-1"] = { }

            channel.handleApprovalCallback("approval:apr-1:yes", "q-1")

            assertEquals(123L, editedChatId.get())
            assertEquals(456L, editedMessageId.get())
        }

    @Test
    fun `handleApprovalCallback answers with Rejected text on reject`() =
        runTest {
            val channel = makeChannel()
            val answeredText = AtomicReference<String?>(null)
            val callbackApproved = AtomicReference<Boolean?>(null)

            channel.answerCallbackAction = { _, text -> answeredText.set(text) }
            channel.editMessageAction = { _, _ -> }

            channel.approvalMessageIds["apr-1"] = Pair(123L, 456L)
            channel.pendingApprovals["apr-1"] = { approved -> callbackApproved.set(approved) }

            channel.handleApprovalCallback("approval:apr-1:no", "q-1")

            assertTrue(answeredText.get()!!.contains("Rejected", ignoreCase = true))
            assertEquals(false, callbackApproved.get())
        }

    @Test
    fun `handleApprovalCallback with unknown id does not answer query`() =
        runTest {
            val channel = makeChannel()
            val answerCalled = AtomicBoolean(false)
            val editCalled = AtomicBoolean(false)

            channel.answerCallbackAction = { _, _ -> answerCalled.set(true) }
            channel.editMessageAction = { _, _ -> editCalled.set(true) }

            channel.handleApprovalCallback("approval:apr-unknown:yes", "q-1")

            assertFalse(answerCalled.get())
            assertFalse(editCalled.get())
        }

    @Test
    fun `handleApprovalCallback with malformed data does nothing`() =
        runTest {
            val channel = makeChannel()
            val answerCalled = AtomicBoolean(false)

            channel.answerCallbackAction = { _, _ -> answerCalled.set(true) }
            channel.editMessageAction = { _, _ -> }

            channel.pendingApprovals["apr-1"] = { }

            // Only 2 parts instead of 3
            channel.handleApprovalCallback("approval:apr-1", "q-1")
            assertFalse(answerCalled.get())

            // 4 parts
            channel.handleApprovalCallback("approval:apr-1:yes:extra", "q-1")
            assertFalse(answerCalled.get())
        }

    @Test
    fun `handleApprovalCallback answer failure does not prevent callback invocation`() =
        runTest {
            val channel = makeChannel()

            channel.answerCallbackAction = { _, _ -> throw IOException("network error") }
            channel.editMessageAction = { _, _ -> }

            val callbackInvoked = AtomicBoolean(false)
            val callbackApproved = AtomicReference<Boolean?>(null)
            channel.pendingApprovals["apr-1"] = { approved ->
                callbackInvoked.set(true)
                callbackApproved.set(approved)
            }

            channel.handleApprovalCallback("approval:apr-1:yes", "q-1")

            assertTrue(callbackInvoked.get())
            assertEquals(true, callbackApproved.get())
        }

    @Test
    fun `sendApproval stores message id and pending approval`() =
        runTest {
            val channel = makeChannel()
            val sentMessageId = 789L

            channel.sendApprovalAction = { _, _, _ -> sentMessageId }

            val request = makeApprovalRequest("apr-store")
            channel.sendApproval("telegram_123", request) { }

            val stored = channel.approvalMessageIds["apr-store"]
            assertNotNull(stored)
            assertEquals(123L, stored.first)
            assertEquals(sentMessageId, stored.second)
            assertNotNull(channel.pendingApprovals["apr-store"])
        }

    @Test
    fun `handleApprovalCallback without stored message id still answers query and invokes callback`() =
        runTest {
            val channel = makeChannel()
            val answerCalled = AtomicBoolean(false)
            val editCalled = AtomicBoolean(false)
            val callbackInvoked = AtomicBoolean(false)

            channel.answerCallbackAction = { _, _ -> answerCalled.set(true) }
            channel.editMessageAction = { _, _ -> editCalled.set(true) }

            channel.pendingApprovals["apr-1"] = { callbackInvoked.set(true) }

            channel.handleApprovalCallback("approval:apr-1:yes", "q-1")

            assertTrue(answerCalled.get())
            assertFalse(editCalled.get()) // No message to edit
            assertTrue(callbackInvoked.get())
        }

    @Test
    fun `edit failure does not prevent callback invocation`() =
        runTest {
            val channel = makeChannel()

            channel.answerCallbackAction = { _, _ -> }
            channel.editMessageAction = { _, _ -> throw IOException("edit failed") }

            val callbackInvoked = AtomicBoolean(false)
            channel.approvalMessageIds["apr-1"] = Pair(123L, 456L)
            channel.pendingApprovals["apr-1"] = { callbackInvoked.set(true) }

            channel.handleApprovalCallback("approval:apr-1:yes", "q-1")

            assertTrue(callbackInvoked.get())
            assertNull(channel.approvalMessageIds["apr-1"])
        }
}
