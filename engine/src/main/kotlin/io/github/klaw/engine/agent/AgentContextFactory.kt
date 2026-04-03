package io.github.klaw.engine.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AgentConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.util.approximateTokenCount
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
import io.github.klaw.engine.scheduler.AgentKlawScheduler
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
import io.github.klaw.engine.tools.ShutdownController
import io.github.klaw.engine.tools.SkillTools
import io.github.klaw.engine.tools.SubagentRunRepository
import io.github.klaw.engine.tools.SubagentStatusTools
import io.github.klaw.engine.tools.SubagentTools
import io.github.klaw.engine.tools.ToolRegistryImpl
import io.github.klaw.engine.tools.UtilityTools
import io.github.klaw.engine.tools.WebFetchTool
import io.github.klaw.engine.util.VT
import io.github.klaw.engine.workspace.HeartbeatCallbacks
import io.github.klaw.engine.workspace.HeartbeatJsonlWriter
import io.github.klaw.engine.workspace.HeartbeatPersistence
import io.github.klaw.engine.workspace.HeartbeatRunner
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.nio.file.Path
import java.util.Properties
import java.util.UUID

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

        val scheduler = buildScheduler(agentId, dirs, subagentRunRepository)

        val toolRegistry =
            buildToolRegistry(
                agentConfig,
                dirs,
                config,
                driver,
                database,
                memoryService,
                skillRegistry,
                subagentRunRepository,
                mcpToolRegistry,
                scheduler,
            )

        val contextBuilder =
            buildContextBuilder(
                workspaceLoader,
                messageRepository,
                summaryService,
                skillRegistry,
                toolRegistry,
                autoRagService,
                subagentHistoryLoader,
                config,
            )

        val (heartbeatRunner, heartbeatScope) =
            buildHeartbeatRunner(
                agentId,
                agentConfig,
                dirs,
                config,
                sessionManager,
                messageRepository,
                messageEmbeddingService,
                toolRegistry,
                workspaceLoader,
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
            scheduler = scheduler,
            heartbeatRunner = heartbeatRunner,
            heartbeatScope = heartbeatScope,
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
        scheduler: AgentKlawScheduler,
    ): ToolRegistryImpl {
        val workspacePath = Path.of(agentConfig.workspace)
        val fileTools = buildFileTools(workspacePath, dirs, config)
        val docsTools = buildDocsTools(database, driver, config)
        val sandboxExecTool = buildSandboxExecTool(workspacePath, dirs, config)
        val infraTools = buildInfraTools(config, dirs, driver, workspacePath, subagentRunRepository)

        return ToolRegistryImpl(
            fileTools = fileTools,
            skillTools = SkillTools(skillRegistry),
            memoryTools = MemoryTools(memoryService),
            docsTools = docsTools,
            scheduleTools = ScheduleTools(scheduler),
            subagentTools = infraTools.subagentTools,
            utilityTools = infraTools.utilityTools,
            sandboxExecTool = sandboxExecTool,
            hostExecTool = infraTools.hostExecTool,
            configTools = infraTools.configTools,
            historyTools = infraTools.historyTools,
            engineHealthTools = EngineHealthTools(shared.engineHealthProvider),
            subagentStatusTools = infraTools.subagentStatusTools,
            webFetchTool = shared.webFetchTool ?: WebFetchTool(config.web.fetch),
            webSearchTool = shared.webSearchTool ?: buildWebSearchTool(config),
            pdfReadTool = PdfReadTool(fileTools, config.documents),
            mdToPdfTool = MdToPdfTool(fileTools, config.documents),
            imageAnalyzeTool = infraTools.imageAnalyzeTool,
            config = config,
            mcpToolRegistry = mcpToolRegistry,
        )
    }

    private data class InfraTools(
        val hostExecTool: HostExecTool,
        val configTools: ConfigTools,
        val historyTools: HistoryTools,
        val subagentTools: SubagentTools,
        val utilityTools: UtilityTools,
        val subagentStatusTools: SubagentStatusTools,
        val imageAnalyzeTool: ImageAnalyzeTool,
    )

    private fun buildInfraTools(
        config: EngineConfig,
        dirs: AgentDirectories,
        driver: JdbcSqliteDriver,
        workspacePath: Path,
        subagentRunRepository: SubagentRunRepository,
    ): InfraTools =
        InfraTools(
            hostExecTool =
                HostExecTool(
                    config = config.hostExecution,
                    llmRouter = shared.llmRouter,
                    approvalService = shared.approvalService ?: error("ApprovalService required"),
                ),
            configTools =
                shared.shutdownController?.let { ConfigTools(dirs.configDir, it) }
                    ?: ConfigTools(dirs.configDir, ShutdownController()),
            historyTools =
                HistoryTools(
                    embeddingService = shared.embeddingService,
                    driver = driver,
                    sqliteVecLoader = shared.sqliteVecLoader,
                ),
            subagentTools =
                SubagentTools(
                    processorProvider = shared.processorProvider ?: error("MessageProcessor provider required"),
                    repository = subagentRunRepository,
                ),
            utilityTools =
                UtilityTools(
                    socketServerProvider = shared.socketServerProvider ?: error("EngineSocketServer provider required"),
                ),
            subagentStatusTools =
                SubagentStatusTools(
                    repository = subagentRunRepository,
                    activeSubagentJobs = shared.activeSubagentJobs ?: error("ActiveSubagentJobs required"),
                ),
            imageAnalyzeTool =
                ImageAnalyzeTool(
                    llmRouter = shared.llmRouter,
                    config = config.vision,
                    allowedPaths = listOf(workspacePath),
                ),
        )

    private fun buildDocsTools(
        database: KlawDatabase,
        driver: JdbcSqliteDriver,
        config: EngineConfig,
    ): DocsTools {
        val docsService = shared.docsService
        return if (docsService != null) {
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
                    docker =
                        io.github.klaw.engine.tools
                            .ProcessDockerClient(),
                ),
                config.codeExecution,
            )
        }
    }

    private fun buildWebSearchTool(config: EngineConfig): io.github.klaw.engine.tools.WebSearchTool {
        val searchConfig = config.web.search
        val provider =
            when (searchConfig.provider) {
                "brave" -> {
                    io.github.klaw.engine.tools
                        .BraveSearchProvider(searchConfig)
                }

                "tavily" -> {
                    io.github.klaw.engine.tools
                        .TavilySearchProvider(searchConfig)
                }

                else -> {
                    error("Unknown web search provider: ${searchConfig.provider}")
                }
            }
        return io.github.klaw.engine.tools
            .WebSearchTool(searchConfig, provider)
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

    private fun buildScheduler(
        agentId: String,
        dirs: AgentDirectories,
        subagentRunRepository: SubagentRunRepository,
    ): AgentKlawScheduler {
        val appCtx = shared.applicationContext ?: error("ApplicationContext required for per-agent scheduler")
        val dbPath = "${dirs.stateDir}${File.separator}scheduler-$agentId.db"
        return AgentKlawScheduler(
            dbPath = dbPath,
            applicationContext = appCtx,
            subagentRunRepository = subagentRunRepository,
            agentId = agentId,
        )
    }

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

    @Suppress("LongParameterList")
    private fun buildHeartbeatRunner(
        agentId: String,
        agentConfig: AgentConfig,
        dirs: AgentDirectories,
        config: EngineConfig,
        sessionManager: SessionManager,
        messageRepository: MessageRepository,
        messageEmbeddingService: MessageEmbeddingService,
        toolRegistry: ToolRegistryImpl,
        workspaceLoader: KlawWorkspaceLoader,
    ): Pair<HeartbeatRunner?, CoroutineScope?> {
        val resolved = resolveHeartbeatPrereqs(agentId, agentConfig, config) ?: return null to null

        val scope = CoroutineScope(Dispatchers.VT + SupervisorJob())
        val callbacks = buildHeartbeatCallbacks(sessionManager, resolved.injectInto, resolved.approvalBridge)
        val persistence = buildHeartbeatPersistence(dirs, config, messageRepository, messageEmbeddingService)
        val effectiveConfig =
            config.copy(
                heartbeat =
                    config.heartbeat.copy(
                        channel = resolved.channel,
                        injectInto = resolved.injectInto,
                        model = resolved.model,
                    ),
            )

        val runner =
            HeartbeatRunner(
                config = effectiveConfig,
                callbacks = callbacks,
                toolExecutor =
                    io.github.klaw.engine.tools
                        .DispatchingToolExecutor(toolRegistry),
                workspaceLoader = workspaceLoader,
                toolRegistry = toolRegistry,
                workspacePath = Path.of(agentConfig.workspace),
                maxToolCallRounds = config.processing.maxToolCallRounds,
                persistence = persistence,
                scope = scope,
            )

        resolved.taskScheduler.scheduleAtFixedRate(resolved.interval, resolved.interval) { runner.triggerHeartbeat() }
        logger.info { "Heartbeat scheduled for agent $agentId — first run in ${resolved.interval}" }

        return runner to scope
    }

    private data class HeartbeatPrereqs(
        val interval: java.time.Duration,
        val taskScheduler: io.micronaut.scheduling.TaskScheduler,
        val approvalBridge: io.github.klaw.engine.workspace.HeartbeatApprovalBridge,
        val channel: String?,
        val injectInto: String?,
        val model: String,
    )

    private fun resolveHeartbeatPrereqs(
        agentId: String,
        agentConfig: AgentConfig,
        config: EngineConfig,
    ): HeartbeatPrereqs? {
        val override = agentConfig.heartbeat
        if (override?.enabled == false) {
            logger.debug { "Heartbeat disabled for agent $agentId" }
            return null
        }
        val interval = resolveHeartbeatInterval(agentId, override, config) ?: return null
        val (taskScheduler, approvalBridge) = resolveHeartbeatDeps(agentId) ?: return null

        val channel = override?.channel ?: config.heartbeat.channel
        val injectInto = override?.injectInto ?: config.heartbeat.injectInto
        val model = override?.model ?: config.heartbeat.model ?: config.routing.default
        logger.info {
            "Heartbeat starting for agent $agentId: interval=$interval, model=$model, " +
                "channel=${channel ?: "<not set>"}, injectInto=${injectInto ?: "<not set>"}"
        }
        return HeartbeatPrereqs(interval, taskScheduler, approvalBridge, channel, injectInto, model)
    }

    private fun resolveHeartbeatInterval(
        agentId: String,
        override: io.github.klaw.common.config.AgentHeartbeatOverride?,
        config: EngineConfig,
    ): java.time.Duration? {
        val interval = HeartbeatRunner.parseInterval(override?.interval ?: config.heartbeat.interval)
        if (interval == null) logger.info { "Heartbeat disabled for agent $agentId (interval=off)" }
        return interval
    }

    private fun resolveHeartbeatDeps(
        agentId: String,
    ): Pair<io.micronaut.scheduling.TaskScheduler, io.github.klaw.engine.workspace.HeartbeatApprovalBridge>? {
        val taskScheduler =
            shared.taskScheduler ?: run {
                logger.warn { "TaskScheduler not available — per-agent heartbeat skipped for $agentId" }
                return null
            }
        val approvalBridge =
            shared.heartbeatApprovalBridge ?: run {
                logger.warn { "HeartbeatApprovalBridge not available — per-agent heartbeat skipped for $agentId" }
                return null
            }
        return taskScheduler to approvalBridge
    }

    private fun buildHeartbeatCallbacks(
        sessionManager: SessionManager,
        injectInto: String?,
        approvalBridge: io.github.klaw.engine.workspace.HeartbeatApprovalBridge,
    ): HeartbeatCallbacks =
        HeartbeatCallbacks(
            chat = shared.llmRouter::chat,
            getOrCreateSession = sessionManager::getOrCreate,
            denyPendingApprovals = {
                if (injectInto != null) approvalBridge.denyPendingForChatId(injectInto) else emptyList()
            },
            sendDismiss = { id -> approvalBridge.sendDismiss(id) },
        )

    private fun buildHeartbeatPersistence(
        dirs: AgentDirectories,
        config: EngineConfig,
        messageRepository: MessageRepository,
        messageEmbeddingService: MessageEmbeddingService,
    ): HeartbeatPersistence {
        val embeddingScope = CoroutineScope(Dispatchers.VT + SupervisorJob())
        return HeartbeatPersistence(
            jsonlWriter = HeartbeatJsonlWriter(Path.of(dirs.conversationsDir)),
            persistDelivered = { ch, chatId, content ->
                val rowId =
                    messageRepository.saveAndGetRowId(
                        id = UUID.randomUUID().toString(),
                        channel = ch,
                        chatId = chatId,
                        role = "assistant",
                        type = "text",
                        content = content,
                        tokens = approximateTokenCount(content),
                    )
                messageEmbeddingService.embedAsync(
                    rowId,
                    "assistant",
                    "text",
                    content,
                    config.memory.autoRag,
                    embeddingScope,
                )
            },
            pushToGateway = { msg -> shared.socketServerProvider?.get()?.pushToGateway(msg) },
        )
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
