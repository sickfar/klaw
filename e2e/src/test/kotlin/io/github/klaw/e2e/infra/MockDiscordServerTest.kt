package io.github.klaw.e2e.infra

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

private const val WS_TIMEOUT_MS = 5000L
private const val OP_DISPATCH = 0
private const val OP_HEARTBEAT = 1
private const val OP_IDENTIFY = 2
private const val OP_HELLO = 10
private const val OP_HEARTBEAT_ACK = 11
private const val INJECT_DELAY_MS = 200L

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MockDiscordServerTest {
    private val discordServer = MockDiscordServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient =
        HttpClient {
            install(WebSockets)
        }

    @BeforeAll
    fun setup() {
        discordServer.start()
    }

    @AfterAll
    fun teardown() {
        httpClient.close()
        discordServer.stop()
    }

    @Test
    fun `gateway sends HELLO on connect`() =
        runBlocking {
            httpClient.webSocket("ws://localhost:${discordServer.gatewayPort}") {
                val frame = withTimeout(WS_TIMEOUT_MS) { incoming.receive() as Frame.Text }
                val msg = json.parseToJsonElement(frame.readText()).jsonObject
                assertEquals(OP_HELLO, msg["op"]?.jsonPrimitive?.int)
                assertNotNull(msg["d"]?.jsonObject?.get("heartbeat_interval"))
            }
        }

    @Test
    fun `gateway responds with READY after IDENTIFY`() =
        runBlocking {
            httpClient.webSocket("ws://localhost:${discordServer.gatewayPort}") {
                // Receive HELLO
                withTimeout(WS_TIMEOUT_MS) { incoming.receive() }

                // Send IDENTIFY
                send(
                    """{"op":$OP_IDENTIFY,"d":{"token":"test-token","intents":33281,"properties":{"os":"linux","browser":"test","device":"test"}}}""",
                )

                // Receive READY
                val frame = withTimeout(WS_TIMEOUT_MS) { incoming.receive() as Frame.Text }
                val msg = json.parseToJsonElement(frame.readText()).jsonObject
                assertEquals(OP_DISPATCH, msg["op"]?.jsonPrimitive?.int)
                assertEquals("READY", msg["t"]?.jsonPrimitive?.content)
                val data = msg["d"]!!.jsonObject
                val user = data["user"]!!.jsonObject
                assertEquals(MockDiscordServer.BOT_USER_ID, user["id"]?.jsonPrimitive?.content)
                assertTrue(user["bot"]?.jsonPrimitive?.boolean ?: false)
            }
        }

    @Test
    fun `gateway handles HEARTBEAT with ACK`() =
        runBlocking {
            httpClient.webSocket("ws://localhost:${discordServer.gatewayPort}") {
                // Receive HELLO
                withTimeout(WS_TIMEOUT_MS) { incoming.receive() }

                // Send HEARTBEAT
                send("""{"op":$OP_HEARTBEAT,"d":null}""")

                // Receive HEARTBEAT_ACK
                val frame = withTimeout(WS_TIMEOUT_MS) { incoming.receive() as Frame.Text }
                val msg = json.parseToJsonElement(frame.readText()).jsonObject
                assertEquals(OP_HEARTBEAT_ACK, msg["op"]?.jsonPrimitive?.int)
            }
        }

    @Test
    fun `injectMessage sends MESSAGE_CREATE with guild info`() =
        runBlocking {
            httpClient.webSocket("ws://localhost:${discordServer.gatewayPort}") {
                // Receive HELLO
                withTimeout(WS_TIMEOUT_MS) { incoming.receive() }

                // Send IDENTIFY to register session
                send("""{"op":$OP_IDENTIFY,"d":{"token":"t","intents":33281,"properties":{}}}""")
                // Receive READY
                withTimeout(WS_TIMEOUT_MS) { incoming.receive() }

                // Inject message from test thread
                kotlinx.coroutines.delay(INJECT_DELAY_MS)
                discordServer.injectMessage(
                    guildId = MockDiscordServer.TEST_GUILD_ID,
                    channelId = MockDiscordServer.TEST_CHANNEL_ID,
                    userId = MockDiscordServer.TEST_USER_ID,
                    username = MockDiscordServer.TEST_USERNAME,
                    content = "Hello from test!",
                )

                val frame = withTimeout(WS_TIMEOUT_MS) { incoming.receive() as Frame.Text }
                val msg = json.parseToJsonElement(frame.readText()).jsonObject
                assertEquals(OP_DISPATCH, msg["op"]?.jsonPrimitive?.int)
                assertEquals("MESSAGE_CREATE", msg["t"]?.jsonPrimitive?.content)
                val data = msg["d"]!!.jsonObject
                assertEquals(MockDiscordServer.TEST_GUILD_ID, data["guild_id"]?.jsonPrimitive?.content)
                assertEquals(MockDiscordServer.TEST_CHANNEL_ID, data["channel_id"]?.jsonPrimitive?.content)
                assertEquals("Hello from test!", data["content"]?.jsonPrimitive?.content)
                val author = data["author"]!!.jsonObject
                assertEquals(MockDiscordServer.TEST_USER_ID, author["id"]?.jsonPrimitive?.content)
                assertEquals(MockDiscordServer.TEST_USERNAME, author["username"]?.jsonPrimitive?.content)
                assertFalse(author["bot"]?.jsonPrimitive?.boolean ?: true)
            }
        }

    @Test
    fun `injectThreadMessage includes thread metadata`() =
        runBlocking {
            httpClient.webSocket("ws://localhost:${discordServer.gatewayPort}") {
                withTimeout(WS_TIMEOUT_MS) { incoming.receive() }
                send("""{"op":$OP_IDENTIFY,"d":{"token":"t","intents":33281,"properties":{}}}""")
                withTimeout(WS_TIMEOUT_MS) { incoming.receive() }

                kotlinx.coroutines.delay(INJECT_DELAY_MS)
                discordServer.injectThreadMessage(
                    threadId = MockDiscordServer.TEST_THREAD_ID,
                    parentId = MockDiscordServer.TEST_CHANNEL_ID,
                    guildId = MockDiscordServer.TEST_GUILD_ID,
                    author = DiscordAuthor(MockDiscordServer.TEST_USER_ID, MockDiscordServer.TEST_USERNAME),
                    content = "Thread msg",
                )

                val frame = withTimeout(WS_TIMEOUT_MS) { incoming.receive() as Frame.Text }
                val msg = json.parseToJsonElement(frame.readText()).jsonObject
                val data = msg["d"]!!.jsonObject
                assertEquals(MockDiscordServer.TEST_THREAD_ID, data["channel_id"]?.jsonPrimitive?.content)
                assertEquals(MockDiscordServer.TEST_GUILD_ID, data["guild_id"]?.jsonPrimitive?.content)
                val thread = data["thread"]!!.jsonObject
                assertEquals(MockDiscordServer.TEST_THREAD_ID, thread["id"]?.jsonPrimitive?.content)
                assertEquals(MockDiscordServer.TEST_CHANNEL_ID, thread["parent_id"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `injectDm sends MESSAGE_CREATE without guild_id`() =
        runBlocking {
            httpClient.webSocket("ws://localhost:${discordServer.gatewayPort}") {
                withTimeout(WS_TIMEOUT_MS) { incoming.receive() }
                send("""{"op":$OP_IDENTIFY,"d":{"token":"t","intents":33281,"properties":{}}}""")
                withTimeout(WS_TIMEOUT_MS) { incoming.receive() }

                kotlinx.coroutines.delay(INJECT_DELAY_MS)
                discordServer.injectDm(
                    dmChannelId = MockDiscordServer.TEST_DM_CHANNEL_ID,
                    userId = MockDiscordServer.TEST_USER_ID,
                    username = MockDiscordServer.TEST_USERNAME,
                    content = "DM content",
                )

                val frame = withTimeout(WS_TIMEOUT_MS) { incoming.receive() as Frame.Text }
                val msg = json.parseToJsonElement(frame.readText()).jsonObject
                val data = msg["d"]!!.jsonObject
                assertEquals(MockDiscordServer.TEST_DM_CHANNEL_ID, data["channel_id"]?.jsonPrimitive?.content)
                assertFalse(data.containsKey("guild_id"), "DM should not contain guild_id")
                assertEquals("DM content", data["content"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `REST stub responds to gateway bot endpoint`() =
        runBlocking {
            val response =
                httpClient.get("http://localhost:${discordServer.restPort}/api/v10/gateway/bot") {
                    header("Authorization", "Bot test-token")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body.containsKey("url"))
            assertTrue(body.containsKey("shards"))
        }

    @Test
    fun `REST stub responds to users me endpoint`() =
        runBlocking {
            val response =
                httpClient.get("http://localhost:${discordServer.restPort}/api/v10/users/@me") {
                    header("Authorization", "Bot test-token")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(MockDiscordServer.BOT_USER_ID, body["id"]?.jsonPrimitive?.content)
            assertTrue(body["bot"]?.jsonPrimitive?.boolean ?: false)
        }

    @Test
    fun `REST stub accepts channel messages and getSentMessages captures them`() =
        runBlocking {
            discordServer.reset()
            val response =
                httpClient.post("http://localhost:${discordServer.restPort}/api/v10/channels/123456/messages") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"test reply"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)

            val sent = discordServer.getSentMessages()
            assertEquals(1, sent.size)
            assertTrue(sent[0].contains("test reply"))
        }

    @Test
    fun `REST stub responds to typing indicator`() =
        runBlocking {
            val response = httpClient.post("http://localhost:${discordServer.restPort}/api/v10/channels/123456/typing")
            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `reset clears recorded requests`() =
        runBlocking {
            httpClient.post("http://localhost:${discordServer.restPort}/api/v10/channels/123456/messages") {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"before reset"}""")
            }
            assertEquals(1, discordServer.getSentMessageCount())

            discordServer.reset()
            assertEquals(0, discordServer.getSentMessageCount())
        }
}
