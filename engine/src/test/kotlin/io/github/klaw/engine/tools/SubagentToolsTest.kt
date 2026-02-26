package io.github.klaw.engine.tools

import io.github.klaw.engine.message.MessageProcessor
import io.github.klaw.engine.message.ScheduledMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.inject.Provider
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubagentToolsTest {
    private val processor = mockk<MessageProcessor>()
    private val provider =
        mockk<Provider<MessageProcessor>> {
            every { get() } returns processor
        }
    private val tools = SubagentTools(provider)

    @Test
    fun `spawn calls handleScheduledMessage with correct ScheduledMessage`() =
        runTest {
            every { processor.handleScheduledMessage(any()) } returns Job()

            val result = tools.spawn("test-agent", "do something", "gpt-4", "chat:123")

            verify {
                processor.handleScheduledMessage(
                    ScheduledMessage(
                        name = "test-agent",
                        message = "do something",
                        model = "gpt-4",
                        injectInto = "chat:123",
                    ),
                )
            }
            assertTrue(result.contains("test-agent"))
        }

    @Test
    fun `spawn returns immediately`() =
        runTest {
            every { processor.handleScheduledMessage(any()) } returns Job()

            val result = tools.spawn("agent", "msg")
            assertTrue(result.isNotEmpty())
        }
}
