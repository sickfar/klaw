package io.github.klaw.engine.tools

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.VirtualTableSetup
import io.github.klaw.engine.message.MessageProcessor
import io.github.klaw.engine.message.ScheduledMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.inject.Provider
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SubagentToolsTest {
    private val processor = mockk<MessageProcessor>()
    private val provider =
        mockk<Provider<MessageProcessor>> {
            every { get() } returns processor
        }
    private lateinit var repo: SubagentRunRepository
    private lateinit var tools: SubagentTools
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(driver)
        VirtualTableSetup.createVirtualTables(driver, sqliteVecAvailable = false)
        val database = KlawDatabase(driver)
        repo = SubagentRunRepository(database)
        tools = SubagentTools(provider, repo)
    }

    @Test
    fun `spawn returns JSON containing id, name, status fields`() =
        runTest {
            every { processor.handleScheduledMessage(any()) } returns Job()

            val result = tools.spawn("test-agent", "do something", "gpt-4", "chat:123")
            val obj = json.parseToJsonElement(result).jsonObject

            assertNotNull(obj["id"]?.jsonPrimitive?.content)
            assertEquals("test-agent", obj["name"]?.jsonPrimitive?.content)
            assertEquals("RUNNING", obj["status"]?.jsonPrimitive?.content)
        }

    @Test
    fun `spawn creates RUNNING record in repository`() =
        runTest {
            every { processor.handleScheduledMessage(any()) } returns Job()

            tools.spawn("my-task", "do it")

            assertEquals(1, repo.countByStatus("RUNNING"))
        }

    @Test
    fun `spawn passes runId and source context through ScheduledMessage`() =
        runTest {
            val messageSlot = slot<ScheduledMessage>()
            every { processor.handleScheduledMessage(capture(messageSlot)) } returns Job()

            val result = tools.spawn("test-agent", "do something", "gpt-4", "chat:123")
            val runId =
                json
                    .parseToJsonElement(result)
                    .jsonObject["id"]!!
                    .jsonPrimitive.content

            val captured = messageSlot.captured
            assertEquals("test-agent", captured.name)
            assertEquals("do something", captured.message)
            assertEquals("gpt-4", captured.model)
            assertEquals("chat:123", captured.injectInto)
            assertEquals(runId, captured.runId)
            // sourceChatId/sourceChannel are null because no ChatContext in test coroutine
            assertNull(captured.sourceChatId)
            assertNull(captured.sourceChannel)
        }

    @Test
    fun `spawn returns immediately`() =
        runTest {
            every { processor.handleScheduledMessage(any()) } returns Job()

            val result = tools.spawn("agent", "msg")
            assertTrue(result.isNotEmpty())
        }
}
