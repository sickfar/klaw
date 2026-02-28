package io.github.klaw.cli.chat

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.command.ChatCommand
import kotlin.test.Test
import kotlin.test.assertTrue

class ChatCommandTest {
    @Test
    fun `chat with console disabled shows error message`() {
        val command =
            ChatCommand(
                configDir = "/nonexistent",
                configReader = { _ -> ConsoleChatConfig(enabled = false) },
                sessionFactory = { _ -> error("Should not connect when disabled") },
            )
        val result = command.test("")
        assertTrue(
            result.output.contains("not enabled", ignoreCase = true) ||
                result.output.contains("WebSocket chat is not enabled"),
            "Expected disabled message in: ${result.output}",
        )
    }

    @Test
    fun `chat disabled shows how to enable`() {
        val command =
            ChatCommand(
                configDir = "/my/config",
                configReader = { _ -> ConsoleChatConfig(enabled = false) },
                sessionFactory = { _ -> error("Should not connect") },
            )
        val result = command.test("")
        assertTrue(result.output.contains("console"), "Expected 'console' in: ${result.output}")
        assertTrue(result.output.contains("gateway.json"), "Expected 'gateway.json' in: ${result.output}")
    }

    @Test
    fun `chat disabled shows config dir in error`() {
        val command =
            ChatCommand(
                configDir = "/my/config",
                configReader = { _ -> ConsoleChatConfig(enabled = false) },
                sessionFactory = { _ -> error("Should not connect") },
            )
        val result = command.test("")
        assertTrue(result.output.contains("/my/config"), "Expected configDir in: ${result.output}")
    }

    @Test
    fun `chat disabled shows restart instruction`() {
        val command =
            ChatCommand(
                configDir = "/nonexistent",
                configReader = { _ -> ConsoleChatConfig(enabled = false) },
                sessionFactory = { _ -> error("Should not connect") },
            )
        val result = command.test("")
        assertTrue(
            result.output.contains("restart") || result.output.contains("klaw init"),
            "Expected restart or init instruction in: ${result.output}",
        )
    }
}
