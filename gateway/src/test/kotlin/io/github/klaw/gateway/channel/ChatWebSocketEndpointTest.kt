package io.github.klaw.gateway.channel

import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.WebSocketChannelConfig
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ChatWebSocketEndpointTest {
    @Test
    fun `valid user frame is forwarded to LocalWsChannel`() {
        val localWsChannel = mockk<LocalWsChannel>(relaxed = true)
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(websocket = mapOf("default" to WebSocketChannelConfig(agentId = "default"))),
            )
        val endpoint = ChatWebSocketEndpoint(localWsChannel, UploadStore(config))
        val session = mockk<io.micronaut.websocket.WebSocketSession>(relaxed = true)

        runBlocking {
            endpoint.onOpen("default", session)
            endpoint.onMessage("default", """{"type":"user","content":"hello"}""", session)
        }

        coVerify(exactly = 1) { localWsChannel.handleIncoming(any(), "hello", any()) }
    }

    @Test
    fun `frame with type not equal to user is ignored`() {
        val localWsChannel = mockk<LocalWsChannel>(relaxed = true)
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(websocket = mapOf("default" to WebSocketChannelConfig(agentId = "default"))),
            )
        val endpoint = ChatWebSocketEndpoint(localWsChannel, UploadStore(config))
        val session = mockk<io.micronaut.websocket.WebSocketSession>(relaxed = true)

        runBlocking {
            endpoint.onOpen("default", session)
            endpoint.onMessage("default", """{"type":"assistant","content":"should be ignored"}""", session)
        }

        coVerify(exactly = 0) { localWsChannel.handleIncoming(any(), any(), any()) }
    }

    @Test
    fun `malformed JSON is silently ignored`() {
        val localWsChannel = mockk<LocalWsChannel>(relaxed = true)
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(websocket = mapOf("default" to WebSocketChannelConfig(agentId = "default"))),
            )
        val endpoint = ChatWebSocketEndpoint(localWsChannel, UploadStore(config))
        val session = mockk<io.micronaut.websocket.WebSocketSession>(relaxed = true)

        runBlocking {
            endpoint.onOpen("default", session)
            endpoint.onMessage("default", "not-valid-json{{{", session)
        }

        coVerify(exactly = 0) { localWsChannel.handleIncoming(any(), any(), any()) }
    }

    @Test
    fun `empty content with type=user is forwarded`() {
        val localWsChannel = mockk<LocalWsChannel>(relaxed = true)
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(websocket = mapOf("default" to WebSocketChannelConfig(agentId = "default"))),
            )
        val endpoint = ChatWebSocketEndpoint(localWsChannel, UploadStore(config))
        val session = mockk<io.micronaut.websocket.WebSocketSession>(relaxed = true)

        runBlocking {
            endpoint.onOpen("default", session)
            endpoint.onMessage("default", """{"type":"user","content":""}""", session)
        }

        coVerify(exactly = 1) { localWsChannel.handleIncoming(any(), "", any()) }
    }
}
