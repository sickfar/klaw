package io.github.klaw.e2e.infra

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val HTTP_OK = 200
private const val HTTP_NO_CONTENT = 204

class MockDiscordServer {
    private val wireMock = WireMockServer(wireMockConfig().dynamicPort())
    private val gateway = MockDiscordGateway()

    val restPort: Int get() = wireMock.port()
    val restBaseUrl: String get() = "http://host.testcontainers.internal:${wireMock.port()}"
    val gatewayWsUrl: String get() = gateway.wsUrl
    val gatewayPort: Int get() = gateway.actualPort

    fun start() {
        wireMock.start()
        gateway.start(0)
        setupStubs()
        logger.debug { "MockDiscordServer started (REST port=${wireMock.port()}, WS port=${gateway.actualPort})" }
    }

    fun stop() {
        gateway.stop()
        wireMock.stop()
        logger.debug { "MockDiscordServer stopped" }
    }

    private fun setupStubs() {
        setupGatewayStubs()
        setupChannelAndGuildStubs()
        setupUserAndApplicationStubs()
        setupMessageStubs()
    }

    private fun setupGatewayStubs() {
        wireMock.stubFor(
            get(urlEqualTo("/api/v10/gateway/bot"))
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"url":"${gateway.wsUrl}","shards":1,""" +
                                """"session_start_limit":{"total":1000,"remaining":999,""" +
                                """"reset_after":14400000,"max_concurrency":1}}""",
                        ),
                ),
        )
    }

    private fun setupChannelAndGuildStubs() {
        wireMock.stubFor(
            get(urlMatching("/api/v10/channels/\\d+"))
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"$TEST_CHANNEL_ID","type":0,"guild_id":"$TEST_GUILD_ID",""" +
                                """"name":"test-channel","position":0}""",
                        ),
                ),
        )

        wireMock.stubFor(
            get(urlMatching("/api/v10/guilds/\\d+"))
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"$TEST_GUILD_ID","name":"Test Guild","owner_id":"$TEST_USER_ID",""" +
                                """"roles":[],"emojis":[],"features":[],"verification_level":0,""" +
                                """"default_message_notifications":0,"explicit_content_filter":0,""" +
                                """"mfa_level":0,"system_channel_flags":0,"premium_tier":0,"nsfw_level":0}""",
                        ),
                ),
        )
    }

    private fun setupUserAndApplicationStubs() {
        wireMock.stubFor(
            get(urlEqualTo("/api/v10/users/@me"))
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"$BOT_USER_ID","username":"TestBot","discriminator":"0000","bot":true}""",
                        ),
                ),
        )

        wireMock.stubFor(
            get(urlEqualTo("/api/v10/applications/@me"))
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"$BOT_USER_ID","name":"TestBot","bot_public":true,""" +
                                """"bot_require_code_grant":false,"flags":0}""",
                        ),
                ),
        )
    }

    private fun setupMessageStubs() {
        wireMock.stubFor(
            post(urlMatching("/api/v10/channels/\\d+/messages"))
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"id":"${gateway.generateSnowflake()}","content":"ok"}""")
                        .withStatus(HTTP_OK),
                ),
        )

        wireMock.stubFor(
            post(urlMatching("/api/v10/channels/\\d+/typing"))
                .willReturn(aResponse().withStatus(HTTP_NO_CONTENT)),
        )
    }

    fun injectMessage(
        guildId: String,
        channelId: String,
        userId: String,
        username: String,
        content: String,
    ) = gateway.injectMessage(guildId, channelId, DiscordAuthor(userId, username), content)

    fun injectThreadMessage(
        threadId: String,
        parentId: String,
        guildId: String,
        author: DiscordAuthor,
        content: String,
    ) = gateway.injectThreadMessage(DiscordThread(threadId, parentId), guildId, author, content)

    fun injectDm(
        dmChannelId: String,
        userId: String,
        username: String,
        content: String,
    ) = gateway.injectDm(dmChannelId, DiscordAuthor(userId, username), content)

    fun getSentMessages(): List<String> =
        wireMock
            .findAll(postRequestedFor(urlMatching("/api/v10/channels/\\d+/messages")))
            .map { it.bodyAsString }

    fun getSentMessageCount(): Int = getSentMessages().size

    val hasConnectedClient: Boolean get() = gateway.connectedSessionCount > 0

    fun reset() {
        wireMock.resetRequests()
    }

    companion object {
        const val BOT_USER_ID = "999888777666555"
        const val TEST_GUILD_ID = "111222333444555"
        const val TEST_CHANNEL_ID = "666777888999000"
        const val TEST_USER_ID = "123456789012345"
        const val TEST_USERNAME = "TestUser"
        const val TEST_THREAD_ID = "777888999000111"
        const val TEST_DM_CHANNEL_ID = "888999000111222"
    }
}
