package io.github.klaw.gateway.channel

import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.WebSocketChannelConfig
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ChatWebSocketEndpointApprovalTest {
    @Test
    fun `approval_response frame dispatches to localWsChannel resolveApproval`() {
        val localWsChannel = mockk<LocalWsChannel>(relaxed = true)
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        websocket = mapOf("default" to WebSocketChannelConfig(agentId = "default")),
                    ),
            )
        val endpoint = ChatWebSocketEndpoint(localWsChannel, UploadStore(config))
        val session = mockk<io.micronaut.websocket.WebSocketSession>(relaxed = true)

        runBlocking {
            endpoint.onOpen(session)
            endpoint.onMessage(
                """{"type":"approval_response","approvalId":"apr-99","approved":true}""",
                session,
            )
        }

        coVerify(exactly = 1) { localWsChannel.resolveApproval("apr-99", true) }
    }

    @Test
    fun `approval_response with approved=false dispatches correctly`() {
        val localWsChannel = mockk<LocalWsChannel>(relaxed = true)
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        websocket = mapOf("default" to WebSocketChannelConfig(agentId = "default")),
                    ),
            )
        val endpoint = ChatWebSocketEndpoint(localWsChannel, UploadStore(config))
        val session = mockk<io.micronaut.websocket.WebSocketSession>(relaxed = true)

        runBlocking {
            endpoint.onOpen(session)
            endpoint.onMessage(
                """{"type":"approval_response","approvalId":"apr-100","approved":false}""",
                session,
            )
        }

        coVerify(exactly = 1) { localWsChannel.resolveApproval("apr-100", false) }
    }

    @Test
    fun `approval_response missing approvalId does not call resolveApproval`() {
        val localWsChannel = mockk<LocalWsChannel>(relaxed = true)
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        websocket = mapOf("default" to WebSocketChannelConfig(agentId = "default")),
                    ),
            )
        val endpoint = ChatWebSocketEndpoint(localWsChannel, UploadStore(config))
        val session = mockk<io.micronaut.websocket.WebSocketSession>(relaxed = true)

        runBlocking {
            endpoint.onOpen(session)
            endpoint.onMessage(
                """{"type":"approval_response","approved":true}""",
                session,
            )
        }

        coVerify(exactly = 0) { localWsChannel.resolveApproval(any(), any()) }
    }

    @Test
    fun `approval_response missing approved does not call resolveApproval`() {
        val localWsChannel = mockk<LocalWsChannel>(relaxed = true)
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        websocket = mapOf("default" to WebSocketChannelConfig(agentId = "default")),
                    ),
            )
        val endpoint = ChatWebSocketEndpoint(localWsChannel, UploadStore(config))
        val session = mockk<io.micronaut.websocket.WebSocketSession>(relaxed = true)

        runBlocking {
            endpoint.onOpen(session)
            endpoint.onMessage(
                """{"type":"approval_response","approvalId":"apr-101"}""",
                session,
            )
        }

        coVerify(exactly = 0) { localWsChannel.resolveApproval(any(), any()) }
    }
}
