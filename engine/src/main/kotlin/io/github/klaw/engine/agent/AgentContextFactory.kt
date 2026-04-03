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
        dirs: AgentDirectories,
    ): AgentContext {
        logger.info { "Creating agent context: $agentId" }
        ensureWorkspace(agentId, agentConfig)
        val services = buildServices(agentId, agentConfig, dirs)
        logger.info { "Agent context created: $agentId" }
        return AgentContext(agentId = agentId, agentConfig = agentConfig, services = services)
    }

    private fun buildServices(
        agentId: String,
        agentConfig: AgentConfig,
        dirs: AgentDirectories,
    ): AgentServices {
        val driver = initDatabase(agentId, dirs.stateDir)
        val database = KlawDatabase(driver)
        val messageRepository = MessageRepository(database)
        val subagentRunRepository = SubagentRunRepository(database)
        val summaryRepository = SummaryRepository(database)
        val sessionManager = SessionManager(database)
        val config = shared.globalConfig

        val (memoryService, workspaceLoader) = buildMemoryPipeline(database, driver, agentConfig, config)
        val skillRegistry = buildSkillRegistry(dirs, Path.of(agentConfig.workspace))

        val summaryService = FileSummaryService(summaryRepository)
        val compactionTracker = CompactionTracker()
        val compactionRunner =
            buildCompactionRunner(summaryRepository, messageRepository, memoryService, compactionTracker, config)

        val autoRagService =
            AutoRagService(
                driver = driver,
                embeddingService = shared.embeddingService,
                sqliteVecLoader = shared.sqliteVecLoader,
            )
        val messageEmbeddingService =
            MessageEmbeddingService(
                driver = driver,
                embeddingService = shared.embeddingService,
                sqliteVecLoader = shared.sqliteVecLoader,
            )
        val subagentHistoryLoader = SubagentHistoryLoader(dirs.conversationsDir)
        val contextBuilder =
            buildContextBuilder(
                workspaceLoader,
                messageRepository,
                summaryService,
                skillRegistry,
                autoRagService,
                subagentHistoryLoader,
                config,
            )

        return AgentServices(
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
            mcpToolRegistry = McpToolRegistry(),
            backupService = BackupService(driver, config),
            contextBuilder = contextBuilder,
        )
    }

    private fun buildMemoryPipeline(
        database: KlawDatabase,
        driver: JdbcSqliteDriver,
        agentConfig: AgentConfig,
        config: io.github.klaw.common.config.EngineConfig,
    ): Pair<MemoryServiceImpl, KlawWorkspaceLoader> {
        val memoryService =
            MemoryServiceImpl(
                database = database,
                driver = driver,
                embeddingService = shared.embeddingService,
                sqliteVecLoader = shared.sqliteVecLoader,
                searchConfig = config.memory.search,
            )
        val workspaceLoader =
            KlawWorkspaceLoader(
                workspacePath = Path.of(agentConfig.workspace),
                memoryService = memoryService,
                config = config,
            )
        memoryService.setOnSaveCallback { workspaceLoader.refreshMemorySummary() }
        workspaceLoader.initialize()
        return memoryService to workspaceLoader
    }

    private fun buildCompactionRunner(
        summaryRepository: SummaryRepository,
        messageRepository: MessageRepository,
        memoryService: MemoryServiceImpl,
        compactionTracker: CompactionTracker,
        config: io.github.klaw.common.config.EngineConfig,
    ): CompactionRunner =
        CompactionRunner(
            summaryRepository = summaryRepository,
            messageRepository = messageRepository,
            llmRouter = shared.llmRouter,
            memoryService = memoryService,
            compactionTracker = compactionTracker,
            config = config,
        )

    @Suppress("LongParameterList")
    private fun buildContextBuilder(
        workspaceLoader: KlawWorkspaceLoader,
        messageRepository: MessageRepository,
        summaryService: FileSummaryService,
        skillRegistry: FileSkillRegistry,
        autoRagService: AutoRagService,
        subagentHistoryLoader: SubagentHistoryLoader,
        config: io.github.klaw.common.config.EngineConfig,
    ): ContextBuilder =
        ContextBuilder(
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

    private fun ensureWorkspace(
        agentId: String,
        agentConfig: AgentConfig,
    ) {
        val workspaceDir = File(agentConfig.workspace)
        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs()
            logger.info { "Auto-created workspace for agent $agentId: ${workspaceDir.absolutePath}" }
        }
    }

    private fun initDatabase(
        agentId: String,
        stateDir: String,
    ): JdbcSqliteDriver {
        val dbPath = "$stateDir${File.separator}klaw-$agentId.db"
        File(dbPath).parentFile?.mkdirs()
        val props = Properties()
        props["enable_load_extension"] = "true"
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath", props)
        KlawDatabase.Schema.create(driver)
        shared.sqliteVecLoader.loadExtension(driver)
        VirtualTableSetup.createVirtualTables(driver, shared.sqliteVecLoader.isAvailable())
        logger.debug { "Database initialized for agent $agentId: $dbPath" }
        return driver
    }

    private fun buildSkillRegistry(
        dirs: AgentDirectories,
        workspacePath: java.nio.file.Path,
    ): FileSkillRegistry {
        val skillsDir = "${dirs.dataDir}${File.separator}skills"
        File(skillsDir).mkdirs()
        val skillRegistry =
            FileSkillRegistry(
                dataSkillsDir = Path.of(skillsDir),
                workspaceSkillsDir = workspacePath.resolve("skills"),
                workspaceDir = workspacePath,
                dataDir = Path.of(dirs.dataDir),
                configDir = Path.of(dirs.configDir),
            )
        skillRegistry.discover()
        return skillRegistry
    }
}
