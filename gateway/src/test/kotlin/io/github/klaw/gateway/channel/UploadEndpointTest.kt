package io.github.klaw.gateway.channel

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UploadEndpointTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val localWsChannel = mockk<LocalWsChannel>(relaxed = true)
    private val uploadStore = UploadStore()

    @Test
    fun `upload PNG returns ID`() =
        testApplication {
            install(WebSockets)
            routing { ChatWebSocketEndpoint(localWsChannel, uploadStore).install(this) }
            val response =
                client.post("/upload") {
                    header("Content-Type", "image/png")
                    header("X-Filename", "screenshot.png")
                    setBody(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val id = body["id"]?.jsonPrimitive?.content
            assertNotNull(id, "Response should contain id")
            assertTrue(id!!.isNotBlank(), "ID should not be blank")

            // Verify file is resolvable
            val resolved = uploadStore.resolve(id)
            assertNotNull(resolved, "Upload should be resolvable by ID")
            assertEquals("image/png", resolved!!.mimeType)
            assertEquals("screenshot.png", resolved.originalName)
        }

    @Test
    fun `upload with unsupported content type returns 400`() =
        testApplication {
            install(WebSockets)
            routing { ChatWebSocketEndpoint(localWsChannel, uploadStore).install(this) }
            val response =
                client.post("/upload") {
                    header("Content-Type", "text/plain")
                    setBody("not an image".toByteArray())
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("unsupported"))
        }

    @Test
    fun `upload with empty body returns 400`() =
        testApplication {
            install(WebSockets)
            routing { ChatWebSocketEndpoint(localWsChannel, uploadStore).install(this) }
            val response =
                client.post("/upload") {
                    header("Content-Type", "image/jpeg")
                    setBody(byteArrayOf())
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("empty"))
        }

    @Test
    fun `upload JPEG with default filename`() =
        testApplication {
            install(WebSockets)
            routing { ChatWebSocketEndpoint(localWsChannel, uploadStore).install(this) }
            val response =
                client.post("/upload") {
                    header("Content-Type", "image/jpeg")
                    setBody(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val id = body["id"]?.jsonPrimitive?.content!!
            val resolved = uploadStore.resolve(id)!!
            assertEquals("image/jpeg", resolved.mimeType)
            assertTrue(
                resolved.originalName.endsWith(".jpeg"),
                "Default filename should use extension from content type",
            )
        }
}
