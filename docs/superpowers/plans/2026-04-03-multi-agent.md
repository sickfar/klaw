# Multi-Agent Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support multiple fully isolated AI agents within a single Engine+Gateway process, sharing only LLM providers and embedding infrastructure.

**Architecture:** AgentContext Registry pattern — a singleton `AgentRegistry` holds `Map<String, AgentContext>`. Each `AgentContext` bundles per-agent state (DB, workspace, memory, scheduler, sessions). Current `@Singleton`/`@Factory` beans for per-agent services are removed from Micronaut DI and constructed imperatively by `AgentContextFactory`. Gateway tags every message with `agentId` based on channel config. CLI defaults to agent `"default"`.

**Tech Stack:** Kotlin 2.3.10, Micronaut 4.10.7, kotlinx-serialization, SqlDelight, Quartz, bash+jq (migration script)

**Spec:** `docs/superpowers/specs/2026-04-03-multi-agent-design.md`

**Constraints from CLAUDE.md:**
- TDD: tests with edge cases first, then implementation
- After each task: lang-tools MCP cleanup (imports + dead code for whole project)
- Detekt violations in production code MUST be fixed (no @Suppress)
- Never use Thread.sleep in tests — use event-based waiting
- Logging: lambda form only, never log sensitive data
- Documentation update phase required
- E2E tests for affected scenarios

---

## File Structure

### New Files (Engine)
- `engine/src/main/kotlin/io/github/klaw/engine/agent/AgentContext.kt` — per-agent state bundle
- `engine/src/main/kotlin/io/github/klaw/engine/agent/AgentContextFactory.kt` — imperative wiring of per-agent services
- `engine/src/main/kotlin/io/github/klaw/engine/agent/AgentRegistry.kt` — singleton registry `Map<String, AgentContext>`
- `engine/src/main/kotlin/io/github/klaw/engine/agent/SharedServices.kt` — holder for cross-agent singletons
- `engine/src/test/kotlin/io/github/klaw/engine/agent/AgentContextFactoryTest.kt`
- `engine/src/test/kotlin/io/github/klaw/engine/agent/AgentRegistryTest.kt`

### New Files (Common)
- No new files — `AgentConfig` goes into existing `EngineConfig.kt`

### New Files (Scripts)
- `scripts/migrate-to-multiagent.sh` — bash+jq migration script

### Modified Files (Common)
- `common/src/commonMain/kotlin/io/github/klaw/common/config/EngineConfig.kt` — add `AgentConfig`, `AgentLimitsConfig`, `_defaults` support, make many fields optional with defaults
- `common/src/commonMain/kotlin/io/github/klaw/common/config/GatewayConfig.kt` — restructure channels to `Map<Type, Map<Name, Config>>`, add `agentId`
- `common/src/commonMain/kotlin/io/github/klaw/common/config/ConfigParser.kt` — add merge logic for `_defaults`
- `common/src/commonMain/kotlin/io/github/klaw/common/protocol/SocketProtocol.kt` — add `agentId` to all message types
- `common/src/commonTest/` — update all config parsing and protocol tests

### Modified Files (Engine)
- `engine/src/main/kotlin/io/github/klaw/engine/EngineLifecycle.kt` — orchestrate AgentRegistry startup/shutdown
- `engine/src/main/kotlin/io/github/klaw/engine/config/EngineConfigFactory.kt` — unchanged (still loads global config)
- `engine/src/main/kotlin/io/github/klaw/engine/db/DatabaseFactory.kt` — remove @Singleton/@Factory, extract creation logic to static/companion method
- `engine/src/main/kotlin/io/github/klaw/engine/message/MessageProcessor.kt` — resolve AgentContext per message, remove direct per-agent singleton deps
- `engine/src/main/kotlin/io/github/klaw/engine/context/ContextBuilder.kt` — remove @Singleton, become per-agent instance
- `engine/src/main/kotlin/io/github/klaw/engine/session/SessionManager.kt` — remove @Singleton
- `engine/src/main/kotlin/io/github/klaw/engine/memory/MemoryServiceImplFactory.kt` — remove @Factory, extract to static method
- `engine/src/main/kotlin/io/github/klaw/engine/scheduler/KlawSchedulerImpl.kt` — remove @Singleton, take agentId for DB path
- `engine/src/main/kotlin/io/github/klaw/engine/workspace/HeartbeatRunnerFactory.kt` — iterate agents instead of single workspace
- `engine/src/main/kotlin/io/github/klaw/engine/socket/EngineSocketServer.kt` — pass agentId from messages to handler
- `engine/src/main/kotlin/io/github/klaw/engine/socket/CliCommandDispatcher.kt` — resolve AgentContext by agentId from CliRequestMessage
- `engine/src/main/kotlin/io/github/klaw/engine/socket/SocketFactory.kt` — adjust DI wiring
- `engine/src/main/kotlin/io/github/klaw/engine/tools/ToolExecutor.kt` — per-agent tool registry from AgentContext
- `engine/src/main/kotlin/io/github/klaw/engine/memory/AutoRagService.kt` — remove @Singleton
- `engine/src/main/kotlin/io/github/klaw/engine/message/MessageRepository.kt` — remove @Singleton
- All existing engine tests — adapt to AgentContext-based wiring

### Modified Files (Gateway)
- `gateway/src/main/kotlin/io/github/klaw/gateway/GatewayLifecycle.kt` — create multiple channel instances from new config structure
- `gateway/src/main/kotlin/io/github/klaw/gateway/channel/TelegramChannel.kt` — accept channelName + agentId, populate in messages
- `gateway/src/main/kotlin/io/github/klaw/gateway/channel/DiscordChannel.kt` — same
- `gateway/src/main/kotlin/io/github/klaw/gateway/channel/LocalWsChannel.kt` — same
- `gateway/src/main/kotlin/io/github/klaw/gateway/socket/GatewayOutboundHandler.kt` — route by channel name instead of chatId prefix
- `gateway/src/main/kotlin/io/github/klaw/gateway/socket/GatewaySocketFactory.kt` — adapt to new config
- `gateway/src/main/kotlin/io/github/klaw/gateway/socket/ConversationJsonlWriter.kt` — scope dirs by agentId

### Modified Files (CLI)
- `cli/src/nativeMain/kotlin/io/github/klaw/cli/command/ChatCommand.kt` — add `--agent` option
- `cli/src/nativeMain/kotlin/io/github/klaw/cli/command/MemoryCommand.kt` — add `--agent`
- `cli/src/nativeMain/kotlin/io/github/klaw/cli/command/SessionsCommand.kt` — add `--agent`
- `cli/src/nativeMain/kotlin/io/github/klaw/cli/command/ScheduleCommand.kt` — add `--agent`
- `cli/src/nativeMain/kotlin/io/github/klaw/cli/command/StatusCommand.kt` — show all agents
- `cli/src/nativeMain/kotlin/io/github/klaw/cli/command/DoctorCommand.kt` — validate all agents
- `cli/src/nativeMain/kotlin/io/github/klaw/cli/init/ConfigTemplates.kt` — generate new config format with agents section
- `cli/src/nativeMain/kotlin/io/github/klaw/cli/init/InitWizard.kt` — create agents array

---

## Task 1: Config Schema — EngineConfig Changes (common module)

**Files:**
- Modify: `common/src/commonMain/kotlin/io/github/klaw/common/config/EngineConfig.kt`
- Test: `common/src/commonTest/kotlin/io/github/klaw/common/config/ConfigParsingTest.kt`

### Step 1.1: Write failing tests for new config structure

- [ ] **Write test: minimal multi-agent config parses**

```kotlin
@Test
fun `parse minimal multi-agent engine config`() {
    val json = """
    {
      "providers": {
        "deepseek": { "apiKey": "sk-test", "models": ["deepseek-chat"] }
      },
      "routing": { "default": "deepseek/deepseek-chat" },
      "agents": {
        "default": { "workspace": "/tmp/klaw/default" }
      }
    }
    """.trimIndent()
    val config = parseEngineConfig(json)
    assertEquals(1, config.agents.size)
    assertEquals("/tmp/klaw/default", config.agents["default"]?.workspace)
}
```

- [ ] **Write test: full multi-agent config with overrides**

```kotlin
@Test
fun `parse multi-agent config with per-agent overrides`() {
    val json = """
    {
      "providers": { "ds": { "apiKey": "k" } },
      "routing": { "default": "ds/m" },
      "agents": {
        "default": {
          "workspace": "/tmp/a",
          "routing": { "default": "ds/other" },
          "processing": { "temperature": 0.3, "slidingWindow": 50 }
        }
      }
    }
    """.trimIndent()
    val config = parseEngineConfig(json)
    val agent = config.agents["default"]!!
    assertEquals("ds/other", agent.routing?.default)
    assertEquals(0.3, agent.processing?.temperature)
    assertEquals(50, agent.processing?.slidingWindow)
}
```

- [ ] **Write test: _defaults inheritance**

```kotlin
@Test
fun `_defaults key is parsed but not treated as agent`() {
    val json = """
    {
      "providers": { "ds": { "apiKey": "k" } },
      "routing": { "default": "ds/m" },
      "agents": {
        "_defaults": { "heartbeat": { "enabled": true } },
        "default": { "workspace": "/tmp/a" }
      }
    }
    """.trimIndent()
    val config = parseEngineConfig(json)
    assertNotNull(config.agentDefaults)
    assertTrue(config.agentDefaults!!.heartbeat?.enabled == true)
    assertEquals(1, config.agents.size) // _defaults is NOT counted as agent
    assertNull(config.agents["_defaults"])
}
```

- [ ] **Write test: disabled agent**

```kotlin
@Test
fun `parse disabled agent config`() {
    val json = """
    {
      "providers": { "ds": { "apiKey": "k" } },
      "routing": { "default": "ds/m" },
      "agents": {
        "default": { "workspace": "/tmp/a" },
        "work": { "enabled": false, "workspace": "/tmp/b" }
      }
    }
    """.trimIndent()
    val config = parseEngineConfig(json)
    assertFalse(config.agents["work"]!!.enabled)
}
```

- [ ] **Write test: agent limits**

```kotlin
@Test
fun `parse agent with resource limits`() {
    val json = """
    {
      "providers": { "ds": { "apiKey": "k" } },
      "routing": { "default": "ds/m" },
      "agents": {
        "default": {
          "workspace": "/tmp/a",
          "limits": { "maxConcurrentRequests": 2, "maxMessagesPerMinute": 20 }
        }
      }
    }
    """.trimIndent()
    val config = parseEngineConfig(json)
    assertEquals(2, config.agents["default"]!!.limits.maxConcurrentRequests)
    assertEquals(20, config.agents["default"]!!.limits.maxMessagesPerMinute)
}
```

- [ ] **Run tests to verify they fail**

Run: `./gradlew :common:jvmTest --tests '*ConfigParsingTest*' -x detekt`
Expected: FAIL — `agents` field doesn't exist yet

### Step 1.2: Add defaults to currently-required fields

- [ ] **Make ProcessingConfig fields optional with defaults**

In `EngineConfig.kt`, change `ProcessingConfig`:
```kotlin
@Serializable
data class ProcessingConfig(
    val debounceMs: Long = 800,
    val maxConcurrentLlm: Int = 3,
    val maxToolCallRounds: Int = 50,
    val maxToolOutputChars: Int = 50_000,
    val maxDebounceEntries: Int = 1_000,
    val subagentTimeoutMs: Long = 300_000L,
    val streaming: StreamingConfig = StreamingConfig(),
)
```

- [ ] **Make MemoryConfig.search.topK default to 10**

```kotlin
@Serializable
data class SearchConfig(
    val topK: Int = 10,
    val mmr: MmrConfig = MmrConfig(),
    val temporalDecay: TemporalDecayConfig = TemporalDecayConfig(),
)
```

- [ ] **Make ContextConfig fields optional with defaults**

```kotlin
@Serializable
data class ContextConfig(
    val tokenBudget: Int? = null,
    val subagentHistory: Int = 10,
)
```

- [ ] **Make RoutingConfig.tasks fall back to default**

```kotlin
@Serializable
data class RoutingConfig(
    val default: String,
    val fallback: List<String> = emptyList(),
    val tasks: TaskRoutingConfig = TaskRoutingConfig(),
)

@Serializable
data class TaskRoutingConfig(
    val summarization: String = "",  // empty = use routing.default
    val subagent: String = "",       // empty = use routing.default
    val consolidation: String = "",
)
```

- [ ] **Make MemoryConfig fully optional**

```kotlin
@Serializable
data class MemoryConfig(
    val embedding: EmbeddingConfig = EmbeddingConfig(),
    val chunking: ChunkingConfig = ChunkingConfig(),
    val search: SearchConfig = SearchConfig(),
    val injectMemoryMap: Boolean = false,
    val mapMaxCategories: Int = 10,
    val autoRag: AutoRagConfig = AutoRagConfig(),
    val compaction: CompactionConfig = CompactionConfig(),
    val consolidation: DailyConsolidationConfig = DailyConsolidationConfig(),
)
```

- [ ] **Make ChunkingConfig defaults match init values**

```kotlin
@Serializable
data class ChunkingConfig(
    val size: Int = 512,
    val overlap: Int = 64,
)
```

- [ ] **Make HeartbeatConfig default interval PT1H**

```kotlin
@Serializable
data class HeartbeatConfig(
    val interval: String = "PT1H",
    // ... rest unchanged
)
```

- [ ] **Make top-level EngineConfig fields with defaults**

```kotlin
@Serializable
data class EngineConfig(
    // ... existing fields ...
    val memory: MemoryConfig = MemoryConfig(),
    val context: ContextConfig = ContextConfig(),
    val processing: ProcessingConfig = ProcessingConfig(),
    // agents (added in next step)
)
```

### Step 1.3: Add AgentConfig and agents field

- [ ] **Add AgentConfig, AgentLimitsConfig, and per-agent override classes**

```kotlin
@Serializable
data class AgentConfig(
    val enabled: Boolean = true,
    val workspace: String,
    val routing: AgentRoutingOverride? = null,
    val processing: AgentProcessingOverride? = null,
    val memory: AgentMemoryOverride? = null,
    val heartbeat: AgentHeartbeatOverride? = null,
    val tools: AgentToolsConfig? = null,
    val mcp: McpConfig? = null,
    val limits: AgentLimitsConfig = AgentLimitsConfig(),
    val vision: AgentVisionOverride? = null,
)

@Serializable
data class AgentRoutingOverride(
    val default: String? = null,
    val tasks: TaskRoutingConfig? = null,
)

@Serializable
data class AgentProcessingOverride(
    val slidingWindow: Int? = null,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

@Serializable
data class AgentMemoryOverride(
    val consolidation: DailyConsolidationConfig? = null,
    val chunking: ChunkingConfig? = null,
    val search: SearchConfig? = null,
    val autoRag: AutoRagConfig? = null,
)

@Serializable
data class AgentHeartbeatOverride(
    val enabled: Boolean? = null,
    val interval: String? = null,
    val cron: String? = null,
    val model: String? = null,
    val channel: String? = null,
)

@Serializable
data class AgentToolsConfig(
    val sandbox: CodeExecutionConfig? = null,
    val hostExec: HostExecutionConfig? = null,
)

@Serializable
data class AgentVisionOverride(
    val enabled: Boolean? = null,
    val model: String? = null,
)

@Serializable
data class AgentLimitsConfig(
    val maxConcurrentRequests: Int = 0,
    val maxMessagesPerMinute: Int = 0,
)
```

- [ ] **Add agents field and _defaults support to EngineConfig**

The `agents` raw map includes `_defaults` key. Add a custom serializer or post-parse logic:

```kotlin
@Serializable
data class EngineConfig(
    // ... existing fields ...
    @SerialName("agents")
    private val rawAgents: Map<String, AgentConfig>,
) {
    @Transient
    val agentDefaults: AgentConfig? = rawAgents["_defaults"]

    @Transient
    val agents: Map<String, AgentConfig> = rawAgents.filterKeys { it != "_defaults" }
}
```

Note: `_defaults` has `workspace` required in `AgentConfig`. Solutions:
- Make `workspace` optional with default `""` and validate non-empty only for real agents (not `_defaults`)
- Or use `JsonElement` for `_defaults` and deserialize separately

Recommended: make `workspace` default `""`, add `init {}` validation that skips `_defaults`:

```kotlin
@Serializable
data class AgentConfig(
    val enabled: Boolean = true,
    val workspace: String = "",  // validated non-empty for real agents only
    // ...
)
```

Validation in `EngineConfig.init {}`:
```kotlin
init {
    require(agents.isNotEmpty()) { "At least one agent must be configured" }
    agents.forEach { (id, agent) ->
        require(agent.workspace.isNotBlank()) { "Agent '$id' must have a workspace path" }
    }
}
```

### Step 1.4: Run tests and fix

- [ ] **Run all config parsing tests**

Run: `./gradlew :common:jvmTest --tests '*ConfigParsingTest*' -x detekt`
Expected: All new tests PASS

- [ ] **Fix any existing tests broken by the schema change**

Existing tests that parse `EngineConfig` without `agents` field will fail. Update them to include minimal `agents` section:
```json
"agents": { "default": { "workspace": "/tmp/test" } }
```

- [ ] **Run full common module tests**

Run: `./gradlew :common:jvmTest -x detekt`
Expected: All PASS

### Step 1.5: Commit

- [ ] **Commit**

```bash
git add common/src/
git commit -m "feat(config): add multi-agent config schema with AgentConfig, defaults, and per-agent overrides"
```

---

## Task 2: Config Schema — GatewayConfig Changes (common module)

**Files:**
- Modify: `common/src/commonMain/kotlin/io/github/klaw/common/config/GatewayConfig.kt`
- Test: `common/src/commonTest/kotlin/io/github/klaw/common/config/ConfigParsingTest.kt`

### Step 2.1: Write failing tests

- [ ] **Write test: new gateway channel structure**

```kotlin
@Test
fun `parse gateway config with map-based channels`() {
    val json = """
    {
      "channels": {
        "telegram": {
          "personal": { "token": "tok1", "agentId": "default" },
          "work": { "token": "tok2", "agentId": "work" }
        },
        "websocket": {
          "main": { "agentId": "default" }
        }
      }
    }
    """.trimIndent()
    val config = parseGatewayConfig(json)
    assertEquals(2, config.channels.telegram.size)
    assertEquals("tok1", config.channels.telegram["personal"]?.token)
    assertEquals("default", config.channels.telegram["personal"]?.agentId)
    assertEquals("work", config.channels.telegram["work"]?.agentId)
    assertEquals(1, config.channels.websocket.size)
}
```

- [ ] **Write test: discord channel with agentId**

```kotlin
@Test
fun `parse gateway discord channel with agentId`() {
    val json = """
    {
      "channels": {
        "discord": {
          "work-guild": { "token": "disc-tok", "agentId": "work" }
        }
      }
    }
    """.trimIndent()
    val config = parseGatewayConfig(json)
    assertEquals("work", config.channels.discord["work-guild"]?.agentId)
}
```

- [ ] **Run tests to verify they fail**

Run: `./gradlew :common:jvmTest --tests '*ConfigParsingTest*gateway*' -x detekt`
Expected: FAIL

### Step 2.2: Restructure GatewayConfig

- [ ] **Rewrite ChannelsConfig to use Map structure**

```kotlin
@Serializable
data class ChannelsConfig(
    val telegram: Map<String, TelegramChannelConfig> = emptyMap(),
    val discord: Map<String, DiscordChannelConfig> = emptyMap(),
    val websocket: Map<String, WebSocketChannelConfig> = emptyMap(),
)

@Serializable
data class TelegramChannelConfig(
    val agentId: String,
    val token: String,
    val allowedChats: List<AllowedChat> = emptyList(),
    val apiBaseUrl: String? = null,
)

@Serializable
data class DiscordChannelConfig(
    val agentId: String,
    val token: String,
    val allowedGuilds: List<AllowedGuild> = emptyList(),
    val apiBaseUrl: String? = null,
)

@Serializable
data class WebSocketChannelConfig(
    val agentId: String,
    val port: Int = 37474,
)
```

Note: Old `TelegramConfig`, `DiscordConfig`, `LocalWsConfig` are replaced. The old `enabled` field is gone — presence in the map means enabled.

- [ ] **Make ChannelsConfig default to empty (no required channels)**

```kotlin
@Serializable
data class GatewayConfig(
    val channels: ChannelsConfig = ChannelsConfig(),
    val delivery: DeliveryConfig = DeliveryConfig(),
    val attachments: AttachmentsConfig = AttachmentsConfig(),
    val webui: WebuiConfig = WebuiConfig(),
)
```

### Step 2.3: Run tests, fix existing tests

- [ ] **Run gateway config tests**

Run: `./gradlew :common:jvmTest --tests '*ConfigParsingTest*' -x detekt`
Expected: New tests PASS, fix any broken existing tests

- [ ] **Run full common tests**

Run: `./gradlew :common:jvmTest -x detekt`

### Step 2.4: Commit

- [ ] **Commit**

```bash
git add common/src/
git commit -m "feat(config): restructure GatewayConfig channels to Map<Type, Map<Name, Config>> with agentId"
```

---

## Task 3: Protocol Changes — Add agentId to SocketMessage (common module)

**Files:**
- Modify: `common/src/commonMain/kotlin/io/github/klaw/common/protocol/SocketProtocol.kt`
- Test: `common/src/commonTest/kotlin/io/github/klaw/common/protocol/SocketProtocolTest.kt`

### Step 3.1: Write failing tests

- [ ] **Write test: InboundSocketMessage with agentId**

```kotlin
@Test
fun `InboundSocketMessage serializes with agentId`() {
    val msg = InboundSocketMessage(
        id = "1", agentId = "work", channel = "tg", chatId = "123",
        content = "hello", ts = Clock.System.now()
    )
    val json = klawJson.encodeToString(SocketMessage.serializer(), msg)
    assertTrue(json.contains("\"agentId\":\"work\""))
    val decoded = klawJson.decodeFromString(SocketMessage.serializer(), json)
    assertEquals("work", (decoded as InboundSocketMessage).agentId)
}
```

- [ ] **Write test: OutboundSocketMessage with agentId**

```kotlin
@Test
fun `OutboundSocketMessage serializes with agentId`() {
    val msg = OutboundSocketMessage(
        agentId = "default", channel = "personal", chatId = "123", content = "hi"
    )
    val json = klawJson.encodeToString(SocketMessage.serializer(), msg)
    assertTrue(json.contains("\"agentId\":\"default\""))
}
```

- [ ] **Write test: CliRequestMessage with agentId defaulting to "default"**

```kotlin
@Test
fun `CliRequestMessage defaults agentId to default`() {
    val json = """{"command":"status","params":{}}"""
    val msg = klawJson.decodeFromString<CliRequestMessage>(json)
    assertEquals("default", msg.agentId)
}

@Test
fun `CliRequestMessage with explicit agentId`() {
    val json = """{"command":"status","agentId":"work","params":{}}"""
    val msg = klawJson.decodeFromString<CliRequestMessage>(json)
    assertEquals("work", msg.agentId)
}
```

- [ ] **Run tests to verify they fail**

Run: `./gradlew :common:jvmTest --tests '*SocketProtocolTest*' -x detekt`
Expected: FAIL

### Step 3.2: Add agentId to all message types

- [ ] **Add agentId to inbound messages**

```kotlin
@Serializable @SerialName("chat")
data class InboundSocketMessage(
    val id: String,
    val agentId: String = "default",  // NEW
    val channel: String,
    val chatId: String,
    val content: String,
    val ts: Instant,
    // ... rest unchanged
)

@Serializable @SerialName("command")
data class CommandSocketMessage(
    val agentId: String = "default",  // NEW
    val channel: String,
    val chatId: String,
    val command: String,
    // ... rest unchanged
)

@Serializable @SerialName("approval_response")
data class ApprovalResponseMessage(
    val id: String,
    val agentId: String = "default",  // NEW
    val approved: Boolean,
)
```

- [ ] **Add agentId to outbound messages**

```kotlin
@Serializable @SerialName("response")
data class OutboundSocketMessage(
    val agentId: String = "default",  // NEW
    val replyTo: String? = null,
    val channel: String,
    val chatId: String,
    val content: String,
    // ... rest unchanged
)

@Serializable @SerialName("approval_request")
data class ApprovalRequestMessage(
    val id: String,
    val agentId: String = "default",  // NEW
    val chatId: String,
    // ... rest unchanged
)

@Serializable @SerialName("approval_dismiss")
data class ApprovalDismissMessage(
    val id: String,
    val agentId: String = "default",  // NEW
)

@Serializable @SerialName("stream_delta")
data class StreamDeltaSocketMessage(
    val agentId: String = "default",  // NEW
    val channel: String,
    val chatId: String,
    // ... rest unchanged
)

@Serializable @SerialName("stream_end")
data class StreamEndSocketMessage(
    val agentId: String = "default",  // NEW
    val channel: String,
    val chatId: String,
    // ... rest unchanged
)
```

- [ ] **Add agentId to CliRequestMessage**

```kotlin
@Serializable
data class CliRequestMessage(
    val agentId: String = "default",  // NEW
    val command: String,
    val params: Map<String, String> = emptyMap(),
)
```

### Step 3.3: Run tests, fix existing protocol tests

- [ ] **Run protocol tests**

Run: `./gradlew :common:jvmTest --tests '*SocketProtocolTest*' -x detekt`
Expected: PASS

- [ ] **Run full common tests**

Run: `./gradlew :common:jvmTest -x detekt`
Expected: All PASS (some tests may need `agentId` added to message constructors)

### Step 3.4: Commit

- [ ] **Commit**

```bash
git add common/src/
git commit -m "feat(protocol): add agentId field to all SocketMessage types and CliRequestMessage"
```

---

## Task 4: Engine compilation fix — update all existing Engine code to compile with new config/protocol

**Files:**
- Modify: All engine source files referencing changed config/protocol types
- No new tests in this task — just make existing code compile

This is a bridge task. The config schema and protocol changed, so Engine code won't compile. This task updates all references to use the new types without changing behavior. The actual multi-agent logic comes in later tasks.

### Step 4.1: Fix config usage in Engine

- [ ] **Fix TaskRoutingConfig fallback**

Wherever Engine reads `config.routing.tasks.summarization` or `config.routing.tasks.subagent`, add fallback to `config.routing.default` when value is empty:

```kotlin
// In LlmRouter or wherever task routing is resolved:
fun resolveTaskModel(task: String): String {
    val taskModel = when (task) {
        "summarization" -> config.routing.tasks.summarization
        "subagent" -> config.routing.tasks.subagent
        "consolidation" -> config.routing.tasks.consolidation
        else -> ""
    }
    return taskModel.ifEmpty { config.routing.default }
}
```

- [ ] **Fix EngineConfig.agents references**

Engine code currently reads `config.workspace`, `config.heartbeat`, etc. For now, create a temporary helper that extracts the first agent's config to maintain single-agent behavior until AgentContext is implemented:

```kotlin
// Temporary bridge — will be removed when AgentContext is implemented
val EngineConfig.defaultAgent: AgentConfig
    get() = agents.values.first()
```

- [ ] **Fix all channel-related code in Engine that references old GatewayConfig types**

Engine doesn't directly use GatewayConfig, but check for any cross-references.

### Step 4.2: Fix Gateway compilation

- [ ] **Update GatewayLifecycle to iterate new channel structure**

The gateway currently reads `config.channels.telegram`, `config.channels.discord`, `config.channels.localWs` as single optional objects. Now they are `Map<String, *>`. Update to iterate:

```kotlin
// Old:
config.channels.telegram?.let { createTelegramChannel(it) }

// New:
config.channels.telegram.forEach { (name, channelConfig) ->
    createTelegramChannel(name, channelConfig)
}
```

- [ ] **Update TelegramChannel, DiscordChannel, LocalWsChannel constructors**

Add `channelName: String` and `agentId: String` parameters. Populate `agentId` in outgoing `InboundSocketMessage` and `CommandSocketMessage`.

- [ ] **Update GatewayOutboundHandler.detectChannel()**

Currently routes by chatId prefix. Now route by `channel` field (which is the channel name from the map key):

```kotlin
// Old: detectChannel(chatId) inspects prefix
// New: detectChannel(channelName) looks up from registered channels map
private val channelMap = ConcurrentHashMap<String, Channel>()

fun registerChannel(name: String, channel: Channel) {
    channelMap[name] = channel
}

fun detectChannel(channelName: String): Channel? = channelMap[channelName]
```

- [ ] **Update ConversationJsonlWriter**

Add `agentId` parameter to `write()` method. Scope directory: `conversations/{agentId}/{chatId}/`

### Step 4.3: Fix CLI compilation

- [ ] **Update CLI commands that construct CliRequestMessage**

Add `agentId` parameter (default "default" for now):

```kotlin
val request = CliRequestMessage(
    agentId = agentOption ?: "default",
    command = "status",
    params = mapOf()
)
```

- [ ] **Update ConfigTemplates to generate new format**

Update `buildEngineConfig()` to produce `agents` section and `buildGatewayConfig()` to produce new channel structure.

### Step 4.4: Compile all modules

- [ ] **Build entire project**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

### Step 4.5: Run all tests, fix failures

- [ ] **Run all tests**

Run: `./gradlew :common:jvmTest :gateway:test :engine:test`
Expected: Fix all failures caused by schema changes (mostly adding `agents` to test configs, updating message constructors)

### Step 4.6: Commit

- [ ] **Commit**

```bash
git add -A
git commit -m "fix: update engine, gateway, and CLI to compile with new multi-agent config and protocol schema"
```

---

## Task 5: AgentContext + AgentRegistry + AgentContextFactory (engine module)

**Files:**
- Create: `engine/src/main/kotlin/io/github/klaw/engine/agent/AgentContext.kt`
- Create: `engine/src/main/kotlin/io/github/klaw/engine/agent/AgentContextFactory.kt`
- Create: `engine/src/main/kotlin/io/github/klaw/engine/agent/AgentRegistry.kt`
- Create: `engine/src/main/kotlin/io/github/klaw/engine/agent/SharedServices.kt`
- Test: `engine/src/test/kotlin/io/github/klaw/engine/agent/AgentContextFactoryTest.kt`
- Test: `engine/src/test/kotlin/io/github/klaw/engine/agent/AgentRegistryTest.kt`

### Step 5.1: Write failing tests for AgentRegistry

- [ ] **Write test: AgentRegistry.get() returns correct context**

```kotlin
@Test
fun `get returns registered agent context`() {
    val registry = AgentRegistry()
    val ctx = createTestAgentContext("test-agent")
    registry.register("test-agent", ctx)
    assertEquals(ctx, registry.get("test-agent"))
}
```

- [ ] **Write test: AgentRegistry.get() throws for unknown agent**

```kotlin
@Test
fun `get throws for unknown agent`() {
    val registry = AgentRegistry()
    assertFailsWith<IllegalArgumentException> {
        registry.get("nonexistent")
    }
}
```

- [ ] **Write test: AgentRegistry.all() returns all contexts**

```kotlin
@Test
fun `all returns all registered contexts`() {
    val registry = AgentRegistry()
    registry.register("a", createTestAgentContext("a"))
    registry.register("b", createTestAgentContext("b"))
    assertEquals(2, registry.all().size)
}
```

- [ ] **Write test: AgentRegistry skips disabled agents**

```kotlin
@Test
fun `initialize skips disabled agents`() = runBlocking {
    val config = createTestEngineConfig(
        agents = mapOf(
            "active" to AgentConfig(workspace = "/tmp/a"),
            "disabled" to AgentConfig(enabled = false, workspace = "/tmp/b"),
        )
    )
    val registry = AgentRegistry()
    // after initialize with factory...
    assertEquals(1, registry.all().size)
    assertNotNull(registry.getOrNull("active"))
    assertNull(registry.getOrNull("disabled"))
}
```

- [ ] **Run tests to verify they fail**

Run: `./gradlew :engine:test --tests '*AgentRegistryTest*' -x detekt`
Expected: FAIL — classes don't exist

### Step 5.2: Implement AgentContext and SharedServices

- [ ] **Create SharedServices**

```kotlin
class SharedServices(
    val llmRouter: LlmRouter,
    val embeddingService: EmbeddingService,
    val sandboxManager: SandboxManager,
    val socketServer: EngineSocketServer,
    val sqliteVecLoader: SqliteVecLoader,
    val globalConfig: EngineConfig,
)
```

- [ ] **Create AgentContext**

```kotlin
class AgentContext(
    val agentId: String,
    val agentConfig: AgentConfig,
    val database: KlawDatabase,
    val driver: JdbcSqliteDriver,
    val sessionManager: SessionManager,
    val messageRepository: MessageRepository,
    val memoryService: MemoryService,
    val workspaceLoader: WorkspaceLoader,
    val contextBuilder: ContextBuilder,
    val scheduler: KlawScheduler,
    val skillRegistry: SkillRegistry,
    val toolRegistry: ToolRegistry,
    val autoRagService: AutoRagService,
    val heartbeatRunner: HeartbeatRunner?,
    val summaryService: SummaryService,
    val compactionRunner: CompactionRunner,
    val subagentRunRepository: SubagentRunRepository,
    val backupService: BackupService,
    val mcpToolRegistry: McpToolRegistry,
    val messageEmbeddingService: MessageEmbeddingService,
    val consolidationService: DailyConsolidationService?,
) {
    fun shutdown() {
        scheduler.shutdownBlocking()
        backupService.stop()
        heartbeatRunner?.stop()
        driver.close()
    }
}
```

### Step 5.3: Implement AgentRegistry

- [ ] **Create AgentRegistry**

```kotlin
@Singleton
class AgentRegistry {
    private val agents = ConcurrentHashMap<String, AgentContext>()

    fun register(agentId: String, context: AgentContext) {
        agents[agentId] = context
    }

    fun get(agentId: String): AgentContext =
        agents[agentId] ?: throw IllegalArgumentException("Unknown agent: '$agentId'")

    fun getOrNull(agentId: String): AgentContext? = agents[agentId]

    fun all(): Collection<AgentContext> = agents.values

    fun shutdown() {
        agents.values.forEach { it.shutdown() }
        agents.clear()
    }
}
```

### Step 5.4: Implement AgentContextFactory

- [ ] **Create AgentContextFactory**

This is the most complex class — it wires up all per-agent services imperatively. The factory takes `SharedServices` and creates one `AgentContext` per agent config.

```kotlin
@Singleton
class AgentContextFactory(
    private val shared: SharedServices,
) {
    fun create(agentId: String, agentConfig: AgentConfig): AgentContext {
        val dbPath = KlawPaths.stateDir + "/klaw-$agentId.db"
        val schedulerDbPath = KlawPaths.stateDir + "/scheduler-$agentId.db"
        val workspacePath = agentConfig.workspace

        // 0. Workspace auto-creation
        val wsDir = File(workspacePath)
        if (!wsDir.exists()) {
            wsDir.mkdirs()
            // Copy template files from classpath resources
            listOf("SOUL.md", "IDENTITY.md", "USER.md", "AGENTS.md", "TOOLS.md").forEach { name ->
                val template = javaClass.getResourceAsStream("/workspace-templates/$name")
                if (template != null) {
                    File(wsDir, name).writeText(template.bufferedReader().readText())
                }
            }
            logger.info { "Created workspace for agent '$agentId' at $workspacePath" }
        }

        // 1. Database
        val driver = createDriver(dbPath, shared.globalConfig.database, shared.sqliteVecLoader)
        val database = createDatabase(driver)

        // 2. Repositories
        val messageRepository = MessageRepository(database)
        val subagentRunRepository = SubagentRunRepository(database)
        val summaryRepository = SummaryRepository(database)

        // 3. Session manager
        val sessionManager = SessionManager(database)

        // 4. Memory
        val memoryService = MemoryServiceImpl(
            database, driver, shared.embeddingService,
            shared.sqliteVecLoader, resolveMemoryConfig(agentConfig)
        )

        // 5. Workspace loader
        val workspaceLoader = KlawWorkspaceLoader(memoryService, workspacePath)

        // 6. Skills
        val skillRegistry = FileSkillRegistry(workspacePath)

        // 7. Summary & compaction
        val summaryService = SummaryService(summaryRepository, shared.llmRouter, resolveConfig(agentConfig))
        val compactionRunner = CompactionRunner(summaryService, messageRepository, resolveConfig(agentConfig))

        // 8. Auto-RAG
        val autoRagService = AutoRagService(memoryService, shared.embeddingService, resolveAutoRagConfig(agentConfig))

        // 9. Message embedding
        val messageEmbeddingService = MessageEmbeddingService(messageRepository, shared.embeddingService)

        // 10. Context builder
        val contextBuilder = ContextBuilder(
            workspaceLoader, messageRepository, summaryService,
            skillRegistry, /* toolRegistry */ null, // set after tool wiring
            resolveConfig(agentConfig), autoRagService,
            SubagentHistoryLoader(workspacePath),
            shared.healthProvider, shared.llmRouter
        )

        // 11. Tools — per-agent registry with agent-scoped file tools, schedule tools, etc.
        val toolRegistry = buildToolRegistry(agentConfig, agentId, workspacePath, memoryService, /*...*/)

        // 12. MCP
        val mcpToolRegistry = McpToolRegistry(agentConfig.mcp)

        // 13. Scheduler
        val scheduler = KlawSchedulerImpl(schedulerDbPath, subagentRunRepository)

        // 14. Backup
        val backupService = BackupService(driver, dbPath, resolveConfig(agentConfig).database)

        // 15. Heartbeat
        val heartbeatRunner = if (resolveHeartbeatConfig(agentConfig).interval != "off") {
            HeartbeatRunner(/* per-agent deps */)
        } else null

        // 16. Consolidation
        val consolidationService = if (resolveConsolidationConfig(agentConfig).enabled) {
            DailyConsolidationService(/* per-agent deps */)
        } else null

        return AgentContext(
            agentId = agentId,
            agentConfig = agentConfig,
            database = database,
            driver = driver,
            sessionManager = sessionManager,
            messageRepository = messageRepository,
            memoryService = memoryService,
            workspaceLoader = workspaceLoader,
            contextBuilder = contextBuilder,
            scheduler = scheduler,
            skillRegistry = skillRegistry,
            toolRegistry = toolRegistry,
            autoRagService = autoRagService,
            heartbeatRunner = heartbeatRunner,
            summaryService = summaryService,
            compactionRunner = compactionRunner,
            subagentRunRepository = subagentRunRepository,
            backupService = backupService,
            mcpToolRegistry = mcpToolRegistry,
            messageEmbeddingService = messageEmbeddingService,
            consolidationService = consolidationService,
        )
    }

    private fun resolveConfig(agentConfig: AgentConfig): EngineConfig {
        // Merge global config with agent overrides
        // Implementation: deep merge agent.routing/processing/memory/etc over global
    }
}
```

Note: The exact wiring depends on current constructor signatures of each service. The implementing agent should read each service's constructor and match parameters. The key principle: NO Micronaut DI for per-agent services — all manual `new`/constructor calls.

### Step 5.5: Run tests

- [ ] **Run agent tests**

Run: `./gradlew :engine:test --tests '*Agent*Test*' -x detekt`
Expected: PASS

### Step 5.6: Commit

- [ ] **Commit**

```bash
git add engine/src/
git commit -m "feat(engine): add AgentContext, AgentContextFactory, AgentRegistry for multi-agent support"
```

---

## Task 6: Wire AgentRegistry into EngineLifecycle

**Files:**
- Modify: `engine/src/main/kotlin/io/github/klaw/engine/EngineLifecycle.kt`
- Modify: `engine/src/main/kotlin/io/github/klaw/engine/config/` — adjust DI factories
- Test: existing lifecycle tests

### Step 6.1: Write failing test

- [ ] **Write test: EngineLifecycle initializes AgentRegistry on startup**

```kotlin
@Test
fun `engine startup initializes all enabled agents`() {
    // Configure two agents, one disabled
    // Start engine
    // Verify AgentRegistry has 1 agent (the enabled one)
}
```

### Step 6.2: Refactor EngineLifecycle

- [ ] **Replace per-agent singleton dependencies with AgentRegistry**

```kotlin
@Singleton
class EngineLifecycle(
    private val socketServer: EngineSocketServer,
    private val messageProcessor: MessageProcessor,
    private val agentRegistry: AgentRegistry,
    private val agentContextFactory: AgentContextFactory,
    private val config: EngineConfig,
    private val sandboxManager: SandboxManager,
    private val healthProvider: EngineHealthProvider,
) : ApplicationEventListener<StartupEvent> {

    override fun onApplicationEvent(event: StartupEvent) {
        // Initialize all agents
        config.agents.forEach { (agentId, agentConfig) ->
            if (agentConfig.enabled) {
                val ctx = agentContextFactory.create(agentId, agentConfig)
                agentRegistry.register(agentId, ctx)
                ctx.scheduler.start()
                ctx.backupService.start()
                ctx.heartbeatRunner?.start()
            }
        }
        socketServer.start()
        healthProvider.markReady()
    }

    fun shutdown() {
        agentRegistry.shutdown()  // stops all per-agent services
        messageProcessor.shutdown()
        sandboxManager.shutdown()
        socketServer.stop()
    }
}
```

- [ ] **Remove old per-agent singletons from DI**

Remove `@Singleton`/`@Factory` from: `DatabaseFactory`, `MemoryServiceImplFactory`, `SessionManager`, etc. These are now created by `AgentContextFactory`.

Keep as Micronaut singletons: `LlmRouter`, `EmbeddingService`, `SandboxManager`, `EngineSocketServer`, `EngineConfig`, `AgentRegistry`, `AgentContextFactory`, `MessageProcessor`, `CliCommandDispatcher`.

### Step 6.3: Run tests, fix

- [ ] **Build and test**

Run: `./gradlew :engine:test -x detekt`
Expected: Fix broken DI wiring — many tests will need updating to construct AgentContext manually instead of relying on injected singletons.

### Step 6.4: Commit

- [ ] **Commit**

```bash
git add engine/src/
git commit -m "feat(engine): wire AgentRegistry into EngineLifecycle, remove per-agent singletons from DI"
```

---

## Task 7: MessageProcessor — resolve AgentContext per message

**Files:**
- Modify: `engine/src/main/kotlin/io/github/klaw/engine/message/MessageProcessor.kt`
- Modify: `engine/src/main/kotlin/io/github/klaw/engine/socket/CliCommandDispatcher.kt`
- Test: existing MessageProcessor tests

### Step 7.1: Write failing test

- [ ] **Write test: messages to different agents use different sessions**

```kotlin
@Test
fun `messages to different agents are isolated`() = runBlocking {
    // Send message with agentId="a" → uses agent A's session manager
    // Send message with agentId="b" → uses agent B's session manager
    // Verify no cross-contamination
}
```

### Step 7.2: Refactor MessageProcessor

- [ ] **Replace per-agent singletons with AgentRegistry lookup**

```kotlin
@Singleton
class MessageProcessor(
    private val agentRegistry: AgentRegistry,
    private val llmRouter: LlmRouter,
    private val socketServerProvider: Provider<EngineSocketServer>,
    private val config: EngineConfig,
    private val approvalService: ApprovalService,
    private val shutdownController: ShutdownController,
) : SocketMessageHandler {

    override suspend fun handleInbound(message: InboundSocketMessage) {
        val ctx = agentRegistry.get(message.agentId)
        // Use ctx.sessionManager, ctx.contextBuilder, ctx.messageRepository, etc.
        debounceBuffer.add(message)
    }

    private suspend fun processMessages(messages: List<InboundSocketMessage>) {
        val agentId = messages.first().agentId
        val ctx = agentRegistry.get(agentId)
        val session = ctx.sessionManager.getOrCreate(chatId, resolveDefaultModel(ctx))
        val context = ctx.contextBuilder.buildContext(session, pendingMessages, false)
        // ... LLM call using shared llmRouter ...
        // ... tool execution using ctx.toolRegistry ...
    }
}
```

- [ ] **Refactor CliCommandDispatcher to use AgentRegistry**

```kotlin
@Singleton
class CliCommandDispatcher(
    private val agentRegistry: AgentRegistry,
    private val config: EngineConfig,
    private val llmRouter: LlmRouter,
    // ... only global services ...
) {
    suspend fun dispatch(request: CliRequestMessage): String {
        val ctx = agentRegistry.get(request.agentId)
        return when {
            request.command.startsWith("memory") -> dispatchMemoryCommand(ctx, request)
            request.command.startsWith("schedule") -> dispatchScheduleCommand(ctx, request)
            request.command == "status" -> dispatchStatusCommand()  // global, all agents
            // ...
        }
    }
}
```

### Step 7.3: Run tests, fix

- [ ] **Run engine tests**

Run: `./gradlew :engine:test -x detekt`

### Step 7.4: Commit

- [ ] **Commit**

```bash
git add engine/src/
git commit -m "feat(engine): MessageProcessor and CliCommandDispatcher resolve AgentContext per message"
```

---

## Task 8: Gateway — channel-to-agent routing

**Files:**
- Modify: `gateway/src/main/kotlin/io/github/klaw/gateway/GatewayLifecycle.kt`
- Modify: `gateway/src/main/kotlin/io/github/klaw/gateway/channel/TelegramChannel.kt`
- Modify: `gateway/src/main/kotlin/io/github/klaw/gateway/channel/DiscordChannel.kt`
- Modify: `gateway/src/main/kotlin/io/github/klaw/gateway/channel/LocalWsChannel.kt`
- Modify: `gateway/src/main/kotlin/io/github/klaw/gateway/socket/GatewayOutboundHandler.kt`
- Modify: `gateway/src/main/kotlin/io/github/klaw/gateway/socket/ConversationJsonlWriter.kt`
- Test: `gateway/src/test/`

### Step 8.1: Write failing tests

- [ ] **Write test: TelegramChannel populates agentId in messages**
- [ ] **Write test: GatewayOutboundHandler routes by channel name**
- [ ] **Write test: ConversationJsonlWriter scopes by agentId**

### Step 8.2: Implement Gateway changes

- [ ] **Update GatewayLifecycle to create channels from new config**

Iterate `config.channels.telegram`, `config.channels.discord`, `config.channels.websocket` maps. Create one channel instance per entry with `channelName` (map key) and `agentId`.

- [ ] **Update each Channel to include agentId in outbound messages**

Each channel stores `agentId` from config and populates it in `InboundSocketMessage(agentId = this.agentId, ...)`.

- [ ] **Update GatewayOutboundHandler to route by channel name**

Replace `detectChannel(chatId)` prefix-based routing with `channelMap[channelName]` lookup.

- [ ] **Update ConversationJsonlWriter to scope by agentId**

`write(agentId, chatId, message)` writes to `conversations/{agentId}/{chatId}/`.

### Step 8.3: Run tests

- [ ] **Run gateway tests**

Run: `./gradlew :gateway:test -x detekt`

### Step 8.4: Commit

- [ ] **Commit**

```bash
git add gateway/src/
git commit -m "feat(gateway): channel-to-agent routing with agentId in messages and scoped JSONL"
```

---

## Task 9: CLI — --agent flag and new config templates

**Files:**
- Modify: `cli/src/nativeMain/kotlin/io/github/klaw/cli/command/ChatCommand.kt`
- Modify: `cli/src/nativeMain/kotlin/io/github/klaw/cli/command/MemoryCommand.kt`
- Modify: `cli/src/nativeMain/kotlin/io/github/klaw/cli/command/SessionsCommand.kt`
- Modify: `cli/src/nativeMain/kotlin/io/github/klaw/cli/command/ScheduleCommand.kt`
- Modify: `cli/src/nativeMain/kotlin/io/github/klaw/cli/command/StatusCommand.kt`
- Modify: `cli/src/nativeMain/kotlin/io/github/klaw/cli/command/DoctorCommand.kt`
- Modify: `cli/src/nativeMain/kotlin/io/github/klaw/cli/init/ConfigTemplates.kt`
- Modify: `cli/src/nativeMain/kotlin/io/github/klaw/cli/init/InitWizard.kt`
- Test: `cli/src/macosArm64Test/`

### Step 9.1: Add --agent option to commands

- [ ] **Create shared agent option**

```kotlin
// In a shared location or as a mixin
class AgentOption : OptionGroup() {
    val agent by option("--agent", "-a", help = "Agent ID (default: 'default')")
        .default("default")
}
```

- [ ] **Add to ChatCommand, MemoryCommand, SessionsCommand, ScheduleCommand**

Each command includes `AgentOption` and passes `agent` to `CliRequestMessage(agentId = agent, ...)`.

- [ ] **StatusCommand: show all agents**

`klaw status` sends a global status request (no agent filter). Engine returns status for all agents.

- [ ] **DoctorCommand: validate all agents**

`klaw doctor` checks all agent configs: workspace exists, DB accessible, etc.

### Step 9.2: Update ConfigTemplates for new format

- [ ] **Update buildEngineConfig to produce agents section**

```kotlin
fun buildEngineConfig(/* params */): String {
    val config = EngineConfig(
        providers = mapOf(providerName to providerConfig),
        routing = RoutingConfig(default = modelId),
        agents = mapOf("default" to AgentConfig(workspace = workspacePath)),
    )
    return encodeEngineConfigMinimal(config)
}
```

- [ ] **Update buildGatewayConfig to produce new channel structure**

```kotlin
fun buildGatewayConfig(/* params */): String {
    val channels = ChannelsConfig(
        telegram = if (telegramEnabled) mapOf("default" to TelegramChannelConfig(
            agentId = "default", token = telegramToken
        )) else emptyMap(),
        // ...
    )
    return encodeGatewayConfigMinimal(GatewayConfig(channels = channels))
}
```

### Step 9.3: Update InitWizard

- [ ] **Create agents array in wizard flow**

Wizard creates single `"default"` agent with workspace path from user input.

### Step 9.4: Run CLI tests

- [ ] **Run CLI tests**

Run: `./gradlew :cli:macosArm64Test -x detekt`

### Step 9.5: Commit

- [ ] **Commit**

```bash
git add cli/src/
git commit -m "feat(cli): add --agent flag to commands, update init wizard for multi-agent config"
```

---

## Task 10: Migration script (bash+jq)

**Files:**
- Create: `scripts/migrate-to-multiagent.sh`

### Step 10.1: Write migration script

- [ ] **Create scripts/migrate-to-multiagent.sh**

```bash
#!/usr/bin/env bash
set -euo pipefail

# Usage: ./migrate-to-multiagent.sh [--apply] [--config-dir DIR] [--data-dir DIR] [--state-dir DIR]
# Default: dry-run mode (prints what would change)

APPLY=false
CONFIG_DIR="${KLAW_CONFIG:-$HOME/.config/klaw}"
DATA_DIR="${KLAW_DATA:-$HOME/.local/share/klaw}"
STATE_DIR="${KLAW_STATE:-$HOME/.local/state/klaw}"

while [[ $# -gt 0 ]]; do
    case $1 in
        --apply) APPLY=true; shift ;;
        --config-dir) CONFIG_DIR="$2"; shift 2 ;;
        --data-dir) DATA_DIR="$2"; shift 2 ;;
        --state-dir) STATE_DIR="$2"; shift 2 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

echo "=== Klaw Multi-Agent Migration ==="
echo "Config: $CONFIG_DIR"
echo "Data:   $DATA_DIR"
echo "State:  $STATE_DIR"
echo "Mode:   $([ "$APPLY" = true ] && echo 'APPLY' || echo 'DRY-RUN')"
echo

# --- engine.json migration ---
ENGINE_JSON="$CONFIG_DIR/engine.json"
if [ -f "$ENGINE_JSON" ]; then
    echo "--- engine.json ---"

    # Extract workspace (may be null)
    WORKSPACE=$(jq -r '.workspace // empty' "$ENGINE_JSON")
    [ -z "$WORKSPACE" ] && WORKSPACE="$DATA_DIR/agents/default"

    # Build new config: move per-agent fields to agents.default
    NEW_ENGINE=$(jq --arg ws "$WORKSPACE" '
        # Extract per-agent fields
        .agents = {
            "default": {
                "workspace": $ws,
                "heartbeat": (.heartbeat // null),
                "vision": (if .vision.enabled then .vision else null end),
                "tools": {
                    "sandbox": (if .codeExecution != {} then .codeExecution else null end),
                    "hostExec": (if .hostExecution.enabled then .hostExecution else null end)
                }
            }
        }
        # Remove per-agent fields from top level
        | del(.workspace, .heartbeat, .vision, .codeExecution, .hostExecution)
        # Remove fields matching new defaults
        | if .processing.debounceMs == 800 then .processing |= del(.debounceMs) else . end
        | if .processing.maxConcurrentLlm == 3 then .processing |= del(.maxConcurrentLlm) else . end
        | if .processing.maxToolCallRounds == 50 then .processing |= del(.maxToolCallRounds) else . end
        | if .memory.chunking.size == 512 then .memory.chunking |= del(.size) else . end
        | if .memory.chunking.overlap == 64 then .memory.chunking |= del(.overlap) else . end
        | if .memory.search.topK == 10 then .memory.search |= del(.topK) else . end
        | if .context.subagentHistory == 10 then .context |= del(.subagentHistory) else . end
        | if .memory.embedding.type == "onnx" then .memory.embedding |= del(.type) else . end
        # Clean empty objects
        | walk(if type == "object" then with_entries(select(.value != null and .value != {} and .value != [])) else . end)
        # Remove agents.default null fields
        | .agents.default |= with_entries(select(.value != null))
    ' "$ENGINE_JSON")

    echo "New engine.json:"
    echo "$NEW_ENGINE" | jq .
    echo

    if [ "$APPLY" = true ]; then
        cp "$ENGINE_JSON" "${ENGINE_JSON}.pre-multiagent-backup"
        echo "$NEW_ENGINE" | jq . > "$ENGINE_JSON"
        echo "engine.json updated (backup: ${ENGINE_JSON}.pre-multiagent-backup)"
    fi
fi

# --- gateway.json migration ---
GATEWAY_JSON="$CONFIG_DIR/gateway.json"
if [ -f "$GATEWAY_JSON" ]; then
    echo "--- gateway.json ---"

    NEW_GATEWAY=$(jq '
        .channels = {
            "telegram": (if .channels.telegram then
                { "default": (.channels.telegram + { "agentId": "default" }) }
            else {} end),
            "discord": (if .channels.discord then
                { "default": (.channels.discord | del(.enabled) | . + { "agentId": "default" }) }
            else {} end),
            "websocket": (if .channels.localWs then
                { "default": (.channels.localWs | del(.enabled) | . + { "agentId": "default" }) }
            else {} end)
        }
        | .channels |= with_entries(select(.value != {}))
    ' "$GATEWAY_JSON")

    echo "New gateway.json:"
    echo "$NEW_GATEWAY" | jq .
    echo

    if [ "$APPLY" = true ]; then
        cp "$GATEWAY_JSON" "${GATEWAY_JSON}.pre-multiagent-backup"
        echo "$NEW_GATEWAY" | jq . > "$GATEWAY_JSON"
        echo "gateway.json updated (backup: ${GATEWAY_JSON}.pre-multiagent-backup)"
    fi
fi

# --- Data file migration ---
echo "--- Data files ---"

# klaw.db → klaw-default.db
if [ -f "$STATE_DIR/klaw.db" ] && [ ! -f "$STATE_DIR/klaw-default.db" ]; then
    echo "mv $STATE_DIR/klaw.db → $STATE_DIR/klaw-default.db"
    if [ "$APPLY" = true ]; then
        mv "$STATE_DIR/klaw.db" "$STATE_DIR/klaw-default.db"
        [ -f "$STATE_DIR/klaw.db-wal" ] && mv "$STATE_DIR/klaw.db-wal" "$STATE_DIR/klaw-default.db-wal"
        [ -f "$STATE_DIR/klaw.db-shm" ] && mv "$STATE_DIR/klaw.db-shm" "$STATE_DIR/klaw-default.db-shm"
    fi
fi

# scheduler.db → scheduler-default.db
if [ -f "$STATE_DIR/scheduler.db" ] && [ ! -f "$STATE_DIR/scheduler-default.db" ]; then
    echo "mv $STATE_DIR/scheduler.db → $STATE_DIR/scheduler-default.db"
    if [ "$APPLY" = true ]; then
        mv "$STATE_DIR/scheduler.db" "$STATE_DIR/scheduler-default.db"
    fi
fi

# conversations/ → conversations/default/
CONV_DIR="$DATA_DIR/conversations"
if [ -d "$CONV_DIR" ] && [ ! -d "$CONV_DIR/default" ]; then
    # Check if there are chat dirs directly in conversations/
    CHAT_DIRS=$(find "$CONV_DIR" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | head -1)
    if [ -n "$CHAT_DIRS" ]; then
        echo "mkdir $CONV_DIR/default && mv $CONV_DIR/*/ → $CONV_DIR/default/"
        if [ "$APPLY" = true ]; then
            mkdir -p "$CONV_DIR/default"
            for dir in "$CONV_DIR"/*/; do
                [ "$(basename "$dir")" = "default" ] && continue
                mv "$dir" "$CONV_DIR/default/"
            done
        fi
    fi
fi

echo
echo "=== Migration $([ "$APPLY" = true ] && echo 'COMPLETE' || echo 'DRY-RUN COMPLETE (use --apply to execute)') ==="
```

### Step 10.2: Test locally with fetched configs

- [ ] **Fetch configs from Pi**

```bash
mkdir -p /tmp/klaw-migrate-test
scp sickfar-pi.local:~/.config/klaw/engine.json /tmp/klaw-migrate-test/
scp sickfar-pi.local:~/.config/klaw/gateway.json /tmp/klaw-migrate-test/
```

- [ ] **Run dry-run**

```bash
./scripts/migrate-to-multiagent.sh --config-dir /tmp/klaw-migrate-test --data-dir /tmp/klaw-data --state-dir /tmp/klaw-state
```

Verify output looks correct.

- [ ] **Run with --apply on test copy**

```bash
./scripts/migrate-to-multiagent.sh --apply --config-dir /tmp/klaw-migrate-test --data-dir /tmp/klaw-data --state-dir /tmp/klaw-state
```

Verify files were created correctly.

### Step 10.3: Commit

- [ ] **Commit**

```bash
git add scripts/migrate-to-multiagent.sh
git commit -m "feat: add bash+jq migration script for multi-agent config transition"
```

---

## Task 11: Documentation Update

**Files:**
- Modify: `doc/config/engine-json.md`
- Modify: `doc/config/gateway-json.md` (or equivalent)
- Modify: `doc/deployment/` — Docker compose, native deployment docs
- Modify: `doc/commands/` — CLI command docs
- Create: `doc/architecture/multi-agent.md`
- Modify: `CLAUDE.md` — update architecture description

### Step 11.1: Update config documentation

- [ ] **Update engine-json.md with agents section, _defaults, per-agent overrides**
- [ ] **Update gateway-json.md with new channel structure**
- [ ] **Document minimal config example**
- [ ] **Document full config example**

### Step 11.2: Create multi-agent architecture doc

- [ ] **Create doc/architecture/multi-agent.md**

Explain: AgentContext pattern, shared vs per-agent, how to add agents, how to migrate.

### Step 11.3: Update CLI command docs

- [ ] **Document --agent flag on all applicable commands**
- [ ] **Document klaw agents list/add/remove (if implemented)**

### Step 11.4: Update CLAUDE.md

- [ ] **Update Project Architecture section**

Add multi-agent info: AgentContext, AgentRegistry, per-agent DB isolation.

- [ ] **Update Config parsing section**

Document `_defaults` merge, per-agent overrides, new defaults.

### Step 11.5: Commit

- [ ] **Commit**

```bash
git add doc/ CLAUDE.md
git commit -m "docs: add multi-agent architecture documentation, update config and CLI docs"
```

---

## Task 12: E2E Tests

**Files:**
- Modify: `e2e/src/main/kotlin/io/github/klaw/e2e/` — infrastructure classes
- Create: `e2e/src/integrationTest/kotlin/io/github/klaw/e2e/MultiAgentE2eTest.kt`

### Step 12.1: Update E2E infrastructure for multi-agent

- [ ] **Update ConfigGenerator to produce multi-agent configs**
- [ ] **Update WorkspaceGenerator to create per-agent workspaces**
- [ ] **Update WireMockLlmServer if needed for agent-scoped requests**

### Step 12.2: Write multi-agent E2E test

- [ ] **Write test: two agents receive messages on separate channels, isolated memory**

```kotlin
@Test
fun `messages to different agents are fully isolated`() {
    // 1. Start Engine + Gateway with two agents ("a" and "b")
    // 2. Send message to agent "a" via channel-a
    // 3. Send message to agent "b" via channel-b
    // 4. Verify: agent A's DB has message, agent B's DB doesn't (and vice versa)
    // 5. Verify: JSONL written to conversations/a/ and conversations/b/
}
```

- [ ] **Write test: disabled agent returns error**

```kotlin
@Test
fun `disabled agent returns error response`() {
    // 1. Configure agent "disabled" with enabled=false
    // 2. Send message to disabled agent
    // 3. Verify error response received
}
```

### Step 12.3: Run E2E tests

- [ ] **Run E2E integration tests**

Run: `./gradlew :e2e:integrationTest --tests '*MultiAgentE2eTest*'`

### Step 12.4: Commit

- [ ] **Commit**

```bash
git add e2e/src/
git commit -m "test(e2e): add multi-agent isolation and disabled agent E2E tests"
```

---

## Task 13: Code Review + Quality Checks

### Step 13.1: Run code quality tools

- [ ] **Run ktlint and detekt**

Run: `./gradlew ktlintCheck detekt`
Fix any violations (no @Suppress on production code).

- [ ] **Run lang-tools dead code detection**

Use `mcp__lang-tools__detect_dead_code_java` and `mcp__lang-tools__detect_dead_code_kotlin` on whole project. Remove genuinely dead code from refactoring (old singleton factories that are no longer used).

- [ ] **Run lang-tools import cleanup**

Use `mcp__lang-tools__cleanup_unused_imports_java` and `mcp__lang-tools__cleanup_unused_imports_kotlin`.

### Step 13.2: Run full test suite

- [ ] **Run all tests**

Run: `./gradlew :common:jvmTest :gateway:test :engine:test`
Expected: All PASS

- [ ] **Run CLI tests**

Run: `./gradlew :cli:macosArm64Test`

### Step 13.3: Run 8-dimension code review

- [ ] **Launch 8 parallel code review subagents** per CLAUDE.md instructions

### Step 13.4: Completeness review

- [ ] **Verify all spec requirements are implemented** — check spec against code

### Step 13.5: Commit fixes

- [ ] **Commit any fixes from review**

```bash
git add -A
git commit -m "fix: address code review findings from multi-agent implementation"
```

---

## Task Dependency Order

```
Task 1 (EngineConfig) → Task 2 (GatewayConfig) → Task 3 (Protocol)
    ↓
Task 4 (Compilation fix — all modules)
    ↓
Task 5 (AgentContext/Registry/Factory)
    ↓
Task 6 (EngineLifecycle wiring)
    ↓
Task 7 (MessageProcessor agent dispatch)
    ↓
Task 8 (Gateway channel routing)  — can run in parallel with Task 9
Task 9 (CLI --agent flag)         — can run in parallel with Task 8
    ↓
Task 10 (Migration script) — independent, can start after Task 4
    ↓
Task 11 (Documentation) — after all implementation tasks
    ↓
Task 12 (E2E tests) — after Tasks 8+9
    ↓
Task 13 (Code review + quality) — final gate
```
