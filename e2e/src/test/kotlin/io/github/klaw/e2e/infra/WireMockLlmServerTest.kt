package io.github.klaw.e2e.infra

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class WireMockLlmServerTest {
    private lateinit var server: WireMockLlmServer
    private val httpClient = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUp() {
        server = WireMockLlmServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `stub single chat response returns expected content`() {
        server.stubChatResponse("Hello from WireMock", promptTokens = 10, completionTokens = 5)

        val response =
            postChatCompletion(
                """{"model":"test","messages":[{"role":"user","content":"Hi"}]}""",
            )

        assertEquals(200, response.statusCode())
        val body = json.parseToJsonElement(response.body()).jsonObject
        val content =
            body["choices"]!!
                .jsonArray[0]
                .jsonObject["message"]!!
                .jsonObject["content"]!!
                .jsonPrimitive.content
        assertEquals("Hello from WireMock", content)
    }

    @Test
    fun `stub sequential responses cycles through turns`() {
        server.stubChatResponseSequence(
            listOf(
                StubResponse("First response", promptTokens = 10, completionTokens = 5),
                StubResponse("Second response", promptTokens = 20, completionTokens = 10),
                StubResponse("Third response", promptTokens = 30, completionTokens = 15),
            ),
        )

        val r1 = extractContent(postChatCompletion(makeRequest("msg1")))
        val r2 = extractContent(postChatCompletion(makeRequest("msg2")))
        val r3 = extractContent(postChatCompletion(makeRequest("msg3")))

        assertEquals("First response", r1)
        assertEquals("Second response", r2)
        assertEquals("Third response", r3)
    }

    @Test
    fun `stub summarization response has priority over regular stub`() {
        server.stubChatResponse("Regular response", promptTokens = 10, completionTokens = 5)
        server.stubSummarizationResponse("Summary of the conversation")

        // Regular request
        val regular = extractContent(postChatCompletion(makeRequest("Hello")))
        assertEquals("Regular response", regular)

        // Summarization request
        val summarizationBody =
            """
            {"model":"test","messages":[
                {"role":"system","content":"You are a conversation summarizer. Write a concise markdown summary"},
                {"role":"user","content":"[user] msg1\n[assistant] resp1"}
            ]}
            """.trimIndent()
        val summary = extractContent(postChatCompletion(summarizationBody))
        assertEquals("Summary of the conversation", summary)
    }

    @Test
    fun `getNthRequestMessages correctly parses messages array`() {
        server.stubChatResponse("Response", promptTokens = 10, completionTokens = 5)

        postChatCompletion(
            """{"model":"test","messages":[
                {"role":"system","content":"You are helpful"},
                {"role":"user","content":"What is 2+2?"}
            ]}""",
        )

        val messages = server.getNthRequestMessages(0)
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("What is 2+2?", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hasReceivedSummarizationCall detects summarization requests`() {
        server.stubChatResponse("Response", promptTokens = 10, completionTokens = 5)

        // No summarization call yet
        assertFalse(server.hasReceivedSummarizationCall())

        // Regular call
        postChatCompletion(makeRequest("Hello"))
        assertFalse(server.hasReceivedSummarizationCall())

        // Summarization call
        server.stubSummarizationResponse("Summary")
        val summarizationBody =
            """
            {"model":"test","messages":[
                {"role":"system","content":"You are a conversation summarizer. Write a concise markdown summary"},
                {"role":"user","content":"[user] msg1"}
            ]}
            """.trimIndent()
        postChatCompletion(summarizationBody)
        assertTrue(server.hasReceivedSummarizationCall())
    }

    @Test
    fun `getSummarizationCallCount returns correct count`() {
        server.stubChatResponse("Response", promptTokens = 10, completionTokens = 5)
        server.stubSummarizationResponse("Summary")

        assertEquals(0, server.getSummarizationCallCount())

        postChatCompletion(makeRequest("Hello"))
        assertEquals(0, server.getSummarizationCallCount())

        postChatCompletion(makeSummarizationRequest("msg1"))
        assertEquals(1, server.getSummarizationCallCount())

        postChatCompletion(makeSummarizationRequest("msg2"))
        assertEquals(2, server.getSummarizationCallCount())
    }

    @Test
    fun `stubSummarizationResponseSequence cycles through summarization turns`() {
        server.stubSummarizationResponseSequence(
            listOf(
                StubResponse("Summary batch 1", promptTokens = 50, completionTokens = 30),
                StubResponse("Summary batch 2", promptTokens = 50, completionTokens = 30),
            ),
        )

        val s1 = extractContent(postChatCompletion(makeSummarizationRequest("conv1")))
        val s2 = extractContent(postChatCompletion(makeSummarizationRequest("conv2")))

        assertEquals("Summary batch 1", s1)
        assertEquals("Summary batch 2", s2)
    }

    @Test
    fun `stubSummarizationResponseSequence applies delay when specified`() {
        server.stubSummarizationResponseSequence(
            listOf(
                StubResponse("Slow summary", promptTokens = 50, completionTokens = 30, delayMs = 500),
                StubResponse("Fast summary", promptTokens = 50, completionTokens = 30),
            ),
        )

        val start1 = System.currentTimeMillis()
        val s1 = extractContent(postChatCompletion(makeSummarizationRequest("conv1")))
        val elapsed1 = System.currentTimeMillis() - start1

        val start2 = System.currentTimeMillis()
        val s2 = extractContent(postChatCompletion(makeSummarizationRequest("conv2")))
        val elapsed2 = System.currentTimeMillis() - start2

        assertEquals("Slow summary", s1)
        assertEquals("Fast summary", s2)
        assertTrue(elapsed1 >= 400, "First response should be delayed (~500ms), was ${elapsed1}ms")
        assertTrue(elapsed2 < 400, "Second response should be fast, was ${elapsed2}ms")
    }

    @Test
    fun `reset clears stubs and recorded requests`() {
        server.stubChatResponse("Response", promptTokens = 10, completionTokens = 5)
        postChatCompletion(makeRequest("Hello"))
        assertTrue(server.getRecordedRequests().isNotEmpty())

        server.reset()

        assertTrue(server.getRecordedRequests().isEmpty())
    }

    private fun postChatCompletion(body: String): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("http://localhost:${server.port}/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun extractContent(response: HttpResponse<String>): String {
        val body = json.parseToJsonElement(response.body()).jsonObject
        return body["choices"]!!
            .jsonArray[0]
            .jsonObject["message"]!!
            .jsonObject["content"]!!
            .jsonPrimitive.content
    }

    private fun makeRequest(content: String): String =
        """{"model":"test","messages":[{"role":"user","content":"$content"}]}"""

    private fun makeSummarizationRequest(content: String): String =
        """{"model":"test","messages":[{"role":"system","content":"You are a conversation summarizer. Write a concise markdown summary"},{"role":"user","content":"$content"}]}"""
}
