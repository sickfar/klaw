package io.github.klaw.engine.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AgentConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.engine.context.CompactionRunner
import io.github.klaw.engine.context.CompactionTracker
import io.github.klaw.engine.context.ContextBuilder
import io.github.klaw.engine.context.FileSkillRegistry
import io.github.klaw.engine.context.FileSummaryService
import io.github.klaw.engine.context.KlawWorkspaceLoader
import io.github.klaw.engine.context.SubagentHistoryLoader
import io.github.klaw.engine.context.SummaryRepository
import io.github.klaw.engine.db.BackupService
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.VirtualTableSetup
import io.github.klaw.engine.docs.DocsServiceImpl
import io.github.klaw.engine.mcp.McpToolRegistry
import io.github.klaw.engine.memory.AutoRagService
import io.github.klaw.engine.memory.MemoryServiceImpl
import io.github.klaw.engine.message.MessageEmbeddingService
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.tools.ConfigTools
import io.github.klaw.engine.tools.DocsTools
import io.github.klaw.engine.tools.EngineHealthTools
import io.github.klaw.engine.tools.FileTools
import io.github.klaw.engine.tools.HistoryTools
import io.github.klaw.engine.tools.HostExecTool
import io.github.klaw.engine.tools.ImageAnalyzeTool
import io.github.klaw.engine.tools.MdToPdfTool
import io.github.klaw.engine.tools.MemoryTools
import io.github.klaw.engine.tools.PdfReadTool
import io.github.klaw.engine.tools.SandboxExecTool
import io.github.klaw.engine.tools.SandboxManager
import io.github.klaw.engine.tools.ScheduleTools
import io.github.klaw.engine.tools.SkillTools
import io.github.klaw.engine.tools.SubagentRunRepository
import io.github.klaw.engine.tools.SubagentStatusTools
import io.github.klaw.engine.tools.SubagentTools
import io.github.klaw.engine.tools.ToolRegistryImpl
import io.github.klaw.engine.tools.UtilityTools
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

    @Suppress("LongMethod")
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
        val mcpToolRegistry = McpToolRegistry()

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

        val toolRegistry =
            buildToolRegistry(
                agentConfig, dirs, config, driver, database, memoryService,
                skillRegistry, subagentRunRepository, mcpToolRegistry,
            )

        val contextBuilder =
            buildContextBuilder(
                workspaceLoader, messageRepository, summaryService,
                skillRegistry, toolRegistry, autoRagService, subagentHistoryLoader, config,
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
            mcpToolRegistry = mcpToolRegistry,
            backupService = BackupService(driver, config),
            contextBuilder = contextBuilder,
            toolRegistry = toolRegistry,
        )
    }

    @Suppress("LongParameterList")
    private fun buildToolRegistry(
        agentConfig: AgentConfig,
        dirs: AgentDirectories,
        config: EngineConfig,
        driver: JdbcSqliteDriver,
        database: KlawDatabase,
        memoryService: MemoryServiceImpl,
        skillRegistry: FileSkillRegistry,
        subagentRunRepository: SubagentRunRepository,
        mcpToolRegistry: McpToolRegistry,
    ): ToolRegistryImpl {
        val workspacePath = Path.of(agentConfig.workspace)
        val fileTools = buildFileTools(workspacePath, dirs, config)
        val docsService = shared.docsService
        val docsTools =
            if (docsService != null) {
                DocsTools(docsService)
            } else {
                DocsTools(
                    DocsServiceImpl(
                        embeddingService = shared.embeddingService,
                        database = database,
                        driver = driver,
                        sqliteVecLoader = shared.sqliteVecLoader,
                        config = config,
                    ),
                )
            }

        val sandboxExecTool = buildSandboxExecTool(workspacePath, dirs, config)
        val hostExecTool =
            HostExecTool(
                config = config.hostExecution,
                llmRouter = shared.llmRouter,
                approvalService = shared.approvalService ?: error("ApprovalService required for per-agent tools"),
            )
        val configTools =
            shared.shutdownController?.let { ConfigTools(dirs.configDir, it) }
                ?: ConfigTools(dirs.configDir, io.github.klaw.engine.tools.ShutdownController())
        val historyTools =
            HistoryTools(
                embeddingService = shared.embeddingService,
                driver = driver,
                sqliteVecLoader = shared.sqliteVecLoader,
            )
        val subagentTools =
            SubagentTools(
                processorProvider = shared.processorProvider ?: error("MessageProcessor provider required"),
                repository = subagentRunRepository,
            )
        val utilityTools =
            UtilityTools(
                socketServerProvider = shared.socketServerProvider ?: error("EngineSocketServer provider required"),
            )
        val subagentStatusTools =
            SubagentStatusTools(
                repository = subagentRunRepository,
                activeSubagentJobs = shared.activeSubagentJobs ?: error("ActiveSubagentJobs required"),
            )
        val engineHealthTools = EngineHealthTools(shared.engineHealthProvider)
        val imageAnalyzeTool =
            ImageAnalyzeTool(
                llmRouter = shared.llmRouter,
                config = config.vision,
                allowedPaths = listOf(workspacePath),
            )

        return ToolRegistryImpl(
            fileTools = fileTools,
            skillTools = SkillTools(skillRegistry),
            memoryTools = MemoryTools(memoryService),
            docsTools = docsTools,
            scheduleTools = ScheduleTools(shared.scheduler ?: error("KlawScheduler required")),
            subagentTools = subagentTools,
            utilityTools = utilityTools,
            sandboxExecTool = sandboxExecTool,
            hostExecTool = hostExecTool,
            configTools = configTools,
            historyTools = historyTools,
            engineHealthTools = engineHealthTools,
            subagentStatusTools = subagentStatusTools,
            webFetchTool = shared.webFetchTool ?: io.github.klaw.engine.tools.WebFetchTool(config.web.fetch),
            webSearchTool = shared.webSearchTool ?: buildWebSearchTool(config),
            pdfReadTool = PdfReadTool(fileTools, config.documents),
            mdToPdfTool = MdToPdfTool(fileTools, config.documents),
            imageAnalyzeTool = imageAnalyzeTool,
            config = config,
            mcpToolRegistry = mcpToolRegistry,
        )
    }

    private fun buildFileTools(
        workspacePath: Path,
        dirs: AgentDirectories,
        config: EngineConfig,
    ): FileTools {
        val allowedPaths =
            listOf(
                workspacePath,
                Path.of(dirs.stateDir),
                Path.of(dirs.dataDir),
                Path.of(dirs.configDir),
            )
        val placeholders =
            mapOf(
                "\$WORKSPACE" to workspacePath.toString(),
                "\$STATE" to dirs.stateDir,
                "\$DATA" to dirs.dataDir,
                "\$CONFIG" to dirs.configDir,
            )
        return FileTools(allowedPaths, config.files.maxFileSizeBytes, placeholders)
    }

    private fun buildSandboxExecTool(
        workspacePath: Path,
        dirs: AgentDirectories,
        config: EngineConfig,
    ): SandboxExecTool {
        val docker = shared.dockerClient
        return if (docker != null) {
            val sandboxManager =
                SandboxManager(
                    config = config.codeExecution,
                    docker = docker,
                    workspacePath = workspacePath.toString(),
                    hostWorkspacePath = System.getenv("KLAW_HOST_WORKSPACE"),
                    stateDir = "${dirs.stateDir}${File.separator}run",
                )
            SandboxExecTool(sandboxManager, config.codeExecution)
        } else {
            SandboxExecTool(
                SandboxManager(
                    config = config.codeExecution,
                    docker = io.github.klaw.engine.tools.ProcessDockerClient(),
                ),
                config.codeExecution,
            )
        }
    }

    private fun buildWebSearchTool(config: EngineConfig): io.github.klaw.engine.tools.WebSearchTool {
        val searchConfig = config.web.search
        val provider =
            when (searchConfig.provider) {
                "brave" -> io.github.klaw.engine.tools.BraveSearchProvider(searchConfig)
                "tavily" -> io.github.klaw.engine.tools.TavilySearchProvider(searchConfig)
                else -> error("Unknown web search provider: ${searchConfig.provider}")
            }
        return io.github.klaw.engine.tools.WebSearchTool(searchConfig, provider)
    }

    private fun buildMemoryPipeline(
        database: KlawDatabase,
        driver: JdbcSqliteDriver,
        agentConfig: AgentConfig,
        config: EngineConfig,
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
        config: EngineConfig,
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
        toolRegistry: ToolRegistryImpl,
        autoRagService: AutoRagService,
        subagentHistoryLoader: SubagentHistoryLoader,
        config: EngineConfig,
    ): ContextBuilder =
        ContextBuilder(
            workspaceLoader = workspaceLoader,
            messageRepository = messageRepository,
            summaryService = summaryService,
            skillRegistry = skillRegistry,
            toolRegistry = toolRegistry,
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
        workspacePath: Path,
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
