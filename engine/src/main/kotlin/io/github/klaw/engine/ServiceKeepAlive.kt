package io.github.klaw.engine

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.ApplicationConfiguration
import io.micronaut.runtime.EmbeddedApplication
import jakarta.inject.Singleton

@Singleton
class ServiceKeepAlive(
    private val applicationContext: ApplicationContext,
    private val applicationConfiguration: ApplicationConfiguration,
) : EmbeddedApplication<ServiceKeepAlive> {
    @Volatile
    private var running = false

    override fun getApplicationContext(): ApplicationContext = applicationContext

    override fun getApplicationConfiguration(): ApplicationConfiguration = applicationConfiguration

    override fun isRunning(): Boolean = running

    override fun isServer(): Boolean = true

    override fun start(): ServiceKeepAlive {
        running = true
        return this
    }

    override fun stop(): ServiceKeepAlive {
        running = false
        return this
    }
}
