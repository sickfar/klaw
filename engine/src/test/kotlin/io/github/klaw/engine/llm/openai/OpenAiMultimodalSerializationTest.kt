package io.github.klaw.engine.llm.openai

import io.github.klaw.common.llm.ImageUrlContentPart
import io.github.klaw.common.llm.ImageUrlData
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.TextContentPart
import io.github.klaw.engine.llm.toOpenAiMessage
import io.github.klaw.engine.llm.toOpenAiRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenAiMultimodalSerializationTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    @Test
    fun `text content serializes as plain string`() {
        val msg = OpenAiMessage(role = "user", content = OpenAiContent.Text("hello"))
        val jsonStr = json.encodeToString(msg)
        val parsed = Json.parseToJsonElement(jsonStr).jsonObject

        assertEquals(JsonPrimitive("hello"), parsed["content"])
    }

    @Test
    fun `multimodal content serializes as array`() {
        val msg =
            OpenAiMessage(
                role = "user",
                content =
                    OpenAiContent.Parts(
                        listOf(
                            OpenAiTextPart("describe this"),
                            OpenAiImageUrlPart(OpenAiImageUrl("data:image/png;base64,abc123")),
                        ),
                    ),
            )
        val jsonStr = json.encodeToString(msg)
        val parsed = Json.parseToJsonElement(jsonStr).jsonObject
        val contentArray = parsed["content"]

        assertTrue(contentArray is JsonArray, "content should be a JSON array")
        val arr = contentArray!!.jsonArray
        assertEquals(2, arr.size)

        val textPart = arr[0].jsonObject
        assertEquals("text", textPart["type"]?.jsonPrimitive?.content)
        assertEquals("describe this", textPart["text"]?.jsonPrimitive?.content)

        val imagePart = arr[1].jsonObject
        assertEquals("image_url", imagePart["type"]?.jsonPrimitive?.content)
        val imageUrl = imagePart["image_url"]?.jsonObject
        assertEquals("data:image/png;base64,abc123", imageUrl?.get("url")?.jsonPrimitive?.content)
    }

    @Test
    fun `null content omitted from JSON`() {
        val msg = OpenAiMessage(role = "assistant", content = null)
        val jsonStr = json.encodeToString(msg)
        val parsed = Json.parseToJsonElement(jsonStr).jsonObject

        assertTrue("content" !in parsed, "null content should not appear in JSON")
    }

    @Test
    fun `toOpenAiMessage maps text content`() {
        val llmMsg = LlmMessage(role = "user", content = "text")
        val openAiMsg = llmMsg.toOpenAiMessage()

        assertEquals("user", openAiMsg.role)
        assertEquals(OpenAiContent.Text("text"), openAiMsg.content)
    }

    @Test
    fun `toOpenAiMessage maps multimodal contentParts`() {
        val llmMsg =
            LlmMessage(
                role = "user",
                contentParts =
                    listOf(
                        TextContentPart("hi"),
                        ImageUrlContentPart(ImageUrlData("data:image/jpeg;base64,xyz")),
                    ),
            )
        val openAiMsg = llmMsg.toOpenAiMessage()

        val parts = openAiMsg.content
        assertTrue(parts is OpenAiContent.Parts)
        val partsList = (parts as OpenAiContent.Parts).parts
        assertEquals(2, partsList.size)
        assertEquals(OpenAiTextPart("hi"), partsList[0])
        assertEquals(OpenAiImageUrlPart(OpenAiImageUrl("data:image/jpeg;base64,xyz")), partsList[1])
    }

    @Test
    fun `toOpenAiMessage maps null content`() {
        val llmMsg = LlmMessage(role = "assistant", content = null, contentParts = null)
        val openAiMsg = llmMsg.toOpenAiMessage()

        assertNull(openAiMsg.content)
    }

    @Test
    fun `full round-trip multimodal request serializes content as array`() {
        val request =
            LlmRequest(
                messages =
                    listOf(
                        LlmMessage(role = "system", content = "You are helpful"),
                        LlmMessage(
                            role = "user",
                            contentParts =
                                listOf(
                                    TextContentPart("What is in this image?"),
                                    ImageUrlContentPart(ImageUrlData("data:image/png;base64,AAAA")),
                                ),
                        ),
                    ),
            )
        val openAiRequest = request.toOpenAiRequest("gpt-4o")
        val jsonStr = json.encodeToString(openAiRequest)
        val parsed = Json.parseToJsonElement(jsonStr).jsonObject

        val messages = parsed["messages"]!!.jsonArray

        // System message: plain string content
        val sysContent = messages[0].jsonObject["content"]
        assertTrue(sysContent is JsonPrimitive, "system content should be a string")
        assertEquals("You are helpful", sysContent!!.jsonPrimitive.content)

        // User message: array content
        val userContent = messages[1].jsonObject["content"]
        assertTrue(userContent is JsonArray, "user multimodal content should be an array")
        assertEquals(2, userContent!!.jsonArray.size)
    }

    @Test
    fun `deserialization of text content from string`() {
        val jsonStr = """{"role":"user","content":"hello"}"""
        val msg = json.decodeFromString<OpenAiMessage>(jsonStr)

        assertEquals(OpenAiContent.Text("hello"), msg.content)
    }

    @Test
    fun `deserialization of multimodal content from array`() {
        val jsonStr =
            """{"role":"user","content":[{"type":"text","text":"look"},{"type":"image_url","image_url":{"url":"https://example.com/img.png"}}]}"""
        val msg = json.decodeFromString<OpenAiMessage>(jsonStr)

        assertTrue(msg.content is OpenAiContent.Parts)
        val parts = (msg.content as OpenAiContent.Parts).parts
        assertEquals(2, parts.size)
        assertEquals(OpenAiTextPart("look"), parts[0])
        assertEquals(OpenAiImageUrlPart(OpenAiImageUrl("https://example.com/img.png")), parts[1])
    }

    @Test
    fun `deserialization of null content`() {
        val jsonStr = """{"role":"assistant"}"""
        val msg = json.decodeFromString<OpenAiMessage>(jsonStr)

        assertNull(msg.content)
    }
}
