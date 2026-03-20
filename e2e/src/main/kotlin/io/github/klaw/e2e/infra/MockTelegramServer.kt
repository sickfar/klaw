package io.github.klaw.e2e.infra

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val HTTP_OK = 200
private const val SCENARIO_NAME = "telegram-updates"
private const val STATE_PHOTO_READY = "photo-ready"
private const val BOT_ID = 123456L
private const val PHOTO_MESSAGE_ID = 100
private const val PHOTO_SENDER_ID = 999
private const val PHOTO_DATE = 1700000000
private const val SMALL_PHOTO_WIDTH = 90
private const val SMALL_PHOTO_HEIGHT = 90
private const val SMALL_PHOTO_SIZE = 500
private const val LARGE_PHOTO_WIDTH = 800
private const val LARGE_PHOTO_HEIGHT = 600
private const val LARGE_PHOTO_SIZE = 10240
private const val FILE_SIZE = 1024

class MockTelegramServer(
    private val token: String = "test-bot-token",
) {
    private val wireMock = WireMockServer(wireMockConfig().dynamicPort())
    private var updateCounter = 0

    val port: Int get() = wireMock.port()
    val baseUrl: String get() = "http://host.testcontainers.internal:${wireMock.port()}"
    val localBaseUrl: String get() = "http://localhost:${wireMock.port()}"

    fun start() {
        wireMock.start()
        setupStubs()
        logger.debug { "MockTelegramServer started on port ${wireMock.port()}" }
    }

    fun stop() {
        wireMock.stop()
        logger.debug { "MockTelegramServer stopped" }
    }

    private fun setupStubs() {
        stubGetMe()
        stubGetUpdatesEmpty()
        stubGetFile()
        stubFileDownload()
        stubSendMessage()
        stubDeleteWebhook()
        stubSetMyCommands()
    }

    private fun stubGetMe() {
        wireMock.stubFor(
            post(urlEqualTo("/bot$token/getMe"))
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"ok":true,"result":{"id":$BOT_ID,"is_bot":true,""" +
                                """"first_name":"TestBot","username":"test_bot"}}""",
                        ),
                ),
        )
    }

    private fun stubGetUpdatesEmpty() {
        wireMock.stubFor(
            post(urlEqualTo("/bot$token/getUpdates"))
                .inScenario(SCENARIO_NAME)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"ok":true,"result":[]}"""),
                ),
        )
    }

    fun sendPhotoUpdate(
        chatId: Long,
        caption: String? = null,
    ) {
        updateCounter++
        val captionField =
            if (caption != null) {
                ""","caption":"$caption""""
            } else {
                ""
            }
        val updateJson =
            buildString {
                append("""{"ok":true,"result":[{"update_id":$updateCounter,"message":{""")
                append(""""message_id":$PHOTO_MESSAGE_ID,""")
                append(""""from":{"id":$PHOTO_SENDER_ID,"is_bot":false,"first_name":"TestUser"},""")
                append(""""chat":{"id":$chatId,"type":"private"},""")
                append(""""date":$PHOTO_DATE,""")
                append(""""photo":[""")
                append("""{"file_id":"small_id","file_unique_id":"u1",""")
                append(""""width":$SMALL_PHOTO_WIDTH,"height":$SMALL_PHOTO_HEIGHT,"file_size":$SMALL_PHOTO_SIZE},""")
                append("""{"file_id":"test_file_id","file_unique_id":"u2",""")
                append(""""width":$LARGE_PHOTO_WIDTH,"height":$LARGE_PHOTO_HEIGHT,"file_size":$LARGE_PHOTO_SIZE}""")
                append("""]""")
                append(captionField)
                append("""}}]}""")
            }

        // Set state to photo-ready with the photo update response
        wireMock.stubFor(
            post(urlEqualTo("/bot$token/getUpdates"))
                .inScenario(SCENARIO_NAME)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo(STATE_PHOTO_READY)
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody(updateJson),
                ),
        )

        // After photo is consumed, revert to empty updates
        wireMock.stubFor(
            post(urlEqualTo("/bot$token/getUpdates"))
                .inScenario(SCENARIO_NAME)
                .whenScenarioStateIs(STATE_PHOTO_READY)
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"ok":true,"result":[]}"""),
                ),
        )

        logger.debug { "Photo update queued for chatId=$chatId" }
    }

    private fun stubGetFile() {
        wireMock.stubFor(
            post(urlEqualTo("/bot$token/getFile"))
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"ok":true,"result":{"file_id":"test_file_id",""" +
                                """"file_unique_id":"unique1","file_size":$FILE_SIZE,""" +
                                """"file_path":"photos/test.jpg"}}""",
                        ),
                ),
        )
    }

    private fun stubFileDownload() {
        wireMock.stubFor(
            get(urlEqualTo("/file/bot$token/photos/test.jpg"))
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "image/jpeg")
                        .withBody(minimalJpegBytes()),
                ),
        )
    }

    private fun stubSendMessage() {
        wireMock.stubFor(
            post(urlEqualTo("/bot$token/sendMessage"))
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"ok":true,"result":{"message_id":1,""" +
                                """"from":{"id":123456,"is_bot":true,"first_name":"TestBot"},""" +
                                """"chat":{"id":12345,"type":"private"},""" +
                                """"date":1700000000,"text":"ok"}}""",
                        ),
                ),
        )
    }

    private fun stubDeleteWebhook() {
        wireMock.stubFor(
            post(urlEqualTo("/bot$token/deleteWebhook"))
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"ok":true,"result":true}"""),
                ),
        )
    }

    private fun stubSetMyCommands() {
        wireMock.stubFor(
            post(urlEqualTo("/bot$token/setMyCommands"))
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"ok":true,"result":true}"""),
                ),
        )
    }

    fun getReceivedMessages(): List<LoggedRequest> =
        wireMock.findAll(postRequestedFor(urlEqualTo("/bot$token/sendMessage")))

    fun getReceivedMessageCount(): Int = getReceivedMessages().size

    fun reset() {
        wireMock.resetRequests()
        wireMock.resetScenarios()
    }

    companion object {
        const val TEST_CHAT_ID = 12345L
        const val TEST_TOKEN = "test-bot-token"

        @Suppress("MagicNumber")
        private fun minimalJpegBytes(): ByteArray =
            byteArrayOf(
                0xFF.toByte(),
                0xD8.toByte(),
                0xFF.toByte(),
                0xE0.toByte(),
                0x00,
                0x10,
                0x4A,
                0x46,
                0x49,
                0x46,
                0x00,
                0x01,
                0x01,
                0x00,
                0x00,
                0x01,
                0x00,
                0x01,
                0x00,
                0x00,
                0xFF.toByte(),
                0xD9.toByte(),
            )
    }
}
