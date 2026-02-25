package io.github.klaw.gateway

import io.micronaut.context.ApplicationContext
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@MicronautTest
class ApplicationContextTest {
    @Inject
    lateinit var applicationContext: ApplicationContext

    @Test
    fun `application context starts`() {
        assertTrue(applicationContext.isRunning)
    }
}
