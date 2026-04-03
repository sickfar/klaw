package io.github.klaw.engine.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AgentConfig
import io.github.klaw.engine.context.CompactionRunner
import io.github.klaw.engine.context.CompactionTracker
import io.github.klaw.engine.context.ContextBuilder
import io.github.klaw.engine.context.FileSkillRegistry
import io.github.klaw.engine.context.FileSummaryService
import io.github.klaw.engine.context.KlawWorkspaceLoader
import io.github.klaw.engine.context.SubagentHistoryLoader
import io.github.klaw.engine.context.SummaryRepository
import io.github.klaw.engine.context.stubs.StubToolRegistry
import io.github.klaw.engine.db.BackupService
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.VirtualTableSetup
import io.github.klaw.engine.mcp.McpToolRegistry
import io.github.klaw.engine.memory.AutoRagService
import io.github.klaw.engine.memory.MemoryServiceImpl
import io.github.klaw.engine.message.MessageEmbeddingService
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.tools.SubagentRunRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Path
import java.util.Properties

private val logger = KotlinLogging.logger {}

/**
 * Creates [AgentContext] instances by manually wiring all per-agent services.
 * No Micronaut DI for per-agent services -- all manual constructor calls.
 */
class AgentContextFactory(
    private val shared: SharedServices,
) {
    fun create(
        agentId: String,
        agentConfig: AgentConfig,
        stateDir: String,
        dataDir: String,
        configDir: String,
        conversationsDir: String,
    ): AgentContext {
        logger.info { "Creating agent context: $agentId" }

        // 1. Workspace auto-creation
        val workspaceDir = File(agentConfig.workspace)
        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs()
            logger.info { "Auto-created workspace for agent $agentId: ${workspaceDir.absolutePath}" }
        }

        // 2. Database
        val dbPath = "$stateDir${File.separator}klaw-$agentId.db"
        File(dbPath).parentFile?.mkdirs()
        val props = Properties()
        props["enable_load_extension"] = "true"
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath", props)
        KlawDatabase.Schema.create(driver)
        shared.sqliteVecLoader.loadExtension(driver)
        VirtualTableSetup.createVirtualTables(driver, shared.sqliteVecLoader.isAvailable())
        logger.debug { "Database initialized for agent $agentId: $dbPath" }

        val database = KlawDatabase(driver)

        // 3. Repositories
        val messageRepository = MessageRepository(database)
        val subagentRunRepository = SubagentRunRepository(database)
        val summaryRepository = SummaryRepository(database)

        // 4. SessionManager
        val sessionManager = SessionManager(database)

        // 5. MemoryService
        val config = shared.globalConfig
        val memoryService = MemoryServiceImpl(
            database = database,
            driver = driver,
            embeddingService = shared.embeddingService,
            sqliteVecLoader = shared.sqliteVecLoader,
            searchConfig = config.memory.search,
        )

        // 6. WorkspaceLoader
        val workspacePath = Path.of(agentConfig.workspace)
        val workspaceLoader = KlawWorkspaceLoader(
            workspacePath = workspacePath,
            memoryService = memoryService,
            config = config,
        )
        memoryService.setOnSaveCallback { workspaceLoader.refreshMemorySummary() }
        workspaceLoader.initialize()

        // 7. SkillRegistry
        val skillsDir = "$dataDir${File.separator}skills"
        File(skillsDir).mkdirs()
        val skillRegistry = FileSkillRegistry(
            dataSkillsDir = Path.of(skillsDir),
            workspaceSkillsDir = workspacePath.resolve("skills"),
            workspaceDir = workspacePath,
            dataDir = Path.of(dataDir),
            configDir = Path.of(configDir),
        )
        skillRegistry.discover()

        // 8. Summary + Compaction
        val summaryService = FileSummaryService(summaryRepository)
        val compactionTracker = CompactionTracker()
        val compactionRunner = CompactionRunner(
            summaryRepository = summaryRepository,
            messageRepository = messageRepository,
            llmRouter = shared.llmRouter,
            memoryService = memoryService,
            compactionTracker = compactionTracker,
            config = config,
        )

        // 9. AutoRag
        val autoRagService = AutoRagService(
            driver = driver,
            embeddingService = shared.embeddingService,
            sqliteVecLoader = shared.sqliteVecLoader,
        )

        // 10. MessageEmbedding
        val messageEmbeddingService = MessageEmbeddingService(
            driver = driver,
            embeddingService = shared.embeddingService,
            sqliteVecLoader = shared.sqliteVecLoader,
        )

        // 11. SubagentHistoryLoader
        val subagentHistoryLoader = SubagentHistoryLoader(conversationsDir)

        // 12. McpToolRegistry (empty per-agent, populated later)
        val mcpToolRegistry = McpToolRegistry()

        // 13. BackupService
        val backupService = BackupService(driver, config)

        // 14. ContextBuilder — uses StubToolRegistry until ToolRegistry is wired per-agent
        val contextBuilder = ContextBuilder(
            workspaceLoader = workspaceLoader,
            messageRepository = messageRepository,
            summaryService = summaryService,
            skillRegistry = skillRegistry,
            toolRegistry = StubToolRegistry(),
            config = config,
            autoRagService = autoRagService,
            subagentHistoryLoader = subagentHistoryLoader,
            healthProviderLazy = { shared.engineHealthProvider },
            llmRouter = shared.llmRouter,
        )

        logger.info { "Agent context created: $agentId" }

        return AgentContext(
            agentId = agentId,
            agentConfig = agentConfig,
            database = database,
            driver = driver,
            sessionManager = sessionManager,
            messageRepository = messageRepository,
            memoryService = memoryService,
            workspaceLoader = workspaceLoader,
            skillRegistry = skillRegistry,
            summaryService = summaryService,
            summaryRepository = summaryRepository,
            compactionRunner = compactionRunner,
            compactionTracker = compactionTracker,
            autoRagService = autoRagService,
            messageEmbeddingService = messageEmbeddingService,
            subagentRunRepository = subagentRunRepository,
            subagentHistoryLoader = subagentHistoryLoader,
            mcpToolRegistry = mcpToolRegistry,
            backupService = backupService,
            contextBuilder = contextBuilder,
            // toolRegistry uses StubToolRegistry in contextBuilder until full per-agent wiring
            // scheduler is wired when we set up per-agent Quartz
            // heartbeatRunner is wired when we set up per-agent heartbeat
        )
    }
}
