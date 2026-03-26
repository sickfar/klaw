package io.github.klaw.gateway.channel

import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramStreamingTest {
    private fun makeChannel(): TelegramChannel {
        val config = GatewayConfig(channels = ChannelsConfig())
        val jsonlWriter = mockk<ConversationJsonlWriter>(relaxed = true)
        return TelegramChannel(config, jsonlWriter)
    }

    @Test
    fun `private chat sendStreamDelta accumulates text and calls draft action`() =
        runTest {
            val channel = makeChannel()
            val draftCalls = CopyOnWriteArrayList<Triple<Long, Long, String>>()

            channel.draftAction = { platformId, draftId, text ->
                draftCalls.add(Triple(platformId, draftId, text))
            }
            channel.chatTypes["telegram_123"] = "private"

            channel.sendStreamDelta("telegram_123", "Hello ", "stream-1")
            channel.sendStreamDelta("telegram_123", "world", "stream-1")

            assertTrue(draftCalls.isNotEmpty(), "At least one draft call expected")
            assertEquals(123L, draftCalls[0].first)
            assertTrue(draftCalls[0].second != 0L, "draftId must be non-zero")
            assertTrue(draftCalls[0].third.contains("Hello "))
        }

    @Test
    fun `private chat sendStreamEnd sends final message via send`() =
        runTest {
            val channel = makeChannel()
            val sentMessages = CopyOnWriteArrayList<Pair<Long, String>>()

            channel.sendAction = { platformId, text ->
                sentMessages.add(Pair(platformId, text))
            }
            channel.draftAction = { _, _, _ -> }
            channel.chatTypes["telegram_123"] = "private"

            channel.sendStreamDelta("telegram_123", "Hello ", "stream-1")
            channel.sendStreamEnd("telegram_123", "Hello world!", "stream-1")

            assertTrue(sentMessages.isNotEmpty(), "Final message should be sent via send()")
            assertEquals("Hello world!", sentMessages.last().second)
        }

    @Test
    fun `group chat sendStreamDelta is no-op`() =
        runTest {
            val channel = makeChannel()
            val draftCalls = AtomicInteger(0)

            channel.draftAction = { _, _, _ ->
                draftCalls.incrementAndGet()
            }
            channel.chatTypes["telegram_456"] = "group"

            channel.sendStreamDelta("telegram_456", "Hello ", "stream-1")

            assertEquals(0, draftCalls.get(), "Draft should not be called for group chats")
        }

    @Test
    fun `group chat sendStreamDelta with supergroup is no-op`() =
        runTest {
            val channel = makeChannel()
            val draftCalls = AtomicInteger(0)

            channel.draftAction = { _, _, _ ->
                draftCalls.incrementAndGet()
            }
            channel.chatTypes["telegram_789"] = "supergroup"

            channel.sendStreamDelta("telegram_789", "Hello ", "stream-1")

            assertEquals(0, draftCalls.get(), "Draft should not be called for supergroup chats")
        }

    @Test
    fun `unknown chat type sendStreamDelta is no-op`() =
        runTest {
            val channel = makeChannel()
            val draftCalls = AtomicInteger(0)

            channel.draftAction = { _, _, _ ->
                draftCalls.incrementAndGet()
            }

            channel.sendStreamDelta("telegram_111", "Hello ", "stream-1")

            assertEquals(0, draftCalls.get(), "Draft should not be called for unknown chat type")
        }

    @Test
    fun `throttle prevents rapid draft calls`() =
        runTest {
            val channel = makeChannel()
            val draftCalls = CopyOnWriteArrayList<String>()

            channel.draftAction = { _, _, text ->
                draftCalls.add(text)
            }
            channel.chatTypes["telegram_123"] = "private"

            repeat(20) { i ->
                channel.sendStreamDelta("telegram_123", "chunk$i ", "stream-1")
            }

            assertTrue(draftCalls.size >= 1, "At least one draft call expected")
            assertTrue(draftCalls.size < 20, "Throttle should prevent all 20 calls from going through")
        }

    @Test
    fun `sendStreamEnd removes stream state`() =
        runTest {
            val channel = makeChannel()
            channel.sendAction = { _, _ -> }
            channel.draftAction = { _, _, _ -> }
            channel.chatTypes["telegram_123"] = "private"

            channel.sendStreamDelta("telegram_123", "data", "stream-1")
            channel.sendStreamEnd("telegram_123", "full data", "stream-1")

            assertNull(channel.streamStates["telegram_123"], "Stream state should be removed after end")
        }

    @Test
    fun `sendStreamEnd with invalid chatId format does not crash`() =
        runTest {
            val channel = makeChannel()
            channel.sendAction = { _, _ -> }

            channel.sendStreamEnd("telegram_abc", "content", "stream-1")
        }

    @Test
    fun `sendStreamDelta with invalid chatId format does not crash`() =
        runTest {
            val channel = makeChannel()
            channel.draftAction = { _, _, _ -> }
            channel.chatTypes["telegram_abc"] = "private"

            channel.sendStreamDelta("telegram_abc", "data", "stream-1")
        }

    @Test
    fun `draft action failure does not crash`() =
        runTest {
            val channel = makeChannel()
            channel.draftAction = { _, _, _ ->
                throw RuntimeException("draft API error")
            }
            channel.chatTypes["telegram_123"] = "private"

            channel.sendStreamDelta("telegram_123", "data", "stream-1")
        }
}
