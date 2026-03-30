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
private const val STATE_TEXT_READY = "text-ready"
private const val STATE_CALLBACK_READY = "callback-ready"
private const val BOT_ID = 123456L
private const val PHOTO_MESSAGE_ID = 100
private const val TEXT_MESSAGE_ID = 101
private const val DEFAULT_SENDER_ID = 999L
private const val MESSAGE_DATE = 1700000000
private const val SMALL_PHOTO_WIDTH = 90
private const val SMALL_PHOTO_HEIGHT = 90
private const val SMALL_PHOTO_SIZE = 500
private const val LARGE_PHOTO_WIDTH = 800
private const val LARGE_PHOTO_HEIGHT = 600
private const val LARGE_PHOTO_SIZE = 10240
private const val FILE_SIZE = 1024
private const val CALLBACK_QUERY_ID_BASE = 5000
private const val SEND_MESSAGE_ID = 500

class MockTelegramServer(
    private val token: String = "test-bot-token",
) {
    private val wireMock = WireMockServer(wireMockConfig().dynamicPort())
    private var updateCounter = 0
    private var callbackQueryCounter = 0

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
        stubSendChatAction()
        stubDeleteWebhook()
        stubSetMyCommands()
        stubAnswerCallbackQuery()
        stubEditMessageReplyMarkup()
        stubSendMessageDraft()
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
                append(""""from":{"id":$DEFAULT_SENDER_ID,"is_bot":false,"first_name":"TestUser"},""")
                append(""""chat":{"id":$chatId,"type":"private"},""")
                append(""""date":$MESSAGE_DATE,""")
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

        // After photo is consumed, revert to STARTED state
        wireMock.stubFor(
            post(urlEqualTo("/bot$token/getUpdates"))
                .inScenario(SCENARIO_NAME)
                .whenScenarioStateIs(STATE_PHOTO_READY)
                .willSetStateTo(Scenario.STARTED)
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"ok":true,"result":[]}"""),
                ),
        )

        logger.debug { "Photo update queued for chatId=$chatId" }
    }

    fun sendTextUpdate(
        chatId: Long,
        text: String,
        senderId: Long = DEFAULT_SENDER_ID,
    ) {
        updateCounter++
        val escapedText = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val updateJson =
            buildString {
                append("""{"ok":true,"result":[{"update_id":$updateCounter,"message":{""")
                append(""""message_id":$TEXT_MESSAGE_ID,""")
                append(""""from":{"id":$senderId,"is_bot":false,"first_name":"TestUser"},""")
                append(""""chat":{"id":$chatId,"type":"private"},""")
                append(""""date":$MESSAGE_DATE,""")
                append(""""text":"$escapedText"}}]}""")
            }

        wireMock.stubFor(
            post(urlEqualTo("/bot$token/getUpdates"))
                .inScenario(SCENARIO_NAME)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo(STATE_TEXT_READY)
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody(updateJson),
                ),
        )

        wireMock.stubFor(
            post(urlEqualTo("/bot$token/getUpdates"))
                .inScenario(SCENARIO_NAME)
                .whenScenarioStateIs(STATE_TEXT_READY)
                .willSetStateTo(Scenario.STARTED)
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"ok":true,"result":[]}"""),
                ),
        )

        logger.debug { "Text update queued for chatId=$chatId" }
    }

    private fun stubSendChatAction() {
        wireMock.stubFor(
            post(urlEqualTo("/bot$token/sendChatAction"))
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"ok":true,"result":true}"""),
                ),
        )
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
                            """{"ok":true,"result":{"message_id":$SEND_MESSAGE_ID,""" +
                                """"from":{"id":$BOT_ID,"is_bot":true,"first_name":"TestBot"},""" +
                                """"chat":{"id":$TEST_CHAT_ID,"type":"private"},""" +
                                """"date":$MESSAGE_DATE,"text":"ok"}}""",
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

    private fun stubAnswerCallbackQuery() {
        wireMock.stubFor(
            post(urlEqualTo("/bot$token/answerCallbackQuery"))
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"ok":true,"result":true}"""),
                ),
        )
    }

    private fun stubSendMessageDraft() {
        wireMock.stubFor(
            post(urlEqualTo("/bot$token/sendMessageDraft"))
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"ok":true,"result":true}"""),
                ),
        )
    }

    private fun stubEditMessageReplyMarkup() {
        wireMock.stubFor(
            post(urlEqualTo("/bot$token/editMessageReplyMarkup"))
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"ok":true,"result":{"message_id":$SEND_MESSAGE_ID,""" +
                                """"from":{"id":$BOT_ID,"is_bot":true,"first_name":"TestBot"},""" +
                                """"chat":{"id":$TEST_CHAT_ID,"type":"private"},""" +
                                """"date":$MESSAGE_DATE,"text":"ok"}}""",
                        ),
                ),
        )
    }

    fun sendCallbackQueryUpdate(
        chatId: Long,
        data: String,
        messageId: Long = SEND_MESSAGE_ID.toLong(),
    ) {
        updateCounter++
        callbackQueryCounter++
        val queryId = CALLBACK_QUERY_ID_BASE + callbackQueryCounter
        val updateJson =
            buildString {
                append("""{"ok":true,"result":[{"update_id":$updateCounter,"callback_query":{""")
                append(""""id":"$queryId",""")
                append(""""from":{"id":$DEFAULT_SENDER_ID,"is_bot":false,"first_name":"TestUser"},""")
                append(""""message":{"message_id":$messageId,""")
                append(""""from":{"id":$BOT_ID,"is_bot":true,"first_name":"TestBot"},""")
                append(""""chat":{"id":$chatId,"type":"private"},""")
                append(""""date":$MESSAGE_DATE,"text":"approval"},""")
                append(""""chat_instance":"${chatId}_instance",""")
                append(""""data":"$data"}}]}""")
            }

        wireMock.stubFor(
            post(urlEqualTo("/bot$token/getUpdates"))
                .inScenario(SCENARIO_NAME)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo(STATE_CALLBACK_READY)
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody(updateJson),
                ),
        )

        wireMock.stubFor(
            post(urlEqualTo("/bot$token/getUpdates"))
                .inScenario(SCENARIO_NAME)
                .whenScenarioStateIs(STATE_CALLBACK_READY)
                .willSetStateTo(Scenario.STARTED)
                .willReturn(
                    aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"ok":true,"result":[]}"""),
                ),
        )

        logger.debug { "Callback query update queued for chatId=$chatId" }
    }

    fun getReceivedMessages(): List<LoggedRequest> =
        wireMock.findAll(postRequestedFor(urlEqualTo("/bot$token/sendMessage")))

    fun getSetMyCommandsRequests(): List<LoggedRequest> =
        wireMock.findAll(postRequestedFor(urlEqualTo("/bot$token/setMyCommands")))

    fun getReceivedMessageCount(): Int = getReceivedMessages().size

    fun getReceivedDrafts(): List<LoggedRequest> =
        wireMock.findAll(postRequestedFor(urlEqualTo("/bot$token/sendMessageDraft")))

    fun getAnswerCallbackQueryRequests(): List<LoggedRequest> =
        wireMock.findAll(postRequestedFor(urlEqualTo("/bot$token/answerCallbackQuery")))

    fun getEditMessageReplyMarkupRequests(): List<LoggedRequest> =
        wireMock.findAll(postRequestedFor(urlEqualTo("/bot$token/editMessageReplyMarkup")))

    fun getRequestCount(path: String): Int =
        wireMock.findAll(postRequestedFor(urlEqualTo(path))).size +
            wireMock
                .findAll(
                    com.github.tomakehurst.wiremock.client.WireMock
                        .getRequestedFor(urlEqualTo(path)),
                ).size

    fun reset() {
        wireMock.resetRequests()
        wireMock.resetScenarios()
        setupStubs()
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
