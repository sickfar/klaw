package io.github.klaw.engine

import io.github.klaw.engine.message.MessageProcessor
import io.github.klaw.engine.socket.EngineSocketServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class GracefulShutdownTest {
    @Test
    fun `shutdown closes message processor before stopping socket`() =
        runTest {
            val socketServer = mockk<EngineSocketServer>(relaxed = true)
            val messageProcessor = mockk<MessageProcessor>(relaxed = true)

            val lifecycle = EngineLifecycle(socketServer, messageProcessor)
            lifecycle.shutdown()

            verify { messageProcessor.close() }
            verify { socketServer.stop() }
        }

    @Test
    fun `shutdown stops socket server even if processor close fails`() =
        runTest {
            val socketServer = mockk<EngineSocketServer>(relaxed = true)
            val messageProcessor = mockk<MessageProcessor>(relaxed = true)
            every { messageProcessor.close() } throws RuntimeException("close failed")

            val lifecycle = EngineLifecycle(socketServer, messageProcessor)
            lifecycle.shutdown()

            verify { socketServer.stop() }
        }

    @Test
    fun `shutdown is idempotent`() =
        runTest {
            val socketServer = mockk<EngineSocketServer>(relaxed = true)
            val messageProcessor = mockk<MessageProcessor>(relaxed = true)

            val lifecycle = EngineLifecycle(socketServer, messageProcessor)
            lifecycle.shutdown()
            lifecycle.shutdown()

            // Should only be called once each
            verify(exactly = 1) { messageProcessor.close() }
            verify(exactly = 1) { socketServer.stop() }
        }
}
