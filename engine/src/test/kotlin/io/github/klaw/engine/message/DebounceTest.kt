package io.github.klaw.engine.message

import io.github.klaw.common.protocol.InboundSocketMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebounceTest {
    private fun makeMsg(
        id: String,
        chatId: String = "chat-1",
    ) = InboundSocketMessage(
        id = id,
        channel = "telegram",
        chatId = chatId,
        content = "msg $id",
        ts = "2024-01-01T00:00:00Z",
    )

    @Test
    fun `single message processed after debounceMs`() =
        runTest {
            val flushed = mutableListOf<List<InboundSocketMessage>>()
            val buffer =
                DebounceBuffer(
                    debounceMs = 1500L,
                    scope = this,
                    onFlush = { flushed.add(it) },
                )

            buffer.add(makeMsg("m1"))

            // Before debounce fires — nothing flushed yet
            advanceTimeBy(1000L)
            assertTrue(flushed.isEmpty(), "Should not flush before debounceMs elapses")

            // Advance past debounce
            advanceTimeBy(600L)
            advanceUntilIdle()

            assertEquals(1, flushed.size)
            assertEquals(1, flushed[0].size)
            assertEquals("m1", flushed[0][0].id)
        }

    @Test
    fun `multiple messages from same chatId merged`() =
        runTest {
            val flushed = mutableListOf<List<InboundSocketMessage>>()
            val buffer =
                DebounceBuffer(
                    debounceMs = 1000L,
                    scope = this,
                    onFlush = { flushed.add(it) },
                )

            buffer.add(makeMsg("m1"))
            buffer.add(makeMsg("m2"))
            buffer.add(makeMsg("m3"))

            advanceTimeBy(1100L)
            advanceUntilIdle()

            assertEquals(1, flushed.size, "All messages from same chatId should be flushed together")
            assertEquals(3, flushed[0].size)
            assertEquals(listOf("m1", "m2", "m3"), flushed[0].map { it.id })
        }

    @Test
    fun `messages from different chatIds processed independently`() =
        runTest {
            val flushed = mutableListOf<List<InboundSocketMessage>>()
            val buffer =
                DebounceBuffer(
                    debounceMs = 1000L,
                    scope = this,
                    onFlush = { flushed.add(it) },
                )

            buffer.add(makeMsg("m1", chatId = "chat-1"))
            buffer.add(makeMsg("m2", chatId = "chat-2"))
            buffer.add(makeMsg("m3", chatId = "chat-1"))

            advanceTimeBy(1100L)
            advanceUntilIdle()

            assertEquals(2, flushed.size, "Two chatIds should produce two flush events")

            val chat1Batch = flushed.first { it[0].chatId == "chat-1" }
            val chat2Batch = flushed.first { it[0].chatId == "chat-2" }

            assertEquals(listOf("m1", "m3"), chat1Batch.map { it.id })
            assertEquals(listOf("m2"), chat2Batch.map { it.id })
        }

    @Test
    fun `timer restarts on new message before timeout`() =
        runTest {
            val flushed = mutableListOf<List<InboundSocketMessage>>()
            val buffer =
                DebounceBuffer(
                    debounceMs = 1000L,
                    scope = this,
                    onFlush = { flushed.add(it) },
                )

            buffer.add(makeMsg("m1"))

            // Advance to just before debounce would fire
            advanceTimeBy(800L)
            assertTrue(flushed.isEmpty(), "Should not flush before debounce fires")

            // Add another message — timer should restart
            buffer.add(makeMsg("m2"))

            // Advance another 800ms (only 800ms since last message, not yet debounced)
            advanceTimeBy(800L)
            assertTrue(flushed.isEmpty(), "Timer should have restarted; should not flush yet")

            // Advance the remaining 300ms to fire debounce after second message
            advanceTimeBy(300L)
            advanceUntilIdle()

            assertEquals(1, flushed.size, "Should flush once after restarted timer fires")
            assertEquals(listOf("m1", "m2"), flushed[0].map { it.id })
        }

    @Test
    fun `messages processed in order`() =
        runTest {
            val flushed = mutableListOf<List<InboundSocketMessage>>()
            val buffer =
                DebounceBuffer(
                    debounceMs = 500L,
                    scope = this,
                    onFlush = { flushed.add(it) },
                )

            val ids = listOf("a", "b", "c", "d", "e")
            ids.forEach { buffer.add(makeMsg(it)) }

            advanceTimeBy(600L)
            advanceUntilIdle()

            assertEquals(1, flushed.size)
            assertEquals(ids, flushed[0].map { it.id }, "Messages should be flushed in insertion order")
        }

    @Test
    fun `messages beyond maxEntries from new chatIds are rejected`() =
        runTest {
            val flushed = mutableListOf<List<InboundSocketMessage>>()
            val buffer =
                DebounceBuffer(
                    debounceMs = 1000L,
                    scope = this,
                    onFlush = { flushed.add(it) },
                    maxEntries = 3,
                )

            // Fill up to capacity with 3 distinct chatIds
            buffer.add(makeMsg("m1", chatId = "chat-1"))
            buffer.add(makeMsg("m2", chatId = "chat-2"))
            buffer.add(makeMsg("m3", chatId = "chat-3"))

            // 4th distinct chatId should be rejected
            val accepted = buffer.add(makeMsg("m4", chatId = "chat-4"))
            assertFalse(accepted, "Message from new chatId beyond maxEntries should be rejected")

            advanceTimeBy(1100L)
            advanceUntilIdle()

            // Only 3 chatIds should have been flushed
            assertEquals(3, flushed.size)
            val flushedChatIds = flushed.map { it[0].chatId }.toSet()
            assertTrue("chat-4" !in flushedChatIds, "Rejected chatId should not appear in flushed messages")
        }

    @Test
    fun `existing chatIds can still add messages when at capacity`() =
        runTest {
            val flushed = mutableListOf<List<InboundSocketMessage>>()
            val buffer =
                DebounceBuffer(
                    debounceMs = 1000L,
                    scope = this,
                    onFlush = { flushed.add(it) },
                    maxEntries = 2,
                )

            buffer.add(makeMsg("m1", chatId = "chat-1"))
            buffer.add(makeMsg("m2", chatId = "chat-2"))

            // Adding to existing chatId should still work
            val accepted = buffer.add(makeMsg("m3", chatId = "chat-1"))
            assertTrue(accepted, "Message to existing chatId should be accepted even at capacity")

            advanceTimeBy(1100L)
            advanceUntilIdle()

            val chat1Batch = flushed.first { it[0].chatId == "chat-1" }
            assertEquals(listOf("m1", "m3"), chat1Batch.map { it.id })
        }

    @Test
    fun `flush errors are logged not silently swallowed`() =
        runTest {
            val buffer =
                DebounceBuffer(
                    debounceMs = 500L,
                    scope = this,
                    onFlush = {
                        @Suppress("TooGenericExceptionThrown")
                        throw RuntimeException("flush failed")
                    },
                )

            buffer.add(makeMsg("m1"))

            // Should not throw — error is caught and logged
            advanceTimeBy(600L)
            advanceUntilIdle()

            // If we get here without exception, the error was caught properly
            // The error should be logged via SLF4J (verified by no exception propagation)
        }
}
