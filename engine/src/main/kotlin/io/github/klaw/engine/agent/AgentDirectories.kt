package io.github.klaw.engine.agent

/**
 * Bundles the four filesystem directory paths required to create per-agent contexts.
 */
data class AgentDirectories(
    val stateDir: String,
    val dataDir: String,
    val configDir: String,
    val conversationsDir: String,
)
