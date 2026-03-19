package io.github.klaw.engine.tools

import io.github.klaw.common.config.CodeExecutionConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

class SandboxExecTool(
    private val sandboxManager: SandboxManager,
    private val config: CodeExecutionConfig,
) {
    suspend fun execute(
        code: String,
        timeout: Int = config.timeout,
    ): String {
        logger.trace { "sandbox_exec: timeout=$timeout" }
        return try {
            val output = sandboxManager.execute(code, timeout)
            output.formatForLlm()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SandboxExecutionException) {
            logger.warn { "sandbox_exec: ${e::class.simpleName}" }
            "Error: ${e.message}"
        } catch (e: IOException) {
            logger.warn { "sandbox_exec IO failure: ${e::class.simpleName}" }
            "Error: sandbox execution failed"
        } catch (e: InterruptedException) {
            logger.warn { "sandbox_exec interrupted: ${e::class.simpleName}" }
            "Error: sandbox execution interrupted"
        }
    }
}
