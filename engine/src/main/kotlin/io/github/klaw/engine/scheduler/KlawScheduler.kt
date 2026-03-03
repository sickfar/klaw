package io.github.klaw.engine.scheduler

interface KlawScheduler {
    suspend fun list(): String

    @Suppress("LongParameterList")
    suspend fun add(
        name: String,
        cron: String?,
        at: String?,
        message: String,
        model: String?,
        injectInto: String?,
        channel: String?,
    ): String

    suspend fun remove(name: String): String

    /** Start the scheduler. Called explicitly from EngineLifecycle. */
    fun start() {}

    /** Synchronous shutdown — wait for running jobs to complete. Called from EngineLifecycle. */
    fun shutdownBlocking() {}
}
