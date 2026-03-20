package io.github.klaw.common.llm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentPartTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    @Test
    fun `TextContentPart serialization`() {
        val part: ContentPart = TextContentPart(text = "hello")
        val encoded = json.encodeToString(part)
        assertTrue(encoded.contains(""""type":"text""""), "Expected type=text in: $encoded")
        assertTrue(encoded.contains(""""text":"hello""""), "Expected text in: $encoded")
    }

    @Test
    fun `ImageUrlContentPart serialization`() {
        val part: ContentPart = ImageUrlContentPart(imageUrl = ImageUrlData(url = "data:image/png;base64,abc123"))
        val encoded = json.encodeToString(part)
        assertTrue(encoded.contains(""""type":"image_url""""), "Expected type=image_url in: $encoded")
        assertTrue(encoded.contains(""""image_url""""), "Expected image_url key in: $encoded")
        assertTrue(encoded.contains(""""url":"data:image/png;base64,abc123""""), "Expected url in: $encoded")
    }

    @Test
    fun `TextContentPart deserialization`() {
        val raw = """{"type":"text","text":"hello"}"""
        val part = json.decodeFromString<ContentPart>(raw)
        val textPart = assertIs<TextContentPart>(part)
        assertEquals("hello", textPart.text)
    }

    @Test
    fun `ImageUrlContentPart deserialization`() {
        val raw = """{"type":"image_url","image_url":{"url":"data:image/png;base64,abc123"}}"""
        val part = json.decodeFromString<ContentPart>(raw)
        val imgPart = assertIs<ImageUrlContentPart>(part)
        assertEquals("data:image/png;base64,abc123", imgPart.imageUrl.url)
    }

    @Test
    fun `TextContentPart round-trip`() {
        val original: ContentPart = TextContentPart(text = "test message")
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ContentPart>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `ImageUrlContentPart round-trip`() {
        val original: ContentPart = ImageUrlContentPart(imageUrl = ImageUrlData(url = "https://example.com/img.png"))
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ContentPart>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `LlmMessage with contentParts null - backward compatible`() {
        val raw = """{"role":"user","content":"hello"}"""
        val msg = json.decodeFromString<LlmMessage>(raw)
        assertEquals("user", msg.role)
        assertEquals("hello", msg.content)
        assertNull(msg.contentParts)
    }

    @Test
    fun `LlmMessage with contentParts present - round-trip`() {
        val msg =
            LlmMessage(
                role = "user",
                content = null,
                contentParts =
                    listOf(
                        TextContentPart(text = "What is this?"),
                        ImageUrlContentPart(imageUrl = ImageUrlData(url = "data:image/png;base64,abc")),
                    ),
            )
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<LlmMessage>(encoded)
        assertEquals(msg, decoded)
        val parts = decoded.contentParts
        assertEquals(2, parts?.size)
        assertIs<TextContentPart>(parts?.get(0))
        assertIs<ImageUrlContentPart>(parts?.get(1))
    }

    @Test
    fun `LlmMessage contentParts null not serialized with encodeDefaults=false`() {
        val msg = LlmMessage(role = "user", content = "hi")
        val encoded = json.encodeToString(msg)
        assertTrue(!encoded.contains("contentParts"), "null contentParts should not be in JSON: $encoded")
    }

    @Test
    fun `ContentPart list serialization`() {
        val parts: List<ContentPart> =
            listOf(
                TextContentPart(text = "describe this"),
                ImageUrlContentPart(imageUrl = ImageUrlData(url = "data:image/jpeg;base64,/9j/4AAQ")),
            )
        val encoded = json.encodeToString(parts)
        val decoded = json.decodeFromString<List<ContentPart>>(encoded)
        assertEquals(2, decoded.size)
        assertEquals(parts, decoded)
    }
}
