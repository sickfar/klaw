package io.github.klaw.engine.agent

import io.github.klaw.common.config.EngineConfig
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Iterates [EngineConfig.effectiveAgents], creates [AgentContext] for each enabled agent,
 * and registers them in [AgentRegistry]. Disabled agents are skipped.
 */
fun initializeAgents(
    config: EngineConfig,
    factory: AgentContextFactory,
    registry: AgentRegistry,
    stateDir: String,
    dataDir: String,
    configDir: String,
    conversationsDir: String,
) {
    val agents = config.effectiveAgents
    logger.info { "Initializing ${agents.size} agent(s)" }

    for ((agentId, agentConfig) in agents) {
        if (!agentConfig.enabled) {
            logger.info { "Agent '$agentId' is disabled — skipping" }
            continue
        }

        val ctx = factory.create(
            agentId = agentId,
            agentConfig = agentConfig,
            stateDir = stateDir,
            dataDir = dataDir,
            configDir = configDir,
            conversationsDir = conversationsDir,
        )
        registry.register(agentId, ctx)
    }

    logger.info { "Agent initialization complete: ${registry.all().size} agent(s) registered" }
}
