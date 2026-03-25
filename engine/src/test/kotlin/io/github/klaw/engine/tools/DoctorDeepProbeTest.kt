package io.github.klaw.engine.tools

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.engine.mcp.McpToolRegistry
import io.github.klaw.engine.memory.EmbeddingService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoctorDeepProbeTest {
    private val embeddingService = mockk<EmbeddingService>()
    private val engineHealthProvider = mockk<EngineHealthProvider>()
    private val mcpToolRegistry = mockk<McpToolRegistry>()
    private val config = mockk<EngineConfig>(relaxed = true)

    private fun createProbe() =
        DoctorDeepProbe(
            embeddingService = embeddingService,
            engineHealthProvider = engineHealthProvider,
            mcpToolRegistry = mcpToolRegistry,
            config = config,
        )

    @Test
    fun `probe returns ok embedding when embed succeeds`() =
        runTest {
            coEvery { embeddingService.embed(any()) } returns FloatArray(384) { 0.1f }
            every { engineHealthProvider.classifyEmbeddingService() } returns "onnx"
            coEvery { engineHealthProvider.checkDatabase() } returns true
            every { config.providers } returns emptyMap()
            every { mcpToolRegistry.serverNames() } returns emptySet()

            val result = createProbe().probe()
            val json = Json.parseToJsonElement(result).jsonObject
            val embedding = json["embedding"]?.jsonObject
            assertEquals("ok", embedding?.get("status")?.jsonPrimitive?.content)
        }

    @Test
    fun `probe returns fail embedding when embed throws`() =
        runTest {
            coEvery { embeddingService.embed(any()) } throws RuntimeException("model not found")
            coEvery { engineHealthProvider.checkDatabase() } returns true
            every { config.providers } returns emptyMap()
            every { mcpToolRegistry.serverNames() } returns emptySet()

            val result = createProbe().probe()
            val json = Json.parseToJsonElement(result).jsonObject
            val embedding = json["embedding"]?.jsonObject
            assertEquals("fail", embedding?.get("status")?.jsonPrimitive?.content)
            assertTrue(embedding?.containsKey("error") == true)
        }

    @Test
    fun `probe returns ok database when check succeeds`() =
        runTest {
            coEvery { embeddingService.embed(any()) } returns FloatArray(384) { 0.1f }
            every { engineHealthProvider.classifyEmbeddingService() } returns "onnx"
            coEvery { engineHealthProvider.checkDatabase() } returns true
            every { config.providers } returns emptyMap()
            every { mcpToolRegistry.serverNames() } returns emptySet()

            val result = createProbe().probe()
            val json = Json.parseToJsonElement(result).jsonObject
            assertEquals(
                "ok",
                json["database"]
                    ?.jsonObject
                    ?.get("status")
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `probe returns fail database when check fails`() =
        runTest {
            coEvery { embeddingService.embed(any()) } returns FloatArray(384) { 0.1f }
            coEvery { engineHealthProvider.checkDatabase() } returns false
            every { config.providers } returns emptyMap()
            every { mcpToolRegistry.serverNames() } returns emptySet()

            val result = createProbe().probe()
            val json = Json.parseToJsonElement(result).jsonObject
            assertEquals(
                "fail",
                json["database"]
                    ?.jsonObject
                    ?.get("status")
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `probe lists providers from config`() =
        runTest {
            coEvery { embeddingService.embed(any()) } returns FloatArray(384) { 0.1f }
            every { engineHealthProvider.classifyEmbeddingService() } returns "onnx"
            coEvery { engineHealthProvider.checkDatabase() } returns true
            every { config.providers } returns
                mapOf(
                    "glm" to ProviderConfig(type = "openai-compatible", endpoint = "http://localhost:8080"),
                    "deepseek" to ProviderConfig(type = "openai-compatible", endpoint = "http://localhost:9090"),
                )
            every { mcpToolRegistry.serverNames() } returns emptySet()

            val result = createProbe().probe()
            val json = Json.parseToJsonElement(result).jsonObject
            val providers = json["providers"]?.jsonArray
            assertEquals(2, providers?.size)
            val names = providers?.map { it.jsonObject["name"]?.jsonPrimitive?.content }
            assertTrue(names?.contains("glm") == true)
            assertTrue(names?.contains("deepseek") == true)
        }

    @Test
    fun `probe lists mcp servers from registry`() =
        runTest {
            coEvery { embeddingService.embed(any()) } returns FloatArray(384) { 0.1f }
            every { engineHealthProvider.classifyEmbeddingService() } returns "onnx"
            coEvery { engineHealthProvider.checkDatabase() } returns true
            every { config.providers } returns emptyMap()
            every { mcpToolRegistry.serverNames() } returns setOf("filesystem", "brave-search")

            val result = createProbe().probe()
            val json = Json.parseToJsonElement(result).jsonObject
            val mcpServers = json["mcpServers"]?.jsonArray
            assertEquals(2, mcpServers?.size)
        }

    @Test
    fun `probe returns valid JSON with all sections`() =
        runTest {
            coEvery { embeddingService.embed(any()) } returns FloatArray(384) { 0.1f }
            every { engineHealthProvider.classifyEmbeddingService() } returns "onnx"
            coEvery { engineHealthProvider.checkDatabase() } returns true
            every { config.providers } returns
                mapOf(
                    "p" to ProviderConfig(type = "openai-compatible", endpoint = "http://localhost"),
                )
            every { mcpToolRegistry.serverNames() } returns setOf("server1")

            val result = createProbe().probe()
            val json = Json.parseToJsonElement(result).jsonObject
            assertTrue(json.containsKey("embedding"))
            assertTrue(json.containsKey("database"))
            assertTrue(json.containsKey("providers"))
            assertTrue(json.containsKey("mcpServers"))
        }
}
