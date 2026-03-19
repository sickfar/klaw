# engine.json Configuration Reference

This file documents the structure of `~/.config/klaw/engine.json`, the primary configuration file for the Klaw engine.

> **Interactive editor:** Run `klaw config edit engine` for a TUI that shows all properties with descriptions, validates changes, and saves directly.
>
> **Generated reference:** See [`engine-config-reference.md`](engine-config-reference.md) for the most up-to-date property descriptions (auto-generated from `@ConfigDoc` annotations in source code).

## JSON Schema

A JSON Schema (draft-07) is available at [`engine.schema.json`](engine.schema.json). Add it to your config file for IDE autocompletion:

```json
{
  "$schema": "./engine.schema.json",
  "providers": { ... }
}
```

To generate the latest schema from your installed version:

```bash
klaw doctor --dump-schema engine > engine.schema.json
```

## ENV Variable Substitution

API keys are stored in `~/.config/klaw/.env` and referenced in `engine.json` using `${VAR_NAME}` placeholders.
The engine resolves placeholders via `EnvVarResolver` at startup.

```
~/.config/klaw/.env:
  ZAI_API_KEY=sk-...
  DEEPSEEK_API_KEY=sk-...
```

## Provider Types

Only `openai-compatible` is currently supported. `anthropic-compatible` is planned.

| Type                   | Providers                       |
|------------------------|---------------------------------|
| `openai-compatible`    | Z.ai (GLM-5), DeepSeek, Qwen, Ollama |
| `anthropic-compatible` | Z.ai Anthropic endpoint (P1)    |

## Example engine.json

```json
{
  "providers": {
    "zai": {
      "type": "openai-compatible",
      "endpoint": "https://api.z.ai/api/paas/v4",
      "apiKey": "${ZAI_API_KEY}"
    },
    "deepseek": {
      "type": "openai-compatible",
      "endpoint": "https://api.deepseek.com/v1",
      "apiKey": "${DEEPSEEK_API_KEY}"
    },
    "ollama": {
      "type": "openai-compatible",
      "endpoint": "http://localhost:11434/v1"
    }
  },
  "models": {
    "zai/glm-5": {
      "maxTokens": 8192
    },
    "deepseek/deepseek-chat": {
      "maxTokens": 32768
    },
    "ollama/qwen3:8b": {
      "maxTokens": 32768
    }
  },
  "routing": {
    "default": "zai/glm-5",
    "fallback": ["deepseek/deepseek-chat", "ollama/qwen3:8b"],
    "tasks": {
      "summarization": "zai/glm-5",
      "subagent": "deepseek/deepseek-chat"
    }
  },
  "llm": {
    "maxRetries": 3,
    "requestTimeoutMs": 90000,
    "initialBackoffMs": 500,
    "backoffMultiplier": 2.0
  },
  "memory": {
    "embedding": {
      "type": "onnx",
      "model": "all-MiniLM-L6-v2"
    },
    "chunking": {
      "size": 512,
      "overlap": 64
    },
    "search": {
      "topK": 10
    },
    "injectSummary": false,
    "mapMaxCategories": 10
  },
  "context": {
    "defaultBudgetTokens": 100000,
    "subagentHistory": 10
  },
  "processing": {
    "debounceMs": 500,
    "maxConcurrentLlm": 2,
    "maxToolCallRounds": 50,
    "maxToolOutputChars": 8000,
    "maxDebounceEntries": 1000
  },
  "logging": {
    "subagentConversations": false
  },
  "codeExecution": {
    "dockerImage": "ghcr.io/sickfar/klaw-sandbox:latest",
    "timeout": 30,
    "allowNetwork": false,
    "maxMemory": "256m",
    "maxCpus": "1.0",
    "readOnlyRootfs": true,
    "keepAlive": false,
    "keepAliveIdleTimeoutMin": 5,
    "keepAliveMaxExecutions": 100
  },
  "files": {
    "maxFileSizeBytes": 10485760
  },
  "docs": {
    "enabled": true
  },
  "heartbeat": {
    "interval": "PT1H",
    "model": "zai/glm-5",
    "injectInto": "telegram_123456",
    "channel": "telegram"
  }
}
```

## memory

Configures the memory system: embedding backend, chunking, search, categories, and memory map injection.

Memory facts are stored in the database with categories. On first start, MEMORY.md and daily memory logs are parsed (markdown headers become categories, lines become facts), then archived. The database is the sole source of truth for memory after initial indexation.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `embedding.type` | string | — | Embedding backend: `"onnx"` (local) or `"ollama"`. |
| `embedding.model` | string | — | Embedding model name (e.g. `"all-MiniLM-L6-v2"`). |
| `chunking.size` | int | — | Maximum chunk size in approximate tokens (used by docs indexing). |
| `chunking.overlap` | int | — | Overlap between consecutive chunks in approximate tokens. |
| `search.topK` | int | — | Number of top results from hybrid search. |
| `injectSummary` | bool | `false` | Inject a Memory Map into the system prompt showing top categories by popularity. |
| `mapMaxCategories` | int | `10` | Maximum number of categories displayed in the Memory Map. Remaining categories shown as "...and N more". |

## heartbeat

Configures periodic autonomous LLM monitoring. The engine reads `HEARTBEAT.md` from the workspace at each interval and runs an LLM session with full tool access. The LLM decides whether to deliver results to the user via the `heartbeat_deliver` tool.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `interval` | string | `"off"` | ISO-8601 duration (`"PT1H"`, `"PT30M"`) or `"off"` to disable. |
| `model` | string | `null` | LLM model for heartbeat. `null` uses `routing.default`. |
| `injectInto` | string | `null` | chatId for delivering results (e.g. `"telegram_123456"`). |
| `channel` | string | `null` | Channel for delivery (e.g. `"telegram"`). |

Both `injectInto` and `channel` must be set for delivery to work. If either is missing, heartbeat runs are skipped entirely (no LLM tokens consumed).

`channel` is set automatically by `klaw init` based on the first configured messaging channel. `injectInto` is set at runtime via the `/use-for-heartbeat` command — send it in any chat to pair that chat for heartbeat delivery. The command persists the change to `engine.json`.

See `doc/scheduling/heartbeat.md` for details.

## docs

Controls the built-in documentation service. Documentation is embedded in the engine JAR and indexed at startup.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | bool | `true` | Enable or disable the docs service. When `false`, `docs_search`, `docs_read`, and `docs_list` tools return a disabled message. |

## autoRag

Automatic RAG retrieval injects relevant earlier messages into the context window when the conversation is long enough.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | bool | `true` | Enable automatic RAG retrieval. |
| `topK` | int | `3` | Number of top relevant messages to retrieve. |
| `maxTokens` | int | `400` | Maximum tokens of auto-RAG context to inject. |
| `relevanceThreshold` | double | `0.5` | Minimum relevance score threshold for including results. |
| `minMessageTokens` | int | `10` | Minimum token count in a message to trigger auto-RAG. |

## hostExecution

Controls the `host_exec` tool — running shell commands directly on the host outside the Docker sandbox.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | bool | `false` | Enable host command execution. |
| `allowList` | string[] | `[]` | Commands allowed without user confirmation. |
| `notifyList` | string[] | `[]` | Commands that trigger a notification to the user. |
| `preValidation.enabled` | bool | `true` | Enable LLM-based pre-validation of host commands. |
| `preValidation.model` | string | `""` | Model used for pre-validation checks. |
| `preValidation.riskThreshold` | int | `5` | Risk score threshold above which commands are blocked. |
| `preValidation.timeoutMs` | long | `5000` | Timeout in milliseconds for the pre-validation LLM call. |
| `askTimeoutMin` | int | `0` | Timeout in minutes for user confirmation prompts (0 = no timeout). |

## skills

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `maxInlineSkills` | int | `5` | Maximum number of skills included inline in the system prompt. |

## compatibility

Third-party compatibility settings.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `openclaw.enabled` | bool | `false` | Enable OpenClaw compatibility mode. |
| `openclaw.sync.memoryMd` | bool | `false` | Sync MEMORY.md file with OpenClaw. |
| `openclaw.sync.dailyLogs` | bool | `false` | Sync daily log files with OpenClaw. |
| `openclaw.sync.userMd` | bool | `false` | Sync USER.md file with OpenClaw. |

## summarization

Background compaction of old conversation messages. Uses a fraction-based trigger instead of a fixed token threshold. Compaction creates summaries of older messages; originals are never deleted from the conversation log.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | bool | `false` | Enable background compaction. |
| `summaryBudgetFraction` | double | `0.25` | Fraction of context budget allocated to summaries (exclusive range `(0.0, 1.0)`). |
| `compactionThresholdFraction` | double | `0.5` | Fraction of context budget that defines the compaction zone (exclusive range `(0.0, 1.0)`). Compaction triggers when `messageTokens > budget * (summaryBudgetFraction + compactionThresholdFraction)`. |

**Validation**: `summaryBudgetFraction + compactionThresholdFraction` must be less than `1.0`.

## Notes

- `routing.fallback` lists model IDs tried in order if the primary model fails.
- `ContextLengthExceededError` is never retried and skips the fallback chain.
- Ollama does not require an `apiKey` — omit the field or set to `null`.
- Model IDs may contain colons (e.g. `qwen3:8b`) — use `provider/modelId` as the full ID for routing.
