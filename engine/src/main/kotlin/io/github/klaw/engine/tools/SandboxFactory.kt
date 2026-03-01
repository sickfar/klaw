package io.github.klaw.engine.tools

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.paths.KlawPaths
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class SandboxFactory {
    @Singleton
    fun sandboxManager(
        config: EngineConfig,
        docker: DockerClient,
    ): SandboxManager = SandboxManager(config.codeExecution, docker, workspacePath = KlawPaths.workspace)

    @Singleton
    fun sandboxExecTool(
        sandboxManager: SandboxManager,
        config: EngineConfig,
    ): SandboxExecTool = SandboxExecTool(sandboxManager, config.codeExecution)
}
