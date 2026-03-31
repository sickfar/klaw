package io.github.klaw.engine.command.commands

import io.github.klaw.common.config.ModelConfig
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.fixtures.testEngineConfig
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class ModelCommandTest {
    private fun commandMsg(
        command: String,
        args: String? = null,
        chatId: String = "c1",
        channel: String = "telegram",
    ) = CommandSocketMessage(channel = channel, chatId = chatId, command = command, args = args)

    private fun session(
        chatId: String = "c1",
        model: String = "test/model",
    ) = Session(
        chatId = chatId,
        model = model,
        segmentStart = Clock.System.now().toString(),
        createdAt = Clock.System.now(),
    )

    @Test
    fun `shows current model when no args`() =
        runTest {
            val result =
                ModelCommand(
                    testEngineConfig(models = mapOf("glm-5" to ModelConfig())),
                    mockk(),
                ).handle(commandMsg("model"), session(model = "glm-5"))
            assertEquals("Current model: glm-5", result)
        }

    @Test
    fun `switches to valid model`() =
        runTest {
            val sessionMgr = mockk<SessionManager>(relaxed = true)
            val config = testEngineConfig(models = mapOf("glm-5" to ModelConfig(), "qwen" to ModelConfig()))
            val result =
                ModelCommand(
                    config,
                    sessionMgr,
                ).handle(commandMsg("model", args = "qwen"), session(model = "glm-5"))
            assertEquals("Switched to model: qwen", result)
            coVerify { sessionMgr.updateModel(any(), "qwen") }
        }

    @Test
    fun `returns error for unknown model`() =
        runTest {
            val config = testEngineConfig(models = mapOf("glm-5" to ModelConfig()))
            val result = ModelCommand(config, mockk()).handle(commandMsg("model", args = "gpt-4"), session())
            assertTrue(result.contains("Unknown model"))
            assertTrue(result.contains("glm-5"))
        }

    @Test
    fun `trims whitespace from model arg`() =
        runTest {
            val sessionMgr = mockk<SessionManager>(relaxed = true)
            val config = testEngineConfig(models = mapOf("glm-5" to ModelConfig()))
            val result = ModelCommand(config, sessionMgr).handle(commandMsg("model", args = "  glm-5  "), session())
            assertEquals("Switched to model: glm-5", result)
        }
}
