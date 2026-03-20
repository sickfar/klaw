package io.github.klaw.gateway.channel

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test
import io.ktor.server.websocket.WebSockets as ServerWebSockets

class ChatWebSocketEndpointApprovalTest {
    @Test
    fun `approval_response frame dispatches to localWsChannel resolveApproval`() {
        val localWsChannel = mockk<LocalWsChannel>(relaxed = true)
        val endpoint = ChatWebSocketEndpoint(localWsChannel, UploadStore())

        testApplication {
            install(ServerWebSockets)
            routing { endpoint.install(this) }

            val client = createClient { install(WebSockets) }
            client.webSocket("/chat") {
                send(
                    Frame.Text(
                        """{"type":"approval_response","approvalId":"apr-99","approved":true}""",
                    ),
                )
            }
        }

        coVerify(exactly = 1) { localWsChannel.resolveApproval("apr-99", true) }
    }

    @Test
    fun `approval_response with approved=false dispatches correctly`() {
        val localWsChannel = mockk<LocalWsChannel>(relaxed = true)
        val endpoint = ChatWebSocketEndpoint(localWsChannel, UploadStore())

        testApplication {
            install(ServerWebSockets)
            routing { endpoint.install(this) }

            val client = createClient { install(WebSockets) }
            client.webSocket("/chat") {
                send(
                    Frame.Text(
                        """{"type":"approval_response","approvalId":"apr-100","approved":false}""",
                    ),
                )
            }
        }

        coVerify(exactly = 1) { localWsChannel.resolveApproval("apr-100", false) }
    }

    @Test
    fun `approval_response missing approvalId does not call resolveApproval`() {
        val localWsChannel = mockk<LocalWsChannel>(relaxed = true)
        val endpoint = ChatWebSocketEndpoint(localWsChannel, UploadStore())

        testApplication {
            install(ServerWebSockets)
            routing { endpoint.install(this) }

            val client = createClient { install(WebSockets) }
            client.webSocket("/chat") {
                send(
                    Frame.Text(
                        """{"type":"approval_response","approved":true}""",
                    ),
                )
            }
        }

        coVerify(exactly = 0) { localWsChannel.resolveApproval(any(), any()) }
    }

    @Test
    fun `approval_response missing approved does not call resolveApproval`() {
        val localWsChannel = mockk<LocalWsChannel>(relaxed = true)
        val endpoint = ChatWebSocketEndpoint(localWsChannel, UploadStore())

        testApplication {
            install(ServerWebSockets)
            routing { endpoint.install(this) }

            val client = createClient { install(WebSockets) }
            client.webSocket("/chat") {
                send(
                    Frame.Text(
                        """{"type":"approval_response","approvalId":"apr-101"}""",
                    ),
                )
            }
        }

        coVerify(exactly = 0) { localWsChannel.resolveApproval(any(), any()) }
    }
}
