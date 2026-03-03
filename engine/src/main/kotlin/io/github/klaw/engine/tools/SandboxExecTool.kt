package io.github.klaw.engine.tools

import io.github.klaw.common.config.CodeExecutionConfig
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class SandboxExecTool(
    private val sandboxManager: SandboxManager,
    private val config: CodeExecutionConfig,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(
        language: String,
        code: String,
        timeout: Int = config.timeout,
    ): String {
        logger.trace { "sandbox_exec: language=$language, timeout=$timeout" }
        return try {
            val output = sandboxManager.execute(language, code, timeout)
            output.formatForLlm()
        } catch (e: IllegalArgumentException) {
            "Error: ${e.message}"
        } catch (e: SandboxExecutionException) {
            logger.warn(e) { "sandbox_exec: SandboxExecutionException" }
            "Error: ${e.message}"
        } catch (e: Exception) {
            logger.warn(e) { "sandbox_exec failed unexpectedly" }
            "Error: sandbox execution failed"
        }
    }
}
