package io.github.klaw.gateway.channel

import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.gateway.api.UploadController
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class UploadEndpointTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val uploadStore = UploadStore(GatewayConfig(channels = ChannelsConfig()))

    private fun mockRequest(
        contentType: String,
        body: ByteArray,
        filename: String? = null,
    ): HttpRequest<ByteArray> =
        mockk(relaxed = true) {
            every { this@mockk.contentType } returns Optional.of(MediaType(contentType))
            every { this@mockk.body } returns Optional.of(body)
            every { headers["X-Filename"] } returns filename
        }

    @Test
    fun `upload PNG returns ID`() {
        val controller = UploadController(uploadStore)
        val request = mockRequest("image/png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "screenshot.png")

        val response = controller.upload(request)

        assertEquals(HttpStatus.OK, response.status)
        val body = json.parseToJsonElement(response.body()!!).jsonObject
        val id = body["id"]?.jsonPrimitive?.content
        assertNotNull(id, "Response should contain id")
        assertTrue(id!!.isNotBlank(), "ID should not be blank")

        val resolved = uploadStore.resolve(id)
        assertNotNull(resolved, "Upload should be resolvable by ID")
        assertEquals("image/png", resolved!!.mimeType)
        assertEquals("screenshot.png", resolved.originalName)
    }

    @Test
    fun `upload with unsupported content type returns 400`() {
        val controller = UploadController(uploadStore)
        val request = mockRequest("text/plain", "not an image".toByteArray())

        val response = controller.upload(request)

        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        assertTrue(response.body()!!.contains("unsupported"))
    }

    @Test
    fun `upload with empty body returns 400`() {
        val controller = UploadController(uploadStore)
        val request = mockRequest("image/jpeg", byteArrayOf())

        val response = controller.upload(request)

        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        assertTrue(response.body()!!.contains("empty"))
    }

    @Test
    fun `upload JPEG with default filename`() {
        val controller = UploadController(uploadStore)
        val request = mockRequest("image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte()))

        val response = controller.upload(request)

        assertEquals(HttpStatus.OK, response.status)
        val body = json.parseToJsonElement(response.body()!!).jsonObject
        val id = body["id"]?.jsonPrimitive?.content!!
        val resolved = uploadStore.resolve(id)!!
        assertEquals("image/jpeg", resolved.mimeType)
        assertTrue(
            resolved.originalName.endsWith(".jpeg"),
            "Default filename should use extension from content type",
        )
    }
}
