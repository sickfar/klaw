package io.github.klaw.engine.command.commands

import io.github.klaw.common.config.ModelConfig
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.fixtures.testEngineConfig
import io.github.klaw.engine.session.Session
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class ModelsCommandTest {
    private fun commandMsg(
        command: String,
        args: String? = null,
        chatId: String = "c1",
    ) = CommandSocketMessage(channel = "telegram", chatId = chatId, command = command, args = args)

    private fun session() =
        Session(
            chatId = "c1",
            model = "test/model",
            segmentStart = Clock.System.now().toString(),
            createdAt = Clock.System.now(),
        )

    @Test
    fun `lists all configured models`() =
        runTest {
            val config = testEngineConfig(models = mapOf("gpt-4" to ModelConfig(), "glm-5" to ModelConfig()))
            val result = ModelsCommand(config).handle(commandMsg("models"), session())
            assertTrue(result.contains("gpt-4"))
            assertTrue(result.contains("glm-5"))
        }

    @Test
    fun `shows context length for known model`() =
        runTest {
            val config = testEngineConfig(models = mapOf("gpt-4o" to ModelConfig()))
            val result = ModelsCommand(config).handle(commandMsg("models"), session())
            assertFalse(result.contains("default"), "Expected numeric context budget, got: $result")
            assertTrue(result.contains("gpt-4o"))
        }
}
