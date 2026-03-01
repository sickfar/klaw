package io.github.klaw.engine.tools

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.engine.llm.LlmRouter
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class HostExecToolFactory {
    @Singleton
    fun hostExecTool(
        config: EngineConfig,
        llmRouter: LlmRouter,
        approvalService: ApprovalService,
    ): HostExecTool = HostExecTool(config.hostExecution, llmRouter, approvalService)
}
