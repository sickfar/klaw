package io.github.klaw.engine

import io.micronaut.runtime.Micronaut

object Application {
    @JvmStatic
    @Suppress("SpreadOperator")
    fun main(args: Array<String>) {
        Micronaut.run(Application::class.java, *args)
    }
}
