package io.github.klaw.engine.tools

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineHealthToolsTest {
    private val healthProvider = mockk<EngineHealthProvider>()
    private val tools = EngineHealthTools(healthProvider)

    private val fixedHealth =
        EngineHealth(
            gatewayStatus = "connected",
            engineUptime = "PT1H",
            docker = false,
            sandbox =
                SandboxHealth(
                    enabled = true,
                    keepAlive = true,
                    containerActive = true,
                    executions = 10,
                ),
            mcpServers = listOf("server-a", "server-b"),
            embeddingService = "onnx",
            sqliteVec = true,
            databaseOk = true,
            scheduledJobs = 3,
            activeSessions = 2,
            pendingDeliveries = 5,
            heartbeatRunning = true,
            docsEnabled = true,
            memoryFacts = 100,
            runningSubagents = 0,
        )

    @Test
    fun `health returns valid JSON string`() =
        runTest {
            coEvery { healthProvider.getHealth() } returns fixedHealth
            val result = tools.health()
            val parsed = Json.parseToJsonElement(result)
            assertTrue(parsed is JsonObject)
        }

    @Test
    fun `JSON contains all expected top-level keys`() =
        runTest {
            coEvery { healthProvider.getHealth() } returns fixedHealth
            val result = tools.health()
            val obj = Json.parseToJsonElement(result).jsonObject
            val expectedKeys =
                setOf(
                    "gateway_status",
                    "engine_uptime",
                    "docker",
                    "sandbox",
                    "mcp_servers",
                    "embedding_service",
                    "sqlite_vec",
                    "database_ok",
                    "scheduled_jobs",
                    "active_sessions",
                    "pending_deliveries",
                    "heartbeat_running",
                    "docs_enabled",
                    "memory_facts",
                )
            for (key in expectedKeys) {
                assertNotNull(obj[key], "Missing key: $key")
            }
        }

    @Test
    fun `sandbox is a nested JSON object`() =
        runTest {
            coEvery { healthProvider.getHealth() } returns fixedHealth
            val result = tools.health()
            val obj = Json.parseToJsonElement(result).jsonObject
            val sandbox = obj["sandbox"]
            assertTrue(sandbox is JsonObject, "sandbox should be a JSON object")
            val sandboxObj = sandbox!!.jsonObject
            assertNotNull(sandboxObj["enabled"])
            assertNotNull(sandboxObj["keep_alive"])
            assertNotNull(sandboxObj["container_active"])
            assertNotNull(sandboxObj["executions"])
        }

    @Test
    fun `mcp_servers is a JSON array`() =
        runTest {
            coEvery { healthProvider.getHealth() } returns fixedHealth
            val result = tools.health()
            val obj = Json.parseToJsonElement(result).jsonObject
            val servers = obj["mcp_servers"]
            assertTrue(servers is JsonArray, "mcp_servers should be a JSON array")
            assertEquals(2, (servers as JsonArray).size)
        }

    @Test
    fun `gateway_status value matches health object`() =
        runTest {
            coEvery { healthProvider.getHealth() } returns fixedHealth
            val result = tools.health()
            val obj = Json.parseToJsonElement(result).jsonObject
            assertEquals("connected", obj["gateway_status"]?.jsonPrimitive?.content)
        }
}
