package io.github.klaw.engine.socket

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.engine.context.SkillRegistry
import io.github.klaw.engine.init.InitCliHandler
import io.github.klaw.engine.llm.LlmUsageTracker
import io.github.klaw.engine.maintenance.ReindexService
import io.github.klaw.engine.memory.ConsolidationResult
import io.github.klaw.engine.memory.DailyConsolidationService
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.tools.EngineHealthProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliCommandDispatcherConsolidateTest {
    private val initCliHandler = mockk<InitCliHandler>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val klawScheduler = mockk<KlawScheduler>(relaxed = true)
    private val memoryService = mockk<MemoryService>(relaxed = true)
    private val reindexService = mockk<ReindexService>(relaxed = true)
    private val skillRegistry = mockk<SkillRegistry>(relaxed = true)
    private val consolidationService = mockk<DailyConsolidationService>(relaxed = true)
    private val engineHealthProvider = mockk<EngineHealthProvider>(relaxed = true)
    private val llmUsageTracker = mockk<LlmUsageTracker>(relaxed = true)
    private val engineConfig = mockk<EngineConfig>(relaxed = true)
    private val commandsCliHandler = mockk<CommandsCliHandler>(relaxed = true)

    private fun createDispatcher() =
        CliCommandDispatcher(
            initCliHandler,
            sessionManager,
            klawScheduler,
            memoryService,
            reindexService,
            skillRegistry,
            consolidationService,
            engineHealthProvider,
            llmUsageTracker,
            mockk(relaxed = true),
            engineConfig,
            mockk(relaxed = true),
            commandsCliHandler,
            ContextDiagnoseHandler(mockk(relaxed = true), mockk(relaxed = true)),
        )

    @Test
    fun `memory_consolidate with defaults`() =
        runBlocking {
            coEvery {
                consolidationService.consolidate(any(), any())
            } returns ConsolidationResult.Success(factsSaved = 7)

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("memory_consolidate"))

            assertTrue(result.contains("7 facts saved"))
            coVerify { consolidationService.consolidate(any(), eq(false)) }
        }

    @Test
    fun `memory_consolidate with date and force`() =
        runBlocking {
            val date = LocalDate(2026, 3, 19)
            coEvery { consolidationService.consolidate(date, true) } returns ConsolidationResult.Success(factsSaved = 3)

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage(
                        "memory_consolidate",
                        mapOf("date" to "2026-03-19", "force" to "true"),
                    ),
                )

            assertTrue(result.contains("3 facts saved"))
            assertTrue(result.contains("2026-03-19"))
            coVerify { consolidationService.consolidate(date, true) }
        }

    @Test
    fun `memory_consolidate returns already consolidated`() =
        runBlocking {
            coEvery { consolidationService.consolidate(any(), any()) } returns ConsolidationResult.AlreadyConsolidated

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("memory_consolidate"))

            assertTrue(result.contains("Already consolidated"))
            assertTrue(result.contains("--force"))
        }

    @Test
    fun `memory_consolidate returns too few messages`() =
        runBlocking {
            coEvery { consolidationService.consolidate(any(), any()) } returns ConsolidationResult.TooFewMessages

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("memory_consolidate"))

            assertTrue(result.contains("Too few messages"))
        }

    @Test
    fun `memory_consolidate returns disabled`() =
        runBlocking {
            coEvery { consolidationService.consolidate(any(), any()) } returns ConsolidationResult.Disabled

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("memory_consolidate"))

            assertTrue(result.contains("disabled"))
        }
}
