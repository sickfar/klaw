package io.github.klaw.e2e.infra

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

private const val TEST_CHAT_ID = 12345L

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MockTelegramServerTest {
    private val telegramServer = MockTelegramServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient()

    @BeforeAll
    fun setup() {
        telegramServer.start()
    }

    @AfterAll
    fun teardown() {
        httpClient.close()
        telegramServer.stop()
    }

    @Test
    fun `getMe returns bot info`() =
        runBlocking {
            val response =
                httpClient.post("${telegramServer.localBaseUrl}/bot${MockTelegramServer.TEST_TOKEN}/getMe")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body["ok"]?.jsonPrimitive?.boolean ?: false)
            val result = body["result"]!!.jsonObject
            assertTrue(result["is_bot"]?.jsonPrimitive?.boolean ?: false)
            assertEquals("test_bot", result["username"]?.jsonPrimitive?.content)
        }

    @Test
    fun `getUpdates returns empty initially`() =
        runBlocking {
            telegramServer.reset()
            val response =
                httpClient.post("${telegramServer.localBaseUrl}/bot${MockTelegramServer.TEST_TOKEN}/getUpdates") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"offset":0,"timeout":0}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body["ok"]?.jsonPrimitive?.boolean ?: false)
            val result = body["result"]!!.jsonArray
            assertEquals(0, result.size)
        }

    @Test
    fun `after sendPhotoUpdate, getUpdates returns photo message`() =
        runBlocking {
            telegramServer.reset()
            telegramServer.sendPhotoUpdate(chatId = TEST_CHAT_ID, caption = "Test caption")

            val response =
                httpClient.post("${telegramServer.localBaseUrl}/bot${MockTelegramServer.TEST_TOKEN}/getUpdates") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"offset":0,"timeout":0}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val result = body["result"]!!.jsonArray
            assertEquals(1, result.size)

            val update = result[0].jsonObject
            val message = update["message"]!!.jsonObject
            val chatId =
                message["chat"]
                    ?.jsonObject
                    ?.get("id")
                    ?.jsonPrimitive
                    ?.long
            assertEquals(TEST_CHAT_ID, chatId)
            assertEquals("Test caption", message["caption"]?.jsonPrimitive?.content)

            val photos = message["photo"]!!.jsonArray
            assertEquals(2, photos.size)
        }

    @Test
    fun `getFile returns file info`() =
        runBlocking {
            val response =
                httpClient.post("${telegramServer.localBaseUrl}/bot${MockTelegramServer.TEST_TOKEN}/getFile") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"file_id":"test_file_id"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body["ok"]?.jsonPrimitive?.boolean ?: false)
            val result = body["result"]!!.jsonObject
            assertEquals("test_file_id", result["file_id"]?.jsonPrimitive?.content)
            assertEquals("photos/test.jpg", result["file_path"]?.jsonPrimitive?.content)
        }

    @Test
    fun `file download returns image bytes`() =
        runBlocking {
            val response =
                httpClient.get(
                    "${telegramServer.localBaseUrl}/file/bot${MockTelegramServer.TEST_TOKEN}/photos/test.jpg",
                )
            assertEquals(HttpStatusCode.OK, response.status)
            val bytes = response.bodyAsBytes()
            assertTrue(bytes.isNotEmpty(), "Downloaded file should not be empty")
            // Verify JPEG magic bytes
            assertEquals(0xFF.toByte(), bytes[0])
            assertEquals(0xD8.toByte(), bytes[1])
        }

    @Test
    fun `sendMessage is captured by getReceivedMessages`() =
        runBlocking {
            telegramServer.reset()
            httpClient.post("${telegramServer.localBaseUrl}/bot${MockTelegramServer.TEST_TOKEN}/sendMessage") {
                contentType(ContentType.Application.Json)
                setBody("""{"chat_id":$TEST_CHAT_ID,"text":"Hello from bot"}""")
            }
            val received = telegramServer.getReceivedMessages()
            assertEquals(1, received.size)
            assertTrue(received[0].bodyAsString.contains("Hello from bot"))
        }

    @Test
    fun `deleteWebhook returns ok`() =
        runBlocking {
            val response =
                httpClient.post("${telegramServer.localBaseUrl}/bot${MockTelegramServer.TEST_TOKEN}/deleteWebhook")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body["ok"]?.jsonPrimitive?.boolean ?: false)
        }

    @Test
    fun `setMyCommands returns ok`() =
        runBlocking {
            val response =
                httpClient.post("${telegramServer.localBaseUrl}/bot${MockTelegramServer.TEST_TOKEN}/setMyCommands") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"commands":[]}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body["ok"]?.jsonPrimitive?.boolean ?: false)
        }

    @Test
    fun `after sendTextUpdate, getUpdates returns text message`() =
        runBlocking {
            telegramServer.reset()
            telegramServer.sendTextUpdate(chatId = TEST_CHAT_ID, text = "Hello bot")

            val response =
                httpClient.post("${telegramServer.localBaseUrl}/bot${MockTelegramServer.TEST_TOKEN}/getUpdates") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"offset":0,"timeout":0}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val result = body["result"]!!.jsonArray
            assertEquals(1, result.size)

            val update = result[0].jsonObject
            val message = update["message"]!!.jsonObject
            val chatId =
                message["chat"]
                    ?.jsonObject
                    ?.get("id")
                    ?.jsonPrimitive
                    ?.long
            assertEquals(TEST_CHAT_ID, chatId)
            assertEquals("Hello bot", message["text"]?.jsonPrimitive?.content)

            // Text message should not have photo field
            assertTrue(message["photo"] == null, "Text message should not contain photo field")
        }

    @Test
    fun `sendTextUpdate with custom senderId`() =
        runBlocking {
            telegramServer.reset()
            val customSenderId = 42L
            telegramServer.sendTextUpdate(chatId = TEST_CHAT_ID, text = "Hi", senderId = customSenderId)

            val response =
                httpClient.post("${telegramServer.localBaseUrl}/bot${MockTelegramServer.TEST_TOKEN}/getUpdates") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"offset":0,"timeout":0}""")
                }
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val message = body["result"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
            val fromId =
                message["from"]
                    ?.jsonObject
                    ?.get("id")
                    ?.jsonPrimitive
                    ?.long
            assertEquals(customSenderId, fromId)
        }

    @Test
    fun `sendChatAction returns ok`() =
        runBlocking {
            val response =
                httpClient.post(
                    "${telegramServer.localBaseUrl}/bot${MockTelegramServer.TEST_TOKEN}/sendChatAction",
                ) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"chat_id":$TEST_CHAT_ID,"action":"typing"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body["ok"]?.jsonPrimitive?.boolean ?: false)
        }
}
