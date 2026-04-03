# Multi-Agent Architecture Design

**Date:** 2026-04-03
**Status:** Draft

## Context

Klaw currently operates as a single-agent system: one Engine process serves one workspace with one set of memory, sessions, scheduler jobs, and system prompt. Users who want multiple AI personas (personal assistant, work helper, coding bot) must run separate Engine instances.

This design introduces **multi-agent support** — multiple fully isolated agents within a single Engine process, sharing only infrastructure (LLM providers, embedding model, Docker sandbox) while maintaining complete data isolation (DB, workspace, memory, scheduler, sessions, conversations).

## Goals

1. Multiple fully isolated agents in one Engine JVM process
2. Shared LLM providers and embedding infrastructure (no duplication)
3. Per-agent: workspace, database, memory, scheduler, sessions, conversations, skills, MCP servers, tools config
4. Minimal required config — maximum sensible defaults
5. Breaking change accepted — `agents` section is mandatory
6. One Gateway process serving all agents' channels

## Non-Goals

- Agent-to-agent communication (out of scope)
- Dynamic agent creation at runtime (config change + restart)
- Per-agent LLM provider definitions (providers are global)

---

## Config Design

### Principles

- **Map over array**: `agents` is `Map<String, AgentConfig>`, key = agentId (no `id` field)
- **`_defaults` inheritance**: reserved key `_defaults` defines base config inherited by all agents; agents override specific fields via deep merge
- **Channel mapping in Gateway only**: `engine.json` defines agents and their settings; `gateway.json` maps channels to agents via `agentId` field. No duplication.
- **Maximum defaults**: only `providers` (at least one), `routing.default`, and `agents` (at least one with `workspace`) are truly required

### Minimal engine.json

```json
{
  "providers": {
    "deepseek": {
      "apiKey": "${DEEPSEEK_API_KEY}",
      "models": ["deepseek-chat"]
    }
  },
  "routing": {
    "default": "deepseek/deepseek-chat"
  },
  "agents": {
    "default": {
      "workspace": "~/.local/share/klaw/agents/default"
    }
  }
}
```

### Full engine.json (two agents, customized)

```json
{
  "providers": {
    "deepseek": {
      "apiKey": "${DEEPSEEK_API_KEY}",
      "models": ["deepseek-chat", "deepseek-reasoner"]
    },
    "glm": {
      "apiKey": "${GLM_API_KEY}",
      "models": ["glm-4-plus", "glm-4v-plus"]
    }
  },
  "routing": {
    "default": "deepseek/deepseek-chat"
  },
  "processing": {
    "debounceMs": 800,
    "maxConcurrentLlm": 3,
    "maxToolCallRounds": 50
  },
  "agents": {
    "_defaults": {
      "memory": {
        "consolidation": { "enabled": true }
      },
      "heartbeat": { "enabled": true },
      "tools": { "sandbox": { "enabled": true } }
    },
    "default": {
      "workspace": "~/.local/share/klaw/agents/default",
      "routing": { "default": "glm/glm-4-plus" },
      "processing": { "temperature": 0.9 }
    },
    "work": {
      "enabled": true,
      "workspace": "~/.local/share/klaw/agents/work",
      "routing": {
        "default": "deepseek/deepseek-chat",
        "tasks": { "subagent": "deepseek/deepseek-reasoner" }
      },
      "processing": {
        "slidingWindow": 50,
        "temperature": 0.3
      },
      "limits": {
        "maxConcurrentRequests": 2,
        "maxMessagesPerMinute": 20
      },
      "tools": {
        "hostExec": { "enabled": true, "allowList": ["git", "docker"] }
      }
    }
  }
}
```

### gateway.json (channel-to-agent mapping)

Channels are structured as `Map<Type, Map<Name, ChannelConfig>>` — grouped by transport type, keyed by arbitrary channel name. This allows multiple channels of the same type (e.g., two Telegram bots for different agents).

```json
{
  "channels": {
    "telegram": {
      "personal": {
        "token": "${TG_PERSONAL_TOKEN}",
        "agentId": "default",
        "allowedChats": [{ "id": 123456, "type": "private" }]
      },
      "work": {
        "token": "${TG_WORK_TOKEN}",
        "agentId": "work",
        "allowedChats": []
      }
    },
    "discord": {
      "work-guild": {
        "token": "${KLAW_DISCORD_TOKEN}",
        "agentId": "work"
      }
    },
    "websocket": {
      "default": {
        "agentId": "default"
      }
    }
  }
}
```

Channel name (map key) is used as the `channel` field in SocketMessage and in logs. Type is inferred from the parent key (`telegram`, `discord`, `websocket`).

### Default Values (moved from required to optional)

| Field | New Default | Previously |
|-------|-------------|-----------|
| `processing.debounceMs` | `800` | required |
| `processing.maxConcurrentLlm` | `3` | required |
| `processing.maxToolCallRounds` | `50` | required |
| `memory.embedding.type` | `"onnx"` | required by init |
| `memory.chunking.size` | `512` | init override |
| `memory.chunking.overlap` | `64` | init override |
| `memory.search.topK` | `10` | required |
| `context.subagentHistory` | `10` | required |
| `routing.tasks.summarization` | falls back to `routing.default` | required |
| `routing.tasks.subagent` | falls back to `routing.default` | required |
| `heartbeat.interval` | `"PT1H"` | required by init |

### `_defaults` Deep Merge Semantics

- `_defaults` is a reserved key — never treated as an agent ID
- Each agent config is deep-merged with `_defaults`: agent values override defaults at the leaf level
- `null` value in agent config explicitly removes a defaults field
- Merge happens at Engine startup in `AgentRegistry` initialization

### Per-Agent Overridable Fields

These fields can appear both at global level and inside `agents.{id}`:

| Field | Global (engine.json root) | Per-Agent Override |
|-------|--------------------------|-------------------|
| `routing` | default routing for all agents | agent can override `default`, `tasks.*` |
| `processing` | global defaults | agent can override `slidingWindow`, `temperature`, `maxOutputTokens` |
| `memory` | global memory config | agent can override `consolidation`, `chunking`, `search`, `autoRag` |
| `heartbeat` | global heartbeat config | agent can override `enabled`, `cron`, `interval` |
| `tools` | — | per-agent only: `sandbox`, `hostExec` settings |
| `mcp` | — | per-agent only: MCP server definitions |
| `limits` | — | per-agent only: rate limits |
| `vision` | global vision config | agent can override `enabled`, `model` |

Fields that are **always global** (never per-agent): `providers`, `embedding`, `httpRetry`, `database`, `web`, `documents`, `logging`.

---

## Architecture: AgentContext Registry

### Pattern

An `AgentContext` class bundles all per-agent state. A singleton `AgentRegistry` holds `Map<String, AgentContext>`, populated at startup. Every inbound message carries `agentId`; the Engine resolves the `AgentContext` before dispatching.

### Component Diagram

```
Engine Process
├── SharedServices (singletons)
│   ├── LlmRouter (shared HTTP clients for all providers)
│   ├── EmbeddingService (ONNX model, thread-safe)
│   ├── SandboxManager (shared Docker client)
│   ├── EngineSocketServer (single TCP listener)
│   └── EngineConfig (global config)
│
├── AgentRegistry
│   ├── "default" → AgentContext
│   │   ├── KlawDatabase (klaw-default.db)
│   │   ├── QuartzScheduler (scheduler-default.db)
│   │   ├── SessionManager
│   │   ├── MessageRepository
│   │   ├── MemoryService
│   │   ├── WorkspaceLoader (SOUL.md, IDENTITY.md, ...)
│   │   ├── ContextBuilder
│   │   ├── SkillRegistry
│   │   ├── ToolRegistry
│   │   ├── McpToolRegistry
│   │   ├── AutoRagService
│   │   ├── HeartbeatRunner
│   │   ├── CompactionRunner
│   │   ├── SummaryService
│   │   └── BackupService
│   │
│   └── "work" → AgentContext
│       ├── KlawDatabase (klaw-work.db)
│       ├── QuartzScheduler (scheduler-work.db)
│       └── ... (same structure, different data)
│
└── MessageProcessor (singleton, resolves AgentContext per message)
```

### AgentContext class

```kotlin
class AgentContext(
    val agentId: String,
    val agentConfig: AgentConfig,
    val effectiveConfig: EffectiveAgentConfig, // merged: global + _defaults + agent overrides
    val database: KlawDatabase,
    val driver: JdbcSqliteDriver,
    val sessionManager: SessionManager,
    val messageRepository: MessageRepository,
    val memoryService: MemoryService,
    val workspaceLoader: KlawWorkspaceLoader,
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
)
```

### AgentContextFactory

A `@Singleton` that receives shared services via constructor injection and constructs `AgentContext` instances imperatively (not through Micronaut DI):

```kotlin
@Singleton
class AgentContextFactory(
    private val llmRouter: LlmRouter,
    private val embeddingService: EmbeddingService,
    private val sandboxManager: SandboxManager,
    private val sqliteVecLoader: SqliteVecLoader,
    private val globalConfig: EngineConfig,
) {
    fun create(agentId: String, agentConfig: AgentConfig): AgentContext {
        val effectiveConfig = mergeConfig(globalConfig, agentConfig)
        val driver = createDriver("klaw-$agentId.db")
        val database = createDatabase(driver)
        // ... wire up all per-agent services
        return AgentContext(...)
    }
}
```

### DI Changes

**Removed from Micronaut DI** (become part of AgentContext, constructed by AgentContextFactory):
- `DatabaseFactory`
- `MemoryServiceImplFactory`
- `KlawWorkspaceLoaderFactory`
- `FileSkillRegistryFactory`
- `McpToolRegistryFactory`
- `SessionManager`
- `MessageRepository`
- `ContextBuilder`
- `KlawSchedulerImpl`
- `HeartbeatRunnerFactory`
- `SummaryService` / `SummaryRepository`
- `CompactionRunner`
- `SubagentRunRepository`
- `BackupService`
- `AutoRagService`
- `MessageEmbeddingService`

**Remain as Micronaut singletons:**
- `EngineConfig` / `EngineConfigFactory`
- `LlmRouter` / `LlmClient` instances
- `EmbeddingService` (ONNX/Ollama)
- `SqliteVecLoader`
- `EngineSocketServer` / `SocketFactory`
- `SandboxManager`
- `AgentRegistry`
- `AgentContextFactory`
- `MessageProcessor` (resolves AgentContext per message)
- `CliCommandDispatcher`
- Web tools (`WebFetchService`, `WebSearchService`)

---

## Protocol Changes

### SocketMessage: add agentId

All Gateway-to-Engine and Engine-to-Gateway messages gain `agentId: String`:

```kotlin
@Serializable
@SerialName("chat")
data class ChatMessage(
    val agentId: String,  // NEW
    val chatId: String,
    val channel: String,
    val text: String,
    // ...
)
```

Affected message types:
- `ChatMessage` (inbound)
- `CommandMessage` (inbound, slash commands)
- `AssistantResponse` (outbound)
- `StreamDeltaMessage` (outbound)
- `StreamEndMessage` (outbound)
- `ApprovalRequestMessage` (outbound)
- `ApprovalResponseMessage` (inbound)
- `ApprovalDismissMessage` (outbound)
- `TypingMessage` (outbound)
- `StatusMessage` (outbound)

`ShutdownMessage` — no agentId needed (global operation).

### CliRequestMessage: add agentId

```kotlin
@Serializable
data class CliRequestMessage(
    val agentId: String = "default",  // NEW, defaults to "default"
    val command: String,
    val args: List<String> = emptyList(),
)
```

---

## Database Isolation

### Per-agent SQLite files

```
~/.local/state/klaw/
├── klaw-default.db
├── klaw-default.db-wal
├── scheduler-default.db
├── klaw-work.db
├── klaw-work.db-wal
├── scheduler-work.db
└── logs/
```

- Each agent gets its own `klaw-{agentId}.db` (messages, sessions, memory, summaries, subagent runs)
- Each agent gets its own `scheduler-{agentId}.db` (Quartz jobs)
- Schema is identical across all agent DBs — no `agent_id` columns needed
- Agent removal = delete two DB files + workspace directory

### Migration from single-agent

Since there are no public releases and a single developer installation, migration is handled by a one-time bash script (`scripts/migrate-to-multiagent.sh`, requires `jq`) that:

**1. Config migration (engine.json):**
- Reads existing `engine.json`
- Extracts per-agent fields and moves them under `agents.default`:
  - `workspace` → `agents.default.workspace`
  - `heartbeat` → `agents.default.heartbeat`
  - `memory.consolidation` → `agents.default.memory.consolidation`
  - `vision` → `agents.default.vision`
  - `hostExecution` → `agents.default.tools.hostExec`
  - `codeExecution` → `agents.default.tools.sandbox`
  - `mcp` → `agents.default.mcp`
- Keeps global fields at top level: `providers`, `models`, `routing`, `processing`, `memory` (embedding, chunking, search, autoRag), `context`, `web`, `httpRetry`, `database`, `documents`, `logging`
- Strips fields that match new defaults (cleanup)
- Writes updated `engine.json`

**2. Config migration (gateway.json):**
- Reads existing `gateway.json`
- Transforms flat channel structure to nested `Map<Type, Map<Name, Config>>`:
  - `channels.telegram: {...}` → `channels.telegram.default: {agentId: "default", ...}`
  - `channels.discord: {...}` → `channels.discord.default: {agentId: "default", ...}`
  - `channels.localWs: {...}` → `channels.websocket.default: {agentId: "default", ...}`
- Writes updated `gateway.json`

**3. Data file migration:**
- `klaw.db` → `klaw-default.db`
- `scheduler.db` → `scheduler-default.db`
- `conversations/{chatId}/` → `conversations/default/{chatId}/` (move all chat dirs under `default/`)

**4. Safety:**
- Creates backup of all files before migration (`*.pre-multiagent-backup`)
- Dry-run mode by default (`--apply` flag to actually write)
- Prints diff of config changes before applying

**5. Development & testing:**
- Fetch live configs from Pi for local testing: `scp sickfar-pi.local:~/.config/klaw/*.json /tmp/klaw-migrate-test/`
- Run script in dry-run mode on fetched configs locally
- Verify output, then run on Pi with `--apply`

The script is temporary — removed after migration is complete. Requires only `bash` and `jq` (available on Raspberry Pi OS).

---

## Conversations JSONL Scoping

Gateway writes JSONL to `conversations/{agentId}/{chatId}/{date}.jsonl`:

```
~/.local/share/klaw/conversations/
├── default/
│   ├── 123456/
│   │   └── 2026-04-03.jsonl
│   └── 789012/
├── work/
│   └── discord_guild_ch/
```

`ConversationJsonlWriter` receives `agentId` from the inbound message and scopes the directory accordingly.

---

## Gateway Changes

### Channel-to-Agent mapping

Gateway config is `Map<Type, Map<Name, ChannelConfig>>`. At startup, Gateway iterates all channel entries, creates a transport instance per entry, and stores the `agentId` + `channelName` (the map key) for each.

```kotlin
// TelegramChannel — one instance per entry in channels.telegram map
class TelegramChannel(
    private val channelName: String,  // map key, e.g. "personal"
    private val config: TelegramChannelConfig,
    private val agentId: String,  // from config
) {
    fun onMessage(update: Update) {
        val msg = ChatMessage(
            agentId = agentId,
            chatId = update.chatId.toString(),
            channel = channelName,  // "personal", not "telegram"
            text = update.text,
        )
        socketClient.send(msg)
    }
}
```

### GatewayOutboundHandler

Responses from Engine carry `channel` (channel name, e.g. `"personal"`) + `chatId`. Gateway maintains a `Map<String, ChannelTransport>` keyed by channel name for outbound routing. The `agentId` in responses is informational — Gateway doesn't need it for routing.

### ConversationJsonlWriter

Now takes `agentId` to scope the conversations directory:

```kotlin
fun write(agentId: String, chatId: String, message: JsonElement) {
    val dir = conversationsDir / agentId / chatId
    // ...
}
```

---

## CLI Changes

### `--agent` flag

All agent-scoped CLI commands get `--agent` option (default: `"default"`):

```
klaw chat [--agent <id>]           # default: "default"
klaw memory search [--agent <id>]
klaw memory categories [--agent <id>]
klaw sessions list [--agent <id>]
klaw sessions cleanup [--agent <id>]
klaw schedule list [--agent <id>]
klaw schedule add [--agent <id>]
klaw reindex [--agent <id>]
klaw context [--agent <id>]
```

Commands that are global (no agent scoping):
```
klaw status          # shows all agents' status
klaw doctor          # checks all agents
klaw config          # edits global config
klaw service         # manages processes
klaw update          # CLI update
```

### `klaw init` changes

The wizard creates `agents` section with one `"default"` agent. Steps:

1. Select provider & model (as before)
2. Set workspace path → becomes `agents.default.workspace`
3. Configure channels → `gateway.json` with `agentId: "default"`
4. Optional: add more agents (interactive loop)

### `klaw agents` command (new)

```
klaw agents list                    # list all configured agents with status
klaw agents add <id>                # interactive: add new agent to config
klaw agents remove <id>             # remove agent (with confirmation)
```

---

## Web UI Changes

### Agent-scoped REST API

All endpoints gain `agentId` path parameter:

```
GET    /api/agents                              # list all agents
GET    /api/agents/{agentId}/status             # agent status
GET    /api/agents/{agentId}/sessions           # sessions list
GET    /api/agents/{agentId}/memory/categories  # memory categories
GET    /api/agents/{agentId}/memory/facts       # memory facts
GET    /api/agents/{agentId}/schedule           # scheduled jobs
POST   /api/agents/{agentId}/schedule           # create job
WS     /api/agents/{agentId}/chat               # WebSocket chat
```

### UI: Agent Switcher

- Sidebar/header shows agent selector dropdown
- Each agent has its own chat history, memory view, schedule view
- Dashboard shows aggregate stats for all agents + per-agent breakdown
- Agent color/icon for visual distinction (optional, cosmetic)

---

## Per-Agent Resource Limits

```kotlin
@Serializable
data class AgentLimitsConfig(
    val maxConcurrentRequests: Int = 0,    // 0 = unlimited
    val maxMessagesPerMinute: Int = 0,     // 0 = unlimited
)
```

Enforced in `MessageProcessor` before dispatching to LLM. Per-agent limiter sits **before** the global `PriorityLlmLimiter`:

```
Inbound message
  → resolve AgentContext
  → per-agent rate limiter (maxMessagesPerMinute)
  → per-agent concurrency limiter (maxConcurrentRequests)
  → global PriorityLlmLimiter (maxConcurrentLlm)
  → LLM call
```

---

## Agent Lifecycle: enabled/disabled

```kotlin
@Serializable
data class AgentConfig(
    val enabled: Boolean = true,
    val workspace: String,
    val routing: RoutingOverride? = null,
    val processing: ProcessingOverride? = null,
    val memory: MemoryOverride? = null,
    val heartbeat: HeartbeatOverride? = null,
    val tools: ToolsConfig? = null,
    val mcp: McpConfig? = null,
    val limits: AgentLimitsConfig = AgentLimitsConfig(),
    val vision: VisionOverride? = null,
)
```

- `enabled: false` → `AgentRegistry` skips creating `AgentContext` for this agent
- Messages to disabled agent → error response "Agent 'work' is disabled"
- `klaw agents list` shows disabled agents with `[disabled]` marker

---

## Scheduler & Heartbeat Per-Agent

### Scheduler

Each `AgentContext` has its own Quartz `Scheduler` instance with `scheduler-{agentId}.db`. The `MicronautJobFactory` receives `AgentRegistry` to resolve `AgentContext` at job execution time.

`ScheduledMessageJob.execute()`:
```kotlin
val agentId = jobDataMap.getString("agentId")
val ctx = agentRegistry.get(agentId)
// use ctx.contextBuilder, ctx.sessionManager, etc.
```

### Heartbeat

Each agent with `heartbeat.enabled = true` gets its own `HeartbeatRunner`. The runner reads `HEARTBEAT.md` from the agent's workspace and uses the agent's scheduler/session/context.

---

## Workspace Auto-Creation

On Engine startup, if an agent's `workspace` directory doesn't exist:
1. Create the directory
2. Populate with template files: `SOUL.md`, `IDENTITY.md`, `USER.md`, `AGENTS.md`, `TOOLS.md`
3. Log at INFO level: "Created workspace for agent '{agentId}' at {path}"

Template content comes from bundled resources (same as current `klaw init` templates).

---

## Error Handling

- Unknown `agentId` in inbound message → log WARN + send error response to channel
- Disabled agent receives message → send "Agent '{id}' is disabled" response
- Agent DB fails to open → log ERROR + skip agent, mark as unhealthy in `klaw status`
- Gateway `agentId` doesn't match any Engine agent → Gateway starts normally but logs WARN at startup ("Channel 'telegram' references unknown agent 'foo'")

---

## Testing Strategy

- **Unit tests**: `AgentContextFactory` creates context with in-memory SQLite
- **Integration tests**: two-agent Engine with separate DBs, verify isolation (message to agent A doesn't appear in agent B's session/memory)
- **E2E tests**: Docker containers with two agents, two mock Telegram bots, verify end-to-end message routing and isolation
- **Config parsing tests**: minimal config, full config, `_defaults` merge, invalid configs

---

## Shared vs Per-Agent Summary

| Component | Shared | Per-Agent |
|-----------|--------|-----------|
| LLM providers & HTTP clients | yes | |
| Model catalog | yes | |
| Embedding service (ONNX/Ollama) | yes | |
| Docker sandbox manager | yes | |
| Web fetch/search services | yes | |
| HTTP retry config | yes | |
| Database config (busyTimeout, backups) | yes | |
| Engine socket server (TCP) | yes | |
| Routing (default model) | yes (base) | override |
| Processing (slidingWindow, temperature) | yes (base) | override |
| klaw.db | | yes (`klaw-{id}.db`) |
| scheduler.db | | yes (`scheduler-{id}.db`) |
| Workspace (SOUL.md, skills, etc.) | | yes |
| Memory (categories, facts, embeddings) | | yes |
| Sessions | | yes |
| Conversations JSONL | | yes |
| Scheduler jobs | | yes |
| Heartbeat | | yes |
| Skills | | yes |
| MCP servers | | yes |
| Tool permissions (sandbox, hostExec) | | yes |
| Auto-RAG | | yes |
| Resource limits | | yes |
| Vision config | yes (base) | override |
