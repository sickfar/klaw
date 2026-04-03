package io.github.klaw.engine.agent

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Singleton registry holding all active [AgentContext] instances.
 * Thread-safe for concurrent register/get operations.
 */
@Singleton
class AgentRegistry {
    private val agents = ConcurrentHashMap<String, AgentContext>()

    fun register(
        agentId: String,
        context: AgentContext,
    ) {
        agents[agentId] = context
        logger.info { "Agent registered: $agentId" }
    }

    fun get(agentId: String): AgentContext =
        agents[agentId]
            ?: throw IllegalArgumentException("Unknown agent: '$agentId'")

    fun getOrNull(agentId: String): AgentContext? = agents[agentId]

    fun all(): Collection<AgentContext> = agents.values.toList()

    fun shutdown() {
        logger.info { "Shutting down ${agents.size} agents" }
        agents.values.forEach { ctx ->
            runCatching { ctx.shutdown() }
                .onFailure { logger.warn(it) { "Agent shutdown failed: ${ctx.agentId}" } }
        }
        agents.clear()
        logger.info { "All agents shut down" }
    }
}
