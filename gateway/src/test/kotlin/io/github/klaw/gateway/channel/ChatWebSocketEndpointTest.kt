package io.github.klaw.gateway.channel

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test
import io.ktor.server.websocket.WebSockets as ServerWebSockets

class ChatWebSocketEndpointTest {
    @Test
    fun `valid user frame is forwarded to LocalWsChannel`() {
        val localWsChannel = mockk<LocalWsChannel>(relaxed = true)
        val endpoint = ChatWebSocketEndpoint(localWsChannel, UploadStore())

        testApplication {
            install(ServerWebSockets)
            routing { endpoint.install(this) }

            val client = createClient { install(WebSockets) }
            client.webSocket("/chat") {
                send(Frame.Text("""{"type":"user","content":"hello"}"""))
            }
        }

        coVerify(exactly = 1) { localWsChannel.handleIncoming("hello", any()) }
    }

    @Test
    fun `frame with type not equal to user is ignored`() {
        val localWsChannel = mockk<LocalWsChannel>(relaxed = true)
        val endpoint = ChatWebSocketEndpoint(localWsChannel, UploadStore())

        testApplication {
            install(ServerWebSockets)
            routing { endpoint.install(this) }

            val client = createClient { install(WebSockets) }
            client.webSocket("/chat") {
                send(Frame.Text("""{"type":"assistant","content":"should be ignored"}"""))
            }
        }

        coVerify(exactly = 0) { localWsChannel.handleIncoming(any(), any()) }
    }

    @Test
    fun `malformed JSON is silently ignored`() {
        val localWsChannel = mockk<LocalWsChannel>(relaxed = true)
        val endpoint = ChatWebSocketEndpoint(localWsChannel, UploadStore())

        testApplication {
            install(ServerWebSockets)
            routing { endpoint.install(this) }

            val client = createClient { install(WebSockets) }
            client.webSocket("/chat") {
                send(Frame.Text("not-valid-json{{{"))
            }
        }

        coVerify(exactly = 0) { localWsChannel.handleIncoming(any(), any()) }
    }

    @Test
    fun `empty content with type=user is forwarded`() {
        val localWsChannel = mockk<LocalWsChannel>(relaxed = true)
        val endpoint = ChatWebSocketEndpoint(localWsChannel, UploadStore())

        testApplication {
            install(ServerWebSockets)
            routing { endpoint.install(this) }

            val client = createClient { install(WebSockets) }
            client.webSocket("/chat") {
                send(Frame.Text("""{"type":"user","content":""}"""))
            }
        }

        coVerify(exactly = 1) { localWsChannel.handleIncoming("", any()) }
    }
}
