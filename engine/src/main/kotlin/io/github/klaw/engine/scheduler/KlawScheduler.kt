package io.github.klaw.engine.scheduler

interface KlawScheduler {
    suspend fun list(): String

    suspend fun listJson(): String = "[]"

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

    suspend fun edit(
        name: String,
        cron: String?,
        message: String?,
        model: String?,
    ): String = """{"error":"not implemented"}"""

    suspend fun enable(name: String): String = """{"error":"not implemented"}"""

    suspend fun disable(name: String): String = """{"error":"not implemented"}"""

    suspend fun run(name: String): String = """{"error":"not implemented"}"""

    suspend fun status(): String = """{"error":"not implemented"}"""

    suspend fun runs(
        name: String,
        limit: Int = 20,
    ): String = "[]"

    suspend fun jobCount(): Int = 0

    /** Start the scheduler. Called explicitly from EngineLifecycle. */
    fun start() {}

    /** Synchronous shutdown — wait for running jobs to complete. Called from EngineLifecycle. */
    fun shutdownBlocking() {}
}
