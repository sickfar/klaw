package io.github.klaw.gateway

import io.micronaut.runtime.Micronaut
import org.slf4j.bridge.SLF4JBridgeHandler

object Application {
    @JvmStatic
    @Suppress("SpreadOperator")
    fun main(args: Array<String>) {
        SLF4JBridgeHandler.removeHandlersForRootLogger()
        SLF4JBridgeHandler.install()
        Micronaut.run(*args)
    }
}
