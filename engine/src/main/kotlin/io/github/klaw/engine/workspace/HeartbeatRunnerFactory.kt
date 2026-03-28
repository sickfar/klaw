package io.github.klaw.engine.workspace

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.engine.context.ToolRegistry
import io.github.klaw.engine.context.WorkspaceLoader
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.session.SessionManager
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.scheduling.TaskScheduler
import jakarta.annotation.PostConstruct
import jakarta.inject.Singleton
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

@Singleton
class HeartbeatRunnerFactory(
    private val config: EngineConfig,
    private val llmRouter: LlmRouter,
    private val toolExecutor: io.github.klaw.engine.tools.ToolExecutor,
    private val sessionManager: SessionManager,
    private val workspaceLoader: WorkspaceLoader,
    private val toolRegistry: ToolRegistry,
    private val taskScheduler: TaskScheduler,
    private val persistenceProvider: HeartbeatPersistenceProvider,
) {
    var runner: HeartbeatRunner? = null
        private set

    @PostConstruct
    fun start() {
        val interval =
            HeartbeatRunner.parseInterval(config.heartbeat.interval) ?: run {
                logger.info { "Heartbeat disabled (interval=${config.heartbeat.interval})" }
                return
            }

        val channel = config.heartbeat.channel
        val injectInto = config.heartbeat.injectInto
        val model = config.heartbeat.model ?: config.routing.default
        logger.info {
            "Heartbeat starting: interval=$interval, model=$model, " +
                "channel=${channel ?: "<not set>"}, injectInto=${injectInto ?: "<not set>"}"
        }

        val r =
            HeartbeatRunner(
                config = config,
                chat = llmRouter::chat,
                toolExecutor = toolExecutor,
                getOrCreateSession = sessionManager::getOrCreate,
                workspaceLoader = workspaceLoader,
                toolRegistry = toolRegistry,
                workspacePath = Path.of(KlawPaths.workspace),
                maxToolCallRounds = config.processing.maxToolCallRounds,
                persistence = persistenceProvider.create(),
            )
        runner = r

        taskScheduler.scheduleWithFixedDelay(interval, interval) { r.runHeartbeat() }
        logger.info { "Heartbeat scheduled — first run in $interval" }
    }
}
