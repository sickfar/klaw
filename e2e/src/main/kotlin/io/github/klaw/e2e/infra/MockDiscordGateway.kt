package io.github.klaw.e2e.infra

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

private const val OP_DISPATCH = 0
private const val OP_HEARTBEAT = 1
private const val OP_IDENTIFY = 2
private const val OP_HELLO = 10
private const val OP_HEARTBEAT_ACK = 11
private const val DEFAULT_HEARTBEAT_INTERVAL = 41250

/**
 * Holds author information for a Discord message.
 */
data class DiscordAuthor(
    val userId: String,
    val username: String,
)

/**
 * Holds thread context for a Discord thread message.
 */
data class DiscordThread(
    val threadId: String,
    val parentChannelId: String,
)

@Suppress("TooManyFunctions")
class MockDiscordGateway {
    private var server: EmbeddedServer<*, *>? = null
    private var resolvedPort: Int = 0
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()
    private val heartbeatCounter = AtomicInteger(0)
    private val sequenceCounter = AtomicLong(0)
    private val snowflakeCounter = AtomicLong(System.currentTimeMillis() shl SNOWFLAKE_SHIFT)
    private val json = Json { ignoreUnknownKeys = true }

    val actualPort: Int get() = resolvedPort
    val wsUrl: String get() = "ws://host.testcontainers.internal:$resolvedPort"
    val heartbeatCount: Int get() = heartbeatCounter.get()
    val connectedSessionCount: Int get() = sessions.size

    fun start(port: Int = 0) {
        val effectivePort = if (port == 0) findFreePort() else port
        resolvedPort = effectivePort
        server =
            embeddedServer(CIO, port = effectivePort) {
                install(WebSockets)
                routing {
                    webSocket("/") {
                        handleSession(this)
                    }
                    webSocket("/{path...}") {
                        logger.debug { "Discord gateway WebSocket connected on non-root path" }
                        handleSession(this)
                    }
                }
            }.also { engine ->
                engine.start(wait = false)
                logger.debug { "MockDiscordGateway started on port $resolvedPort" }
            }
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    fun stop() {
        server?.stop()
        sessions.clear()
        logger.debug { "MockDiscordGateway stopped" }
    }

    fun injectMessage(
        guildId: String,
        channelId: String,
        author: DiscordAuthor,
        content: String,
        messageId: String = generateSnowflake(),
    ) {
        val payload = buildMessageCreateJson(messageId, channelId, guildId, author, content)
        broadcastDispatch("MESSAGE_CREATE", payload)
    }

    fun injectThreadMessage(
        thread: DiscordThread,
        guildId: String,
        author: DiscordAuthor,
        content: String,
    ) {
        val payload = buildThreadMessageCreateJson(generateSnowflake(), thread, guildId, author, content)
        broadcastDispatch("MESSAGE_CREATE", payload)
    }

    fun injectDm(
        dmChannelId: String,
        author: DiscordAuthor,
        content: String,
    ) {
        val payload = buildDmMessageCreateJson(generateSnowflake(), dmChannelId, author, content)
        broadcastDispatch("MESSAGE_CREATE", payload)
    }

    private suspend fun handleSession(session: WebSocketSession) {
        val sessionId = "session-${System.nanoTime()}"
        sessions[sessionId] = session
        logger.debug { "Discord gateway session connected: $sessionId" }

        try {
            sendHello(session)
            handleIncoming(session, sessionId)
        } catch (_: ClosedReceiveChannelException) {
            logger.debug { "Discord gateway session closed: $sessionId" }
        } finally {
            sessions.remove(sessionId)
        }
    }

    private suspend fun sendHello(session: WebSocketSession) {
        val hello = """{"op":$OP_HELLO,"d":{"heartbeat_interval":$DEFAULT_HEARTBEAT_INTERVAL}}"""
        session.send(hello)
        logger.trace { "Sent HELLO to session" }
    }

    private suspend fun handleIncoming(
        session: WebSocketSession,
        sessionId: String,
    ) {
        for (frame in session.incoming) {
            if (frame is Frame.Text) {
                val text = frame.readText()
                val msg = json.parseToJsonElement(text).jsonObject
                val op = msg["op"]?.jsonPrimitive?.int ?: continue

                when (op) {
                    OP_IDENTIFY -> handleIdentify(session, sessionId)
                    OP_HEARTBEAT -> handleHeartbeat(session)
                    else -> logger.trace { "Received unknown opcode $op" }
                }
            }
        }
    }

    private suspend fun handleIdentify(
        session: WebSocketSession,
        sessionId: String,
    ) {
        logger.debug { "Received IDENTIFY from $sessionId" }
        val seq = nextSequence()
        val ready = buildReadyJson(seq, sessionId)
        session.send(ready)
        logger.trace { "Sent READY to $sessionId" }
    }

    private suspend fun handleHeartbeat(session: WebSocketSession) {
        heartbeatCounter.incrementAndGet()
        session.send("""{"op":$OP_HEARTBEAT_ACK}""")
        logger.trace { "Sent HEARTBEAT_ACK" }
    }

    // Uses runBlocking intentionally: inject* callers are synchronous test methods that need
    // delivery to complete before assertions run. This is test infrastructure only.
    private fun broadcastDispatch(
        eventName: String,
        dataJson: String,
    ) {
        val seq = nextSequence()
        val frame = """{"op":$OP_DISPATCH,"s":$seq,"t":"$eventName","d":$dataJson}"""
        runBlocking {
            sessions.values.forEach { session ->
                try {
                    session.send(frame)
                    logger.trace { "Dispatched $eventName to session" }
                } catch (_: ClosedSendChannelException) {
                    logger.debug { "Session closed during dispatch of $eventName" }
                } catch (_: IOException) {
                    logger.debug { "IO error during dispatch of $eventName" }
                }
            }
        }
    }

    private fun nextSequence(): Long = sequenceCounter.incrementAndGet()

    fun generateSnowflake(): String = snowflakeCounter.incrementAndGet().toString()

    companion object {
        private const val SNOWFLAKE_SHIFT = 22

        internal fun buildReadyJson(
            sequence: Long,
            sessionId: String,
        ): String =
            """{"op":$OP_DISPATCH,"s":$sequence,"t":"READY","d":{""" +
                """"v":10,""" +
                """"user":{"id":"$BOT_USER_ID","username":"TestBot","discriminator":"0000","bot":true},""" +
                """"private_channels":[],""" +
                """"guilds":[{"id":"$TEST_GUILD_ID","unavailable":true}],""" +
                """"session_id":"$sessionId",""" +
                """"resume_gateway_url":"ws://localhost"}}"""

        private const val TIMESTAMP = "2026-03-19T12:00:00.000000+00:00"

        private fun authorJson(author: DiscordAuthor): String =
            """{"id":"${author.userId}","username":"${author.username}",""" +
                """"discriminator":"0000","bot":false}"""

        internal fun buildMessageCreateJson(
            messageId: String,
            channelId: String,
            guildId: String,
            author: DiscordAuthor,
            content: String,
        ): String {
            val escaped = escapeJson(content)
            val authorBlock = authorJson(author)
            return """{"id":"$messageId","channel_id":"$channelId",""" +
                """"guild_id":"$guildId","author":$authorBlock,""" +
                """"content":"$escaped","timestamp":"$TIMESTAMP","type":0}"""
        }

        internal fun buildThreadMessageCreateJson(
            messageId: String,
            thread: DiscordThread,
            guildId: String,
            author: DiscordAuthor,
            content: String,
        ): String {
            val escaped = escapeJson(content)
            val authorBlock = authorJson(author)
            return """{"id":"$messageId","channel_id":"${thread.threadId}",""" +
                """"guild_id":"$guildId","author":$authorBlock,""" +
                """"content":"$escaped","timestamp":"$TIMESTAMP","type":0,""" +
                """"thread":{"id":"${thread.threadId}",""" +
                """"parent_id":"${thread.parentChannelId}","type":11}}"""
        }

        internal fun buildDmMessageCreateJson(
            messageId: String,
            dmChannelId: String,
            author: DiscordAuthor,
            content: String,
        ): String {
            val escaped = escapeJson(content)
            val authorBlock = authorJson(author)
            return """{"id":"$messageId","channel_id":"$dmChannelId",""" +
                """"author":$authorBlock,""" +
                """"content":"$escaped","timestamp":"$TIMESTAMP","type":0}"""
        }

        private fun escapeJson(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

        const val BOT_USER_ID = "999888777666555"
        const val TEST_GUILD_ID = "111222333444555"
    }
}
