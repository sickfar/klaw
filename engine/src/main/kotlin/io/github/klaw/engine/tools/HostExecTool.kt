package io.github.klaw.engine.tools

import io.github.klaw.common.config.HostExecutionConfig
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

private val RISK_NUMBER_REGEX = Regex("""\b(\d{1,2})\b""")
private const val MAX_RISK_SCORE = 10

@Suppress("MaxLineLength")
private const val RISK_ASSESSMENT_PROMPT =
    """You are a security risk assessor. Your ONLY job is to output a single integer from 0 to 10.

Rules:
- 0 = completely safe, read-only (examples: cat, ls, head, grep, wc, df, free, uptime, whoami, date, echo, pwd)
- 1-3 = low risk, reads data or harmless operations
- 4-5 = moderate risk, writes to temporary or non-critical files
- 6-7 = high risk, modifies system state (service restart, package install, config change)
- 8-9 = very high risk, destructive or broad-scope changes (rm -r, chmod -R, kill, format)
- 10 = catastrophic, irreversible data loss (rm -rf /, mkfs, dd if=/dev/zero)

IMPORTANT: Respond with ONLY a single integer. No words, no explanation. Just the number.

Command: %s

Your answer (single integer 0-10):"""

@Suppress("MaxLineLength")
private const val RISK_RETRY_PROMPT =
    """You did not follow instructions. Respond with ONLY a single integer from 0 to 10. Nothing else. No words, no explanation.

Command: %s

Your answer (single integer 0-10):"""

internal fun extractRiskScore(text: String?): Int? {
    if (text.isNullOrBlank()) return null
    val trimmed = text.trim()
    // Try direct parse first, then extract first number in range 0-10
    val directParse = trimmed.toIntOrNull()?.takeIf { it in 0..MAX_RISK_SCORE }
    return directParse
        ?: RISK_NUMBER_REGEX
            .findAll(trimmed)
            .map { it.groupValues[1].toInt() }
            .firstOrNull { it in 0..MAX_RISK_SCORE }
}

class HostExecTool(
    private val config: HostExecutionConfig,
    private val llmRouter: LlmRouter,
    private val approvalService: ApprovalService,
) {
    @Suppress("ReturnCount")
    suspend fun execute(
        command: String,
        chatId: String,
        channel: String = "unknown",
    ): String {
        if (!config.enabled) {
            return "Error: host_exec is disabled"
        }
        logger.trace { "host_exec: chatId=$chatId" }

        // 1. allowList check
        if (matchesGlob(command, config.allowList)) {
            logger.debug { "host_exec: command matched allowList" }
            return runCommand(command)
        }

        // 2. notifyList check
        if (matchesGlob(command, config.notifyList)) {
            logger.debug { "host_exec: command matched notifyList" }
            approvalService.notify(chatId, channel, command)
            return runCommand(command)
        }

        // 3. LLM pre-validation
        if (config.preValidation.enabled && config.preValidation.model.isNotEmpty()) {
            val risk = evaluateRisk(command)
            if (risk < config.preValidation.riskThreshold) {
                logger.debug { "host_exec: LLM risk=$risk below threshold=${config.preValidation.riskThreshold}" }
                return runCommand(command)
            }
            logger.debug { "host_exec: LLM risk=$risk at or above threshold, asking user" }
        }

        // 4. Ask user
        return requestApprovalAndExecute(chatId, command)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun evaluateRisk(command: String): Int =
        try {
            withTimeout(config.preValidation.timeoutMs) {
                val score = callLlmForRisk(RISK_ASSESSMENT_PROMPT.format(command))
                if (score != null) {
                    return@withTimeout score
                }
                // Retry once with a stricter prompt
                logger.debug { "host_exec: LLM response unparseable, retrying" }
                val retryScore = callLlmForRisk(RISK_RETRY_PROMPT.format(command))
                retryScore ?: config.preValidation.riskThreshold
            }
        } catch (e: Exception) {
            logger.debug(e) { "host_exec: LLM pre-validation failed, falling back to ask" }
            config.preValidation.riskThreshold
        }

    private suspend fun callLlmForRisk(prompt: String): Int? {
        val request =
            LlmRequest(
                messages = listOf(LlmMessage(role = "user", content = prompt)),
            )
        val response = llmRouter.chat(request, config.preValidation.model)
        return extractRiskScore(response.content)
    }

    private suspend fun requestApprovalAndExecute(
        chatId: String,
        command: String,
    ): String {
        val approved =
            approvalService.requestApproval(
                chatId = chatId,
                command = command,
                riskScore = -1,
                timeoutMin = config.askTimeoutMin,
            )
        if (!approved) {
            logger.debug { "host_exec: command rejected or timed out" }
            return "Error: command rejected by user or timed out"
        }
        return runCommand(command)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runCommand(command: String): String =
        withContext(Dispatchers.VT) {
            try {
                logger.trace { "host_exec: executing command" }
                val process =
                    ProcessBuilder("sh", "-c", command)
                        .redirectErrorStream(false)
                        .start()
                // Drain streams concurrently to avoid pipe buffer deadlock
                var stdout = ""
                var stderr = ""
                val stdoutThread = Thread { stdout = process.inputStream.bufferedReader().readText() }
                val stderrThread = Thread { stderr = process.errorStream.bufferedReader().readText() }
                stdoutThread.start()
                stderrThread.start()
                val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    stdoutThread.join(STREAM_JOIN_TIMEOUT_MS)
                    stderrThread.join(STREAM_JOIN_TIMEOUT_MS)
                    return@withContext "Error: command timed out after ${COMMAND_TIMEOUT_SECONDS}s"
                }
                stdoutThread.join(STREAM_JOIN_TIMEOUT_MS)
                stderrThread.join(STREAM_JOIN_TIMEOUT_MS)
                formatOutput(stdout, stderr, process.exitValue())
            } catch (e: Exception) {
                logger.warn(e) { "host_exec: execution failed" }
                "Error: command execution failed"
            }
        }

    private fun formatOutput(
        stdout: String,
        stderr: String,
        exitCode: Int,
    ): String {
        val parts = mutableListOf<String>()
        if (stdout.isNotEmpty()) parts.add(stdout.trimEnd())
        if (stderr.isNotEmpty()) parts.add("stderr:\n${stderr.trimEnd()}")
        if (exitCode != 0) parts.add("exit code: $exitCode")
        return if (parts.isEmpty()) "(no output)" else parts.joinToString("\n")
    }

    companion object {
        private const val COMMAND_TIMEOUT_SECONDS = 60L
        private const val STREAM_JOIN_TIMEOUT_MS = 5000L
    }
}
